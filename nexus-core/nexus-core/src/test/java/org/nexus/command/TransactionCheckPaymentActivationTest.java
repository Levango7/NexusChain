package org.nexus.command;

import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.Block;
import org.nexus.core.StateCommitment;
import org.nexus.core.account.Transaction;
import org.nexus.core.payment.BatchTransferPayload;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;
import org.nexus.crypto.ed25519.Ed25519PrivateKey;
import org.nexus.db.StateDB;
import org.nexus.keystore.crypto.RipemdUtility;
import org.nexus.keystore.crypto.SHA3Utility;
import org.nexus.keystore.wallet.KeystoreAction;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionCheckPaymentActivationTest {

    @Test
    void fullySignedBatchPassesOnlyAtActiveHeight() {
        Transaction tx = signedBatch(30);
        TransactionCheck check = checker(100, 99);

        APIResult before = check.TransactionFormatCheck(tx.toRPCBytes(), 99);
        assertEquals(5000, before.getCode());
        assertTrue(isTypeGateFailure(before), before.getMessage());

        APIResult active = check.TransactionFormatCheck(tx.toRPCBytes(), 100);
        assertEquals(2000, active.getCode(), active.getMessage());
        assertEquals(tx.getHashHexString(),
                ((Transaction) active.getData()).getHashHexString());
    }

    @Test
    void signedBatchAmountMismatchIsRejectedAtAdmission() {
        Transaction tx = signedBatch(31);
        TransactionCheck check = checker(100, 99);

        APIResult result = check.TransactionFormatCheck(tx.toRPCBytes(), 100);

        assertEquals(5000, result.getCode());
        assertEquals("Batch amount does not match payload total", result.getMessage());
    }

    private static Transaction signedBatch(long declaredAmount) {
        Ed25519KeyPair sender = Ed25519.generateKeyPair();
        Ed25519KeyPair recipient = Ed25519.generateKeyPair();
        byte[] senderPub = sender.getPublicKey().getEncoded();
        byte[] recipientPub = recipient.getPublicKey().getEncoded();
        byte[] recipientHash = RipemdUtility.ripemd160(SHA3Utility.keccak256(recipientPub));
        String recipientAddress = KeystoreAction.pubkeyToAddress(recipientPub, (byte) 1);
        byte[] payload = BatchTransferPayload.build(List.of(
                new BatchTransferPayload.TransferItem(recipientAddress, 30)));
        Transaction tx = new Transaction(1, Transaction.Type.BATCH_TRANSFER.ordinal(), 8L,
                senderPub, 1L, declaredAmount, payload, recipientHash,
                new byte[Transaction.SIGNATURE_SIZE]);
        tx.signature = new Ed25519PrivateKey(sender.getPrivateKey().getEncoded())
                .sign(tx.getRawForSign());
        return tx;
    }

    @Test
    void exactBlockHeightIsIndependentOfCurrentBestHeight() {
        TransactionCheck check = checker(100, 50);

        assertTrue(isTypeGateFailure(checkAt(check, 99)));
        assertFalse(isTypeGateFailure(checkAt(check, 100)));
        assertFalse(isTypeGateFailure(checkAt(check, 101)));

        APIResult admission = check.TransactionFormatCheck(tx(Transaction.Type.BATCH_TRANSFER).toRPCBytes());
        assertTrue(isTypeGateFailure(admission),
                "best=50 means the next admission height is 51, not a historical height of 100");
    }

    @Test
    void admissionUsesNextHeightAndBatchBoundary() {
        TransactionCheck before = checker(100, 98);
        assertTrue(isTypeGateFailure(before.TransactionFormatCheck(
                tx(Transaction.Type.BATCH_TRANSFER).toRPCBytes())));

        TransactionCheck active = checker(100, 99);
        assertFalse(isTypeGateFailure(active.TransactionFormatCheck(
                tx(Transaction.Type.BATCH_TRANSFER).toRPCBytes())));
    }

    @Test
    void everyOtherPaymentTypeRemainsDisabledAfterActivation() {
        TransactionCheck check = checker(100, 199);
        for (int type = 0x10; type <= 0x1a; type++) {
            if (type == Transaction.Type.BATCH_TRANSFER.ordinal()) {
                continue;
            }
            final int current = type;
            Transaction transaction = new Transaction(
                    1, current, 1L, new byte[Transaction.PUBLIC_KEY_SIZE], 0L, 1L,
                    "payload".getBytes(StandardCharsets.UTF_8),
                    new byte[Transaction.PUBLIC_KEY_HASH_SIZE],
                    new byte[Transaction.SIGNATURE_SIZE]);
            APIResult result = check.TransactionFormatCheck(transaction.toRPCBytes(), 100);
            assertTrue(isTypeGateFailure(result), "type must remain disabled: " + current);
        }
    }

    private static APIResult checkAt(TransactionCheck check, long height) {
        return check.TransactionFormatCheck(
                tx(Transaction.Type.BATCH_TRANSFER).toRPCBytes(), height);
    }

    private static boolean isTypeGateFailure(APIResult result) {
        return result.getMessage() != null
                && result.getMessage().contains("Transaction type not enabled");
    }

    private static TransactionCheck checker(long activationHeight, long bestHeight) {
        TransactionCheck check = new TransactionCheck();
        Configuration configuration = mock(Configuration.class);
        when(configuration.getMin_procedurefee()).thenReturn(0L);
        ReflectionTestUtils.setField(check, "configuration", configuration);
        ReflectionTestUtils.setField(check, "stateCommitment",
                new StateCommitment(StateCommitment.DISABLED_ACTIVATION_HEIGHT, activationHeight));

        Block best = new Block();
        best.nHeight = bestHeight;
        StateDB stateDB = mock(StateDB.class);
        when(stateDB.getBestBlock()).thenReturn(best);
        ReflectionTestUtils.setField(check, "stateDB", stateDB);
        return check;
    }

    private static Transaction tx(Transaction.Type type) {
        return new Transaction(
                1, type.ordinal(), 1L, new byte[Transaction.PUBLIC_KEY_SIZE], 0L, 1L,
                "payload".getBytes(StandardCharsets.UTF_8),
                new byte[Transaction.PUBLIC_KEY_HASH_SIZE],
                new byte[Transaction.SIGNATURE_SIZE]);
    }
}
