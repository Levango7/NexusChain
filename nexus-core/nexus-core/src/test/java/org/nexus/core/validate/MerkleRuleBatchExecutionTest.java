package org.nexus.core.validate;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.nexus.command.IncubatorAddress;
import org.nexus.core.Block;
import org.nexus.core.PaymentExecutionResult;
import org.nexus.core.PaymentStateEntry;
import org.nexus.core.StateCommitment;
import org.nexus.core.account.Account;
import org.nexus.core.account.AccountDB;
import org.nexus.core.account.Transaction;
import org.nexus.core.incubator.IncubatorDB;
import org.nexus.core.payment.BatchTransferPayload;
import org.nexus.keystore.crypto.RipemdUtility;
import org.nexus.keystore.crypto.SHA3Utility;
import org.nexus.keystore.wallet.KeystoreAction;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MerkleRuleBatchExecutionTest {

    @Test
    void batchExecutionProducesAtomicAccountsPaymentRootAndFee() throws Exception {
        Transaction tx = batchTx();
        Account sender = sender(tx);
        MerkleRule rule = rule(100, 100, sender);

        Map<String, Object> replay = rule.validateMerkle(List.of(tx), 100);
        @SuppressWarnings("unchecked")
        List<Account> accounts = (List<Account>) replay.get("account");
        @SuppressWarnings("unchecked")
        List<PaymentStateEntry> payments = (List<PaymentStateEntry>) replay.get("payment");
        long executedFee = (long) replay.get("executedFee");

        assertEquals(49_970, find(accounts, sender.getPubkeyHash()).getBalance());
        assertEquals(10, find(accounts, hash(2)).getBalance());
        assertEquals(20, find(accounts, hash(3)).getBalance());
        assertEquals(1, payments.size());
        assertEquals(50_000, executedFee);

        Block block = new Block();
        block.nHeight = 100;
        block.hashMerkleState = rule.calculateStateRoot(accounts, payments, 100);
        block.hashMerkleIncubate = Block.calculateMerkleIncubate(List.of());
        assertTrue(rule.verifyStateRoots(block, accounts, List.of(), payments).isSuccess());

        find(accounts, hash(2)).setBalance(999);
        Result tampered = rule.verifyStateRoots(block, accounts, List.of(), payments);
        assertFalse(tampered.isSuccess());
    }

    @Test
    void batchExecutionIsRejectedBeforePaymentActivation() {
        Transaction tx = batchTx();
        MerkleRule rule = rule(100, 200, sender(tx));

        assertThrows(IllegalArgumentException.class, () -> rule.validateMerkle(List.of(tx), 100));
    }

    private static MerkleRule rule(long stateHeight, long paymentHeight, Account sender) {
        AccountDB accountDB = mock(AccountDB.class);
        when(accountDB.selectaccount(any())).thenAnswer(invocation -> {
            byte[] hash = invocation.getArgument(0);
            if (Arrays.equals(hash, IncubatorAddress.resultpubhash())) {
                return new Account(0, IncubatorAddress.resultpubhash(), 0, 0, 0, 0, 0);
            }
            if (Arrays.equals(hash, sender.getPubkeyHash())) {
                return sender;
            }
            return null;
        });
        MerkleRule rule = new MerkleRule("validator", new StateCommitment(stateHeight, paymentHeight));
        ReflectionTestUtils.setField(rule, "accountDB", accountDB);
        ReflectionTestUtils.setField(rule, "incubatorDB", mock(IncubatorDB.class));
        return rule;
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

    private static Account sender(Transaction tx) {
        return new Account(0, RipemdUtility.ripemd160(SHA3Utility.keccak256(tx.from)),
                1, 100_000, 0, 0, 0);
    }

    private static Account find(List<Account> accounts, byte[] hash) {
        return accounts.stream().filter(account -> Arrays.equals(hash, account.getPubkeyHash()))
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
