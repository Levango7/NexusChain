package org.nexus.consensus.pow;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.nexus.core.Block;
import org.nexus.core.StateCommitment;
import org.nexus.core.account.Account;
import org.nexus.core.account.Transaction;
import org.nexus.core.payment.BatchTransferPayload;
import org.nexus.core.payment.PaymentExecutionKernel;
import org.nexus.db.AccountState;
import org.nexus.db.StateDB;
import org.nexus.keystore.crypto.RipemdUtility;
import org.nexus.keystore.crypto.SHA3Utility;
import org.nexus.keystore.wallet.KeystoreAction;
import org.nexus.pool.PeningTransPool;
import org.nexus.pool.TransPool;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PackageMinerBatchExecutionTest {

    @Test
    void activeBatchIsPackedAndDebitedExactlyOnce() throws Exception {
        Fixture fixture = fixture(batchTx(), 100, 100);

        assertEquals(1, fixture.packed.size());
        assertEquals(100, fixture.packed.get(0).height);
        assertEquals(49_970, fixture.account(senderHash).getBalance());
        assertEquals(10, fixture.account(hash(2)).getBalance());
        assertEquals(20, fixture.account(hash(3)).getBalance());
    }

    @Test
    void preActivationAndUnsupportedTypesAreNotPackedWithoutAccounting() throws Exception {
        Transaction tx = batchTx();
        Fixture before = fixture(tx, 99, 100);
        assertTrue(before.packed.isEmpty());
        assertEquals(100_000, before.account(senderHash).getBalance());

        Transaction unsupported = batchTx();
        unsupported.type = Transaction.Type.CHANNEL_OPEN.ordinal();
        Fixture closed = fixture(unsupported, 100, 100);
        assertTrue(closed.packed.isEmpty());
        assertEquals(100_000, closed.account(senderHash).getBalance());
    }

    private static final byte[] senderHash = hash(1);

    private static Fixture fixture(Transaction tx, long height, long activation) throws Exception {
        Account sender = new Account(0, senderHash, 1, 100_000, 0, 0, 0);
        Map<String, AccountState> states = new HashMap<>();
        states.put(Hex.encodeHexString(senderHash),
                new AccountState(sender, new HashMap<>(), new HashMap<>()));

        TreeMap<Long, TransPool> nonceMap = new TreeMap<>();
        nonceMap.put(tx.nonce, new TransPool(tx, 0, 0));
        Map<String, TreeMap<Long, TransPool>> pending = new HashMap<>();
        pending.put(Hex.encodeHexString(senderHash), nonceMap);

        PeningTransPool pool = mock(PeningTransPool.class);
        when(pool.getAllMap()).thenReturn(pending);
        when(pool.getAllPubhash()).thenReturn(new ArrayList<>(List.of(senderHash)));

        StateDB stateDB = mock(StateDB.class);
        when(stateDB.getAccounts(any(), any())).thenReturn(states);
        when(stateDB.hasTransaction(any(), any())).thenReturn(false);
        when(stateDB.getAccount(any(), any())).thenReturn(null);

        PackageMiner miner = new PackageMiner();
        ReflectionTestUtils.setField(miner, "peningTransPool", pool);
        ReflectionTestUtils.setField(miner, "stateDB", stateDB);
        ReflectionTestUtils.setField(miner, "paymentExecutionKernel", new PaymentExecutionKernel());
        ReflectionTestUtils.setField(miner, "stateCommitment",
                new StateCommitment(StateCommitment.DISABLED_ACTIVATION_HEIGHT, activation));

        Block block = new Block();
        block.nHeight = height;
        block.body = new ArrayList<>();
        List<Transaction> packed = miner.TransferCheck(new byte[Block.HASH_SIZE], height, block);
        return new Fixture(states, packed);
    }

    private static final class Fixture {
        private final Map<String, AccountState> states;
        private final List<Transaction> packed;

        private Fixture(Map<String, AccountState> states, List<Transaction> packed) {
            this.states = states;
            this.packed = packed;
        }

        private Account account(byte[] hash) {
            return states.get(Hex.encodeHexString(hash)).getAccount();
        }
    }

    private static Transaction batchTx() {
        Transaction tx = Transaction.createEmpty();
        tx.type = Transaction.Type.BATCH_TRANSFER.ordinal();
        tx.from = pub(1);
        tx.to = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
        tx.nonce = 2;
        tx.gasPrice = 1;
        tx.amount = 30;
        tx.payload = BatchTransferPayload.build(List.of(
                new BatchTransferPayload.TransferItem(address(2), 10),
                new BatchTransferPayload.TransferItem(address(3), 20)));
        return tx;
    }

    private static String address(int seed) {
        return KeystoreAction.pubkeyToAddress(pub(seed), (byte) 1);
    }

    private static byte[] pub(int seed) {
        byte[] bytes = new byte[Transaction.PUBLIC_KEY_SIZE];
        Arrays.fill(bytes, (byte) seed);
        return bytes;
    }

    private static byte[] hash(int seed) {
        return RipemdUtility.ripemd160(SHA3Utility.keccak256(pub(seed)));
    }
}
