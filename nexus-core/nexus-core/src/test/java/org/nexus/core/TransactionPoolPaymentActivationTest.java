package org.nexus.core;

import org.junit.jupiter.api.Test;
import org.nexus.core.account.Transaction;
import org.nexus.db.StateDB;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionPoolPaymentActivationTest {

    private static final byte[] FROM = new byte[Transaction.PUBLIC_KEY_SIZE];
    private static final byte[] TO = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
    private static final byte[] SIGNATURE = new byte[Transaction.SIGNATURE_SIZE];

    static {
        FROM[0] = 1;
        SIGNATURE[0] = 1;
    }

    @Test
    void batchIsRejectedBeforeAndAcceptedAtNextBlockActivation() {
        TransactionPool before = pool(100, 98);
        assertFalse(before.add(tx(Transaction.Type.BATCH_TRANSFER)));
        assertEquals(0, before.size());

        TransactionPool active = pool(100, 99);
        assertTrue(active.add(tx(Transaction.Type.BATCH_TRANSFER)));
        assertEquals(1, active.size());
    }

    @Test
    void otherPaymentTypesRemainClosedAfterActivation() {
        TransactionPool active = pool(100, 99);
        for (Transaction.Type type : Transaction.Type.values()) {
            if (type.ordinal() < 0x10 || type.ordinal() > 0x1a
                    || type == Transaction.Type.BATCH_TRANSFER) {
                continue;
            }
            assertFalse(active.add(tx(type)), type + " must remain closed");
        }
        assertEquals(0, active.size());
    }

    private static TransactionPool pool(long activationHeight, long bestHeight) {
        TransactionPool pool = new TransactionPool();
        ReflectionTestUtils.setField(pool, "stateCommitment",
                new StateCommitment(StateCommitment.DISABLED_ACTIVATION_HEIGHT, activationHeight));
        Block best = new Block();
        best.nHeight = bestHeight;
        StateDB stateDB = mock(StateDB.class);
        when(stateDB.getBestBlock()).thenReturn(best);
        ReflectionTestUtils.setField(pool, "stateDB", stateDB);
        return pool;
    }

    private static Transaction tx(Transaction.Type type) {
        return new Transaction(1, type.ordinal(), 1L, FROM, 0L, 1L, null, TO, SIGNATURE);
    }
}
