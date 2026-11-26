package org.nexus.core.payment;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.nexus.command.TransactionCheck;
import org.nexus.core.PaymentExecutionResult;
import org.nexus.core.PaymentStateEntry;
import org.nexus.core.account.Account;
import org.nexus.core.account.Transaction;
import org.nexus.keystore.crypto.RipemdUtility;
import org.nexus.keystore.crypto.SHA3Utility;
import org.nexus.keystore.wallet.KeystoreAction;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PaymentExecutionKernelTest {

    private final PaymentExecutionKernel kernel = new PaymentExecutionKernel();

    @Test
    void batchTransferDebitsAndCreditsExactlyOnce() {
        Transaction tx = batchTx(List.of(
                new BatchTransferPayload.TransferItem(address(2), 10),
                new BatchTransferPayload.TransferItem(address(3), 20)), 30, 2);
        Account sender = sender(tx, 1, 100_000);

        PaymentExecutionResult result = kernel.executeBatchTransfer(tx, sender, lookup(), 100);
        Account changedSender = find(result, sender.getPubkeyHash());
        Account recipient2 = find(result, hash(2));
        Account recipient3 = find(result, hash(3));

        assertEquals(49_970, changedSender.getBalance());
        assertEquals(2, changedSender.getNonce());
        assertEquals(100, changedSender.getBlockHeight());
        assertEquals(10, recipient2.getBalance());
        assertEquals(20, recipient3.getBalance());
        assertEquals(50_000, result.getExecutedFee());
        assertEquals(1, result.getPaymentStates().size());
        assertEquals(PaymentStateEntry.Kind.BATCH, result.getPaymentStates().get(0).getKind());
        assertEquals(100_000, sender.getBalance(), "input sender must not be mutated");
    }

    @Test
    void selfTransferOnlyPaysFeeAndReturnsOneAccount() {
        Transaction tx = batchTx(List.of(
                new BatchTransferPayload.TransferItem(KeystoreAction.pubkeyToAddress(pub(1), (byte) 1), 30)), 30, 2);
        Account sender = sender(tx, 1, 100_000);

        PaymentExecutionResult result = kernel.executeBatchTransfer(tx, sender, lookup(), 100);

        assertEquals(1, result.getAccounts().size());
        assertEquals(50_000, find(result, sender.getPubkeyHash()).getBalance());
        assertEquals(2, find(result, sender.getPubkeyHash()).getNonce());
    }

    @Test
    void duplicateRecipientsAreMergedWithExactArithmetic() {
        Transaction tx = batchTx(List.of(
                new BatchTransferPayload.TransferItem(address(2), 10),
                new BatchTransferPayload.TransferItem(address(2), 20)), 30, 2);
        Account sender = sender(tx, 1, 100_000);

        PaymentExecutionResult result = kernel.executeBatchTransfer(tx, sender, lookup(), 100);

        assertEquals(2, result.getAccounts().size());
        assertEquals(30, find(result, hash(2)).getBalance());
        assertEquals(49_970, find(result, sender.getPubkeyHash()).getBalance());
    }

    @Test
    void insufficientFundsAndAmountMismatchAreAtomicFailures() {
        Transaction insufficient = batchTx(List.of(new BatchTransferPayload.TransferItem(address(2), 30)), 30, 2);
        Account poor = sender(insufficient, 1, 10);
        assertThrows(IllegalArgumentException.class,
                () -> kernel.executeBatchTransfer(insufficient, poor, lookup(), 100));
        assertEquals(10, poor.getBalance());
        assertEquals(1, poor.getNonce());

        Transaction mismatch = batchTx(List.of(new BatchTransferPayload.TransferItem(address(2), 30)), 31, 2);
        Account sender = sender(mismatch, 1, 100_000);
        assertThrows(IllegalArgumentException.class,
                () -> kernel.executeBatchTransfer(mismatch, sender, lookup(), 100));
        assertEquals(100_000, sender.getBalance());
        assertEquals(1, sender.getNonce());
    }

    @Test
    void overflowInDebitOrRecipientCreditIsRejectedWithoutMutation() {
        Transaction debitOverflow = batchTx(
                List.of(new BatchTransferPayload.TransferItem(address(2), 1_000_000)), 1_000_000, 2);
        debitOverflow.gasPrice = Long.MAX_VALUE / 50_000;
        Account rich = sender(debitOverflow, 1, Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,
                () -> kernel.executeBatchTransfer(debitOverflow, rich, lookup(), 100));
        assertEquals(Long.MAX_VALUE, rich.getBalance());
        assertEquals(1, rich.getNonce());

        Transaction creditOverflow = batchTx(List.of(new BatchTransferPayload.TransferItem(address(2), 1)), 1, 2);
        Account sender = sender(creditOverflow, 1, 100_000);
        Map<String, Account> db = new HashMap<>();
        db.put(Hex.encodeHexString(hash(2)), new Account(0, hash(2), 0, Long.MAX_VALUE, 0, 0, 0));
        assertThrows(ArithmeticException.class,
                () -> kernel.executeBatchTransfer(creditOverflow, sender, lookup(db), 100));
        assertEquals(100_000, sender.getBalance());
        assertEquals(1, sender.getNonce());
    }

    @Test
    void nonceBoundsAreEnforced() {
        Transaction replay = batchTx(List.of(new BatchTransferPayload.TransferItem(address(2), 1)), 1, 1);
        Account sender = sender(replay, 1, 100_000);
        assertThrows(IllegalArgumentException.class,
                () -> kernel.executeBatchTransfer(replay, sender, lookup(), 100));

        Transaction excessive = batchTx(List.of(new BatchTransferPayload.TransferItem(address(2), 1)), 1,
                TransactionCheck.MAX_TX_NONCE + 1);
        Account fresh = sender(excessive, 1, 100_000);
        assertThrows(IllegalArgumentException.class,
                () -> kernel.executeBatchTransfer(excessive, fresh, lookup(), 100));
    }

    @Test
    void generatedAddressesUseDecodedVersionAndChecksum() {
        assertEquals(0, KeystoreAction.verifyAddress(address(2)));
        assertEquals(0, KeystoreAction.verifyAddress(address(3)));
        assertNotEquals(0, KeystoreAction.verifyAddress(address(2).substring(1)));
    }

    @Test
    void feeCalculationOverflowIsExplicit() {
        Transaction tx = batchTx(List.of(new BatchTransferPayload.TransferItem(address(2), 1)), 1, 2);
        tx.gasPrice = Long.MAX_VALUE;
        assertThrows(ArithmeticException.class, tx::getFeeExact);
    }

    private static Transaction batchTx(List<BatchTransferPayload.TransferItem> items, long amount, long nonce) {
        Transaction tx = Transaction.createEmpty();
        tx.type = Transaction.Type.BATCH_TRANSFER.ordinal();
        tx.from = pub(1);
        tx.to = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
        tx.nonce = nonce;
        tx.gasPrice = 1;
        tx.amount = amount;
        tx.payload = BatchTransferPayload.build(items);
        return tx;
    }

    private static Account sender(Transaction tx, long nonce, long balance) {
        return new Account(0, RipemdUtility.ripemd160(SHA3Utility.keccak256(tx.from)),
                nonce, balance, 0, 0, 0);
    }

    private static Function<byte[], Account> lookup() {
        return lookup(new HashMap<>());
    }

    private static Function<byte[], Account> lookup(Map<String, Account> db) {
        return hash -> db.get(Hex.encodeHexString(hash));
    }

    private static Account find(PaymentExecutionResult result, byte[] hash) {
        return result.getAccounts().stream()
                .filter(account -> Arrays.equals(hash, account.getPubkeyHash()))
                .findFirst().orElseThrow();
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
