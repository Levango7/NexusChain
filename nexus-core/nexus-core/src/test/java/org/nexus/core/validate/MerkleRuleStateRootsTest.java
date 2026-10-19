package org.nexus.core.validate;

import org.junit.jupiter.api.Test;
import org.nexus.command.IncubatorAddress;
import org.nexus.core.Block;
import org.nexus.core.account.Account;
import org.nexus.core.account.AccountDB;
import org.nexus.core.account.Transaction;
import org.nexus.core.incubator.Incubator;
import org.nexus.core.incubator.IncubatorDB;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PLAN-014 Phase 1 回归：{@link MerkleRule#verifyStateRoots} 提取后的行为等价性。
 *
 * <p>覆盖：状态根一致 / 状态根不一致 / 孵化根不一致（validator）/
 * 交易所节点跳过孵化根 / 交易所在状态根上仍严格校验 / validateBlock
 * 走提取后的同一校验路径。</p>
 */
public class MerkleRuleStateRootsTest {

    private static final long HEIGHT = 7L;

    @Test
    void acceptsConsistentStateAndIncubateRoots() {
        MerkleRule rule = new MerkleRule("validator");
        List<Account> accounts = accounts();

        Result result = rule.verifyStateRoots(
                block(Block.calculateMerkleState(accounts), new byte[Block.HASH_SIZE]),
                accounts, new ArrayList<>());

        assertTrue(result.isSuccess(), result.getMessage());
    }

    @Test
    void rejectsStateRootMismatch() {
        MerkleRule rule = new MerkleRule("validator");
        List<Account> accounts = accounts();

        Result result = rule.verifyStateRoots(
                block(filled(Block.HASH_SIZE, (byte) 0x01), new byte[Block.HASH_SIZE]),
                accounts, new ArrayList<>());

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("merkle state validate fail"), result.getMessage());
    }

    @Test
    void rejectsIncubateRootMismatchOnValidatorNode() {
        MerkleRule rule = new MerkleRule("validator");
        List<Account> accounts = accounts();
        List<Incubator> incubators = incubators();

        Result result = rule.verifyStateRoots(
                block(Block.calculateMerkleState(accounts), filled(Block.HASH_SIZE, (byte) 0x02)),
                accounts, incubators);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("merkle incubate validate fail"), result.getMessage());
    }

    @Test
    void exchangeNodeSkipsIncubateRoot() {
        MerkleRule rule = new MerkleRule("exchange");
        List<Account> accounts = accounts();

        Result result = rule.verifyStateRoots(
                block(Block.calculateMerkleState(accounts), filled(Block.HASH_SIZE, (byte) 0x02)),
                accounts, incubators());

        assertTrue(result.isSuccess(), result.getMessage());
    }

    @Test
    void exchangeNodeStillRejectsStateRootMismatch() {
        MerkleRule rule = new MerkleRule("exchange");
        List<Account> accounts = accounts();

        Result result = rule.verifyStateRoots(
                block(filled(Block.HASH_SIZE, (byte) 0x03), new byte[Block.HASH_SIZE]),
                accounts, incubators());

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("merkle state validate fail"), result.getMessage());
    }

    @Test
    void validateBlockUsesExtractedRootChecks() throws Exception {
        AccountDB accountDB = mock(AccountDB.class);
        IncubatorDB incubatorDB = mock(IncubatorDB.class);
        byte[] proposerHash = filled(Transaction.PUBLIC_KEY_HASH_SIZE, (byte) 0x11);
        stubAccounts(accountDB, proposerHash);

        MerkleRule rule = new MerkleRule("validator");
        ReflectionTestUtils.setField(rule, "accountDB", accountDB);
        ReflectionTestUtils.setField(rule, "incubatorDB", incubatorDB);

        Block block = coinbaseBlock(rule, proposerHash);
        Result success = rule.validateBlock(block);
        assertTrue(success.isSuccess(), success.getMessage());

        block.hashMerkleState = filled(Block.HASH_SIZE, (byte) 0x04);
        Result tampered = rule.validateBlock(block);
        assertFalse(tampered.isSuccess());
        assertTrue(tampered.getMessage().contains("merkle state validate fail"), tampered.getMessage());
    }

    private static Block coinbaseBlock(MerkleRule rule, byte[] proposerHash) throws Exception {
        Transaction tx = new Transaction();
        tx.type = Transaction.Type.COINBASE.ordinal();
        tx.from = new byte[Transaction.PUBLIC_KEY_SIZE];
        tx.to = proposerHash;
        tx.amount = 500L;
        tx.gasPrice = 0;
        tx.payload = new byte[0];
        tx.signature = new byte[64];
        List<Transaction> body = new ArrayList<>();
        body.add(tx);

        @SuppressWarnings("unchecked")
        Map<String, Object> merklemap = rule.validateMerkle(body, HEIGHT);
        @SuppressWarnings("unchecked")
        List<Account> accounts = (List<Account>) merklemap.get("account");
        @SuppressWarnings("unchecked")
        List<Incubator> incubators = (List<Incubator>) merklemap.get("incubator");

        Block block = new Block();
        block.nVersion = 1;
        block.nHeight = HEIGHT;
        block.nTime = 1_700_000_000_000L;
        block.nBits = filled(Block.HASH_SIZE, (byte) 0x01);
        block.nNonce = filled(Block.HASH_SIZE, (byte) 0x02);
        block.hashPrevBlock = filled(Block.HASH_SIZE, (byte) 0x03);
        block.body = body;
        block.hashMerkleRoot = Block.calculateMerkleRoot(body);
        block.hashMerkleState = Block.calculateMerkleState(accounts);
        block.hashMerkleIncubate = Block.calculateMerkleIncubate(incubators);
        return block;
    }

    private static void stubAccounts(AccountDB accountDB, byte[] proposerHash) {
        when(accountDB.selectaccount(any())).thenAnswer(invocation -> {
            byte[] hash = invocation.getArgument(0);
            if (hash == null) {
                return null;
            }
            if (Arrays.equals(hash, IncubatorAddress.resultpubhash())) {
                return new Account(HEIGHT - 1, IncubatorAddress.resultpubhash(), 0, 0L, 0L, 0L, 0L);
            }
            if (Arrays.equals(hash, proposerHash)) {
                return new Account(HEIGHT - 1, proposerHash, 0, 100L, 0L, 0L, 0L);
            }
            return null;
        });
    }

    private static Block block(byte[] stateRoot, byte[] incubateRoot) {
        Block block = new Block();
        block.nHeight = HEIGHT;
        block.hashMerkleState = stateRoot;
        block.hashMerkleIncubate = incubateRoot;
        return block;
    }

    private static List<Account> accounts() {
        List<Account> accounts = new ArrayList<>();
        accounts.add(new Account(HEIGHT, new byte[Transaction.PUBLIC_KEY_HASH_SIZE], 1, 100L, 0L, 0L, 0L));
        accounts.add(new Account(HEIGHT, filled(Transaction.PUBLIC_KEY_HASH_SIZE, (byte) 0x44), 0, 5L, 0L, 0L, 0L));
        return accounts;
    }

    private static List<Incubator> incubators() {
        List<Incubator> incubators = new ArrayList<>();
        incubators.add(new Incubator(filled(Transaction.PUBLIC_KEY_HASH_SIZE, (byte) 0x55),
                filled(Transaction.PUBLIC_KEY_HASH_SIZE, (byte) 0x66),
                filled(Block.HASH_SIZE, (byte) 0x07),
                HEIGHT, 100L, 2L, 3L, HEIGHT, HEIGHT));
        return incubators;
    }

    private static byte[] filled(int size, byte value) {
        byte[] out = new byte[size];
        Arrays.fill(out, value);
        return out;
    }
}
