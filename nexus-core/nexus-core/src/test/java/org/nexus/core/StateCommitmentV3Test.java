package org.nexus.core;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.nexus.core.account.Account;
import org.nexus.keystore.crypto.SHA3Utility;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StateCommitmentV3Test {

    @Test
    void v3HasIndependentActivationBoundaries() {
        StateCommitment commitment = new StateCommitment(100, 200);
        List<Account> accounts = accounts();
        List<PaymentStateEntry> payments = payments();

        assertArrayEquals(Block.calculateMerkleState(accounts), commitment.calculateMerkleState(accounts, payments, 99));
        assertArrayEquals(StateCommitment.calculateV2(accounts), commitment.calculateMerkleState(accounts, payments, 100));
        assertArrayEquals(StateCommitment.calculateV3(accounts, payments), commitment.calculateMerkleState(accounts, payments, 200));
        assertFalse(commitment.isV3Active(199));
        assertTrue(commitment.isV3Active(200));
    }

    @Test
    void v3IsDeterministicAcrossAccountAndPaymentOrder() {
        List<Account> accounts = accounts();
        List<PaymentStateEntry> payments = payments();

        assertArrayEquals(StateCommitment.calculateV3(accounts, payments),
                StateCommitment.calculateV3(
                        List.of(accounts.get(1), accounts.get(0)),
                        List.of(payments.get(1), payments.get(0))));
    }

    @Test
    void v3ChangesWhenPaymentStateChanges() {
        PaymentStateEntry changed = new PaymentStateEntry(
                PaymentStateEntry.Kind.BATCH, "batch-1", "changed".getBytes(StandardCharsets.UTF_8));

        assertNotEquals(Hex.encodeHexString(StateCommitment.calculateV3(accounts(), payments())),
                Hex.encodeHexString(StateCommitment.calculateV3(accounts(), List.of(changed))));
    }

    @Test
    void paymentEntryEncodingIsVersionedAndDuplicateIdsAreRejected() {
        PaymentStateEntry entry = new PaymentStateEntry(
                PaymentStateEntry.Kind.BATCH, "b", new byte[]{1, 2});

        assertEquals("0301000162000000020102", Hex.encodeHexString(entry.encode()));
        assertThrows(IllegalArgumentException.class, () -> StateCommitment.calculateV3(
                accounts(), List.of(entry, entry)));
        assertArrayEquals(SHA3Utility.keccak256(new byte[]{StateCommitment.PAYMENT_VERSION}),
                StateCommitment.calculatePaymentRoot(List.of()));
    }

    private static List<Account> accounts() {
        return List.of(
                new Account(1, new byte[20], 1, 10, 0, 0, 0),
                new Account(1, new byte[]{1}, 2, 20, 0, 0, 0));
    }

    private static List<PaymentStateEntry> payments() {
        return List.of(
                new PaymentStateEntry(PaymentStateEntry.Kind.BATCH, "batch-1", new byte[]{1}),
                new PaymentStateEntry(PaymentStateEntry.Kind.CHANNEL, "channel-1", new byte[]{2}));
    }
}
