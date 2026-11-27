package org.nexus.core.validate;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;
import org.nexus.command.TransactionCheck;
import org.nexus.core.Block;
import org.nexus.core.WhitelistTransaction;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountRuleBatchExecutionTest {

    @Test
    void exactHeightOverloadAndAtomicBatchAccountingAreUsed() {
        Transaction tx = batchTx();
        Account sender = sender(tx);
        Map<String, AccountState> states = new HashMap<>();
        states.put(Hex.encodeHexString(sender.getPubkeyHash()),
                new AccountState(sender, new HashMap<>(), new HashMap<>()));

        StateDB stateDB = mock(StateDB.class);
        when(stateDB.getAccounts(any(), any())).thenReturn(states);
        when(stateDB.getAccount(any(), any())).thenReturn(null);

        TransactionCheck check = mock(TransactionCheck.class);
        when(check.TransactionFormatCheck(any(byte[].class), anyLong()))
                .thenAnswer(ignored -> APIResult.newSuccess(null));
        when(check.TransactionVerify(any(), any(), any()))
                .thenAnswer(ignored -> APIResult.newSuccess(null));

        WhitelistTransaction whitelist = mock(WhitelistTransaction.class);
        when(whitelist.IsUnchecked(anyString())).thenReturn(false);

        AccountRule rule = new AccountRule("exchange");
        ReflectionTestUtils.setField(rule, "stateDB", stateDB);
        ReflectionTestUtils.setField(rule, "transactionCheck", check);
        ReflectionTestUtils.setField(rule, "whitelistTransaction", whitelist);
        ReflectionTestUtils.setField(rule, "peningTransPool", mock(PeningTransPool.class));
        ReflectionTestUtils.setField(rule, "paymentExecutionKernel", new PaymentExecutionKernel());

        Result result = rule.validateBlock(block(tx, 100));

        assertTrue(result.isSuccess(), result.getMessage());
        verify(check).TransactionFormatCheck(any(byte[].class), eq(100L));
        assertEquals(49_970, account(states, sender.getPubkeyHash()).getBalance());
        assertEquals(10, account(states, hash(2)).getBalance());
        assertEquals(20, account(states, hash(3)).getBalance());
        assertEquals(2, account(states, sender.getPubkeyHash()).getNonce());
    }

    private static Account account(Map<String, AccountState> states, byte[] hash) {
        return states.get(Hex.encodeHexString(hash)).getAccount();
    }

    private static Block block(Transaction tx, long height) {
        Block block = new Block();
        block.nHeight = height;
        block.hashPrevBlock = new byte[Block.HASH_SIZE];
        block.body = new ArrayList<>(List.of(tx));
        return block;
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
