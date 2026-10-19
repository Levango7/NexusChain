package org.nexus.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.command.IncubatorAddress;
import org.nexus.core.account.Account;
import org.nexus.core.account.AccountDB;
import org.nexus.core.account.Transaction;
import org.nexus.core.incubator.Incubator;
import org.nexus.core.incubator.IncubatorDB;
import org.nexus.core.validate.MerkleRule;
import org.nexus.keystore.crypto.RipemdUtility;
import org.nexus.keystore.crypto.SHA3Utility;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PLAN-014 Phase 1 回归：状态重放管理器（重放 → 持久化 → 状态根校验）。
 *
 * <p>覆盖：一致区块（重放后根一致 + 落库）/ 声明根不一致（观察位不抛异常、
 * 仍持久化、计数）/ 同步路径"块体不完整"导致的重放根不一致（PLAN-012
 * 死锁场景的最小复现）/ 重放失败向上抛出（由 StatetreeUpdate 转
 * AccountUpdateFailedEvent）。</p>
 */
public class StateReplayManagerTest {

    private static final long HEIGHT = 10L;

    private AccountDB accountDB;
    private IncubatorDB incubatorDB;
    private MerkleRule merkleRule;
    private StateReplayManager manager;

    private byte[] proposerHash;
    private byte[] recipientHash;
    private byte[] fromPubkey;
    private byte[] fromPubkeyHash;

    @BeforeEach
    void setUp() {
        accountDB = mock(AccountDB.class);
        incubatorDB = mock(IncubatorDB.class);

        merkleRule = new MerkleRule("validator");
        ReflectionTestUtils.setField(merkleRule, "accountDB", accountDB);
        ReflectionTestUtils.setField(merkleRule, "incubatorDB", incubatorDB);

        manager = new StateReplayManager();
        ReflectionTestUtils.setField(manager, "merkleRule", merkleRule);
        ReflectionTestUtils.setField(manager, "accountDB", accountDB);
        ReflectionTestUtils.setField(manager, "incubatorDB", incubatorDB);

        proposerHash = filled(Transaction.PUBLIC_KEY_HASH_SIZE, (byte) 0x11);
        recipientHash = filled(Transaction.PUBLIC_KEY_HASH_SIZE, (byte) 0x22);
        fromPubkey = filled(Transaction.PUBLIC_KEY_SIZE, (byte) 0x33);
        fromPubkeyHash = RipemdUtility.ripemd160(SHA3Utility.keccak256(fromPubkey));

        // 每次查询返回全新实例，避免重放对夹具的可变状态造成二次影响
        when(accountDB.selectaccount(any())).thenAnswer(invocation -> {
            byte[] hash = invocation.getArgument(0);
            if (hash == null) {
                return null;
            }
            if (Arrays.equals(hash, IncubatorAddress.resultpubhash())) {
                return account(HEIGHT - 1, IncubatorAddress.resultpubhash(), 0, 10_000_000L);
            }
            if (Arrays.equals(hash, fromPubkeyHash)) {
                return account(HEIGHT - 1, fromPubkeyHash, 0, 1_000L);
            }
            if (Arrays.equals(hash, recipientHash)) {
                return account(HEIGHT - 1, recipientHash, 0, 0L);
            }
            return null;
        });
    }

    @Test
    void replaysPersistsAndVerifiesConsistentBlock() throws Exception {
        Block block = block(body());

        StateReplayManager.ReplayResult result = manager.replayBlock(block);

        assertTrue(result.isVerified(), result.getVerifyMessage());
        assertEquals(HEIGHT, result.getHeight());
        assertEquals(2, result.getTxCount());
        assertEquals(3, result.getAccountCount());
        assertEquals(0, result.getIncubatorCount());
        verify(accountDB).insertAccountList(anyList());
        verify(incubatorDB).insertIncubatorList(anyList());
        assertEquals(1L, manager.getReplayedBlocks());
        assertEquals(0L, manager.getStateRootMismatches());
    }

