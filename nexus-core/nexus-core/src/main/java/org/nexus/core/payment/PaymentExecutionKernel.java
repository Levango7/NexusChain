package org.nexus.core.payment;

import org.apache.commons.codec.binary.Hex;
import org.nexus.command.TransactionCheck;
import org.nexus.core.PaymentExecutionResult;
import org.nexus.core.PaymentStateEntry;
import org.nexus.core.account.Account;
import org.nexus.core.account.Transaction;
import org.nexus.keystore.crypto.RipemdUtility;
import org.nexus.keystore.crypto.SHA3Utility;
import org.nexus.keystore.wallet.KeystoreAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Deterministic, atomic payment execution used by consensus replay (ADR-038). */
@Component
public class PaymentExecutionKernel {

    private static final int MAX_RECIPIENTS = 100;
    private static final long MAX_TOTAL_AMOUNT = 100_000_000_000L;

    public PaymentExecutionResult executeBatchTransfer(
            Transaction tx, Account sender, Function<byte[], Account> accountLookup, long blockHeight) {
        if (tx == null || sender == null || accountLookup == null) {
            throw new IllegalArgumentException("batch transaction, sender, and account lookup are required");
        }
        if (tx.type != Transaction.Type.BATCH_TRANSFER.ordinal()) {
            throw new IllegalArgumentException("transaction is not BATCH_TRANSFER");
        }
        if (tx.nonce <= sender.getNonce() || tx.nonce > TransactionCheck.MAX_TX_NONCE) {
            throw new IllegalArgumentException("batch transaction nonce is out of range");
        }

        List<BatchTransferPayload.TransferItem> items = BatchTransferPayload.parse(tx.payload);
        BatchTransferPayload.validate(items, MAX_RECIPIENTS, MAX_TOTAL_AMOUNT);
        long total = BatchTransferPayload.getTotalAmount(items);
        if (tx.amount != total) {
            throw new IllegalArgumentException("batch amount does not match payload total");
        }
        long fee = tx.getFeeExact();
        long required = Math.addExact(total, fee);
        Account changedSender = sender.copy();
        if (changedSender.getBalance() < required) {
            throw new IllegalArgumentException("insufficient balance for batch transfer and fee");
        }

        byte[] senderHash = changedSender.getPubkeyHash();
        Map<String, Account> changed = new HashMap<>();
        changed.put(Hex.encodeHexString(senderHash), changedSender);
        changedSender.setBalance(Math.subtractExact(changedSender.getBalance(), required));
        changedSender.setNonce(tx.nonce);
        changedSender.setBlockHeight(blockHeight);

        for (BatchTransferPayload.TransferItem item : items) {
            if (KeystoreAction.verifyAddress(item.getAddress()) != 0) {
                throw new IllegalArgumentException("invalid batch recipient address");
            }
            byte[] recipientHash = KeystoreAction.addressToPubkeyHash(item.getAddress());
            String key = Hex.encodeHexString(recipientHash);
            Account recipient = changed.computeIfAbsent(key, ignored -> {
                Account current = accountLookup.apply(recipientHash);
                return current == null
                        ? new Account(blockHeight, recipientHash, 0, 0, 0, 0, 0)
                        : current.copy();
            });
            recipient.setBalance(Math.addExact(recipient.getBalance(), item.getAmount()));
            recipient.setBlockHeight(blockHeight);
        }

        PaymentStateEntry state = new PaymentStateEntry(
                PaymentStateEntry.Kind.BATCH, tx.getHashHexString(), BatchTransferPayload.build(items));
        return new PaymentExecutionResult(
                new ArrayList<>(changed.values()), List.of(state), fee);
    }
}