    @Test
    void observesMismatchWithoutBlockingAndStillPersists() throws Exception {
        Block block = block(body());
        block.hashMerkleState = filled(Block.HASH_SIZE, (byte) 0x7f);

        StateReplayManager.ReplayResult result = manager.replayBlock(block);

        assertFalse(result.isVerified());
        assertTrue(result.getVerifyMessage().contains("merkle state validate fail"),
                result.getVerifyMessage());
        // 观察位语义：状态仍按重放结果持久化，不抛异常、不阻断确认
        verify(accountDB).insertAccountList(anyList());
        assertEquals(1L, manager.getReplayedBlocks());
        assertEquals(1L, manager.getStateRootMismatches());
    }

    @Test
    void detectsRootMismatchWhenBlockBodyIncomplete() throws Exception {
        List<Transaction> fullBody = body();
        Block block = block(fullBody);
        // 模拟同步节点收到不完整块体：声明根来自完整块，本地只剩 coinbase
        block.body = new ArrayList<>(fullBody.subList(0, 1));

        StateReplayManager.ReplayResult result = manager.replayBlock(block);

        assertFalse(result.isVerified());
        assertTrue(result.getVerifyMessage().contains("merkle state validate fail"),
                result.getVerifyMessage());
        assertEquals(1, result.getTxCount());
    }

    @Test
    void propagatesReplayFailureToCaller() throws Exception {
        Block block = block(body());
        when(accountDB.selectaccount(any())).thenReturn(null);

        assertThrows(RuntimeException.class, () -> manager.replayBlock(block));
        assertEquals(0L, manager.getReplayedBlocks());
    }

    private List<Transaction> body() {
        List<Transaction> body = new ArrayList<>();
        body.add(tx(Transaction.Type.COINBASE.ordinal(),
                new byte[Transaction.PUBLIC_KEY_SIZE], proposerHash, 500L, 0));
        body.add(tx(Transaction.Type.TRANSFER.ordinal(),
                fromPubkey, recipientHash, 10L, 1));
        return body;
    }

    private Block block(List<Transaction> body) throws Exception {
        Block block = new Block();
        block.nVersion = 1;
        block.nHeight = HEIGHT;
        block.nTime = 1_700_000_000_000L;
        block.nBits = filled(Block.HASH_SIZE, (byte) 0x01);
        block.nNonce = filled(Block.HASH_SIZE, (byte) 0x02);
        block.hashPrevBlock = filled(Block.HASH_SIZE, (byte) 0x03);
        block.body = body;
        // 出块方视角：用同一重放引擎推导声明根（Miner 生产路径同源）
        @SuppressWarnings("unchecked")
        Map<String, Object> merklemap = merkleRule.validateMerkle(body, HEIGHT);
        @SuppressWarnings("unchecked")
        List<Account> accounts = (List<Account>) merklemap.get("account");
        @SuppressWarnings("unchecked")
        List<Incubator> incubators = (List<Incubator>) merklemap.get("incubator");
        block.hashMerkleRoot = Block.calculateMerkleRoot(body);
        block.hashMerkleState = Block.calculateMerkleState(accounts);
        block.hashMerkleIncubate = Block.calculateMerkleIncubate(incubators);
        return block;
    }

    private static Transaction tx(int type, byte[] from, byte[] to, long amount, long nonce) {
        Transaction tx = new Transaction();
        tx.type = type;
        tx.from = from;
        tx.to = to;
        tx.amount = amount;
        tx.nonce = nonce;
        tx.gasPrice = 0;
        tx.payload = new byte[0];
        tx.signature = new byte[64];
        return tx;
    }

    private static Account account(long height, byte[] pubkeyHash, long nonce, long balance) {
        return new Account(height, pubkeyHash, nonce, balance, 0L, 0L, 0L);
    }

    private static byte[] filled(int size, byte value) {
        byte[] out = new byte[size];
        Arrays.fill(out, value);
        return out;
    }
}
