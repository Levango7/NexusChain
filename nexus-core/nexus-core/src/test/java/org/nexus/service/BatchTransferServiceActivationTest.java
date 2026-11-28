package org.nexus.service;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;
import org.nexus.command.Configuration;
import org.nexus.command.TransactionCheck;
import org.nexus.controller.BatchTransferController.BatchTransferRequest;
import org.nexus.controller.BatchTransferController;
import org.nexus.core.Block;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.core.StateCommitment;
import org.nexus.core.account.Account;
import org.nexus.core.account.AccountDB;
import org.nexus.core.account.Transaction;
import org.nexus.core.payment.BatchTransferPayload.TransferItem;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;
import org.nexus.crypto.ed25519.Ed25519PrivateKey;
import org.nexus.db.StateDB;
import org.nexus.keystore.crypto.RipemdUtility;
import org.nexus.keystore.crypto.SHA3Utility;
import org.nexus.keystore.wallet.KeystoreAction;
import org.nexus.pool.AdoptTransPool;
import org.nexus.service.Impl.CommandServiceImpl;
import org.mockito.ArgumentCaptor;
import org.nexus.core.payment.BatchTransferPayload;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchTransferServiceActivationTest {

    @Test
    void serverSignedSubmissionPassesRealConsensusValidation() {
        Fixture fixture = new Fixture();
        Ed25519KeyPair sender = Ed25519.generateKeyPair();
        List<TransferItem> items = items();

        APIResult result = fixture.service.batchTransfer(
                hex(sender.getPublicKey().getEncoded()), items,
                hex(sender.getPrivateKey().getEncoded()), 0);

        assertEquals(APIResult.SUCCESS, result.getCode(), result.getMessage());
        ArgumentCaptor<byte[]> raw = ArgumentCaptor.forClass(byte[].class);
        verify(fixture.commandService).verifyTransfer(raw.capture());
        TransactionCheck check = checker(100);
        APIResult validated = check.TransactionFormatCheck(raw.getValue(), 100);
        assertEquals(APIResult.SUCCESS, validated.getCode(), validated.getMessage());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(30L, data.get("totalAmount"));
        assertEquals(1L, data.get("nonce"));
        assertEquals(50_000L, data.get("fee"));
        assertNotNull(data.get("txHash"));
    }

    @Test
    void sdkSignedSubmissionAndConfirmedStateUseTheSameCanonicalTransaction() {
        Fixture fixture = new Fixture();
        Ed25519KeyPair sender = Ed25519.generateKeyPair();
        List<TransferItem> items = items();
        Transaction tx = signedTransaction(sender, items);
        String raw = Hex.encodeHexString(tx.toRPCBytes());

        APIResult submitted = fixture.service.submitSignedBatch(
                hex(sender.getPublicKey().getEncoded()), items, raw, 0);
        assertEquals(APIResult.SUCCESS, submitted.getCode(), submitted.getMessage());
        verify(fixture.commandService).verifyTransfer(any(byte[].class));

        tx.height = 100;
        when(fixture.chain.getTransaction(tx.getHash())).thenReturn(tx);
        APIResult status = fixture.service.getBatchStatus(tx.getHashHexString());
        assertEquals(APIResult.SUCCESS, status.getCode(), status.getMessage());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) status.getData();
        assertEquals("CONFIRMED", data.get("status"));
        assertEquals(100L, data.get("height"));
        assertEquals(30L, data.get("totalAmount"));
        assertEquals(50_000L, data.get("fee"));
        assertEquals(2, ((List<?>) data.get("items")).size());
    }

    @Test
    void realCommandServiceAcceptsTheServerSignedBatchIntoItsAdmissionPool() {
        Ed25519KeyPair sender = Ed25519.generateKeyPair();
        List<TransferItem> items = items();
        StateDB stateDB = mock(StateDB.class);
        Block best = new Block();
        best.nHeight = 99;
        when(stateDB.getBestBlock()).thenReturn(best);

        TransactionCheck check = checker(100);
        ReflectionTestUtils.setField(check, "stateDB", stateDB);
        AccountDB accountDB = mock(AccountDB.class);
        when(accountDB.selectaccount(any())).thenReturn(new Account(
                0, org.nexus.keystore.crypto.RipemdUtility.ripemd160(
                        org.nexus.keystore.crypto.SHA3Utility.keccak256(sender.getPublicKey().getEncoded())),
                0, 100_000, 0, 0, 0));
        AdoptTransPool adoptTransPool = mock(AdoptTransPool.class);
        Configuration configuration = new Configuration();
        configuration.setMaxqueued(10);

        CommandServiceImpl commandService = new CommandServiceImpl();
        ReflectionTestUtils.setField(commandService, "transactionCheck", check);
        ReflectionTestUtils.setField(commandService, "accountDB", accountDB);
        ReflectionTestUtils.setField(commandService, "stateDB", stateDB);
        ReflectionTestUtils.setField(commandService, "adoptTransPool", adoptTransPool);
        ReflectionTestUtils.setField(commandService, "configuration", configuration);

        BatchTransferService service = new BatchTransferService();
        ReflectionTestUtils.setField(service, "stateCommitment",
                new StateCommitment(StateCommitment.DISABLED_ACTIVATION_HEIGHT, 100));
        ReflectionTestUtils.setField(service, "stateDB", stateDB);
        ReflectionTestUtils.setField(service, "commandService", commandService);

        APIResult result = service.batchTransfer(
                hex(sender.getPublicKey().getEncoded()), items,
                hex(sender.getPrivateKey().getEncoded()), 0);

        assertEquals(APIResult.SUCCESS, result.getCode(), result.getMessage());
        ArgumentCaptor<List<Transaction>> accepted = ArgumentCaptor.forClass(List.class);
        verify(adoptTransPool).add(accepted.capture());
        assertEquals(1, accepted.getValue().size());
        assertEquals(Transaction.Type.BATCH_TRANSFER.ordinal(), accepted.getValue().get(0).type);
        assertEquals(30L, accepted.getValue().get(0).amount);
    }

    @Test
    void v2ControllerUsesSignedPayloadWithoutCallingThePrivateKeyFlow() {
        BatchTransferService service = mock(BatchTransferService.class);
        when(service.submitSignedBatch(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(APIResult.newSuccess(Map.of()));
        BatchTransferController controller = new BatchTransferController();
        ReflectionTestUtils.setField(controller, "batchTransferService", service);
        BatchTransferRequest request = new BatchTransferRequest();
        request.setFromPubkey("aa");
        request.setItems(items());
        request.setTraninfo("bb");
        request.setNonce(0);

        Object result = controller.batchTransferV2(request);

        assertTrue(result instanceof APIResult);
        verify(service).submitSignedBatch("aa", request.getItems(), "bb", 0);
        verify(service, never()).batchTransfer(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    private static TransactionCheck checker(long activation) {
        TransactionCheck check = new TransactionCheck();
        Configuration configuration = new Configuration();
        configuration.setMin_procedurefee(0);
        ReflectionTestUtils.setField(check, "configuration", configuration);
        ReflectionTestUtils.setField(check, "stateCommitment",
                new StateCommitment(StateCommitment.DISABLED_ACTIVATION_HEIGHT, activation));
        return check;
    }

    private static final class Fixture {
        private final BatchTransferService service = new BatchTransferService();
        private final CommandService commandService = mock(CommandService.class);
        private final NexusChainBlockChain chain = mock(NexusChainBlockChain.class);

        private Fixture() {
            StateDB stateDB = mock(StateDB.class);
            Block best = new Block();
            best.nHeight = 99;
            when(stateDB.getBestBlock()).thenReturn(best);
            when(commandService.verifyTransfer(any(byte[].class)))
                    .thenAnswer(ignored -> APIResult.newSuccess(null));
            ReflectionTestUtils.setField(service, "stateCommitment",
                    new StateCommitment(StateCommitment.DISABLED_ACTIVATION_HEIGHT, 100));
            ReflectionTestUtils.setField(service, "stateDB", stateDB);
            ReflectionTestUtils.setField(service, "commandService", commandService);
            ReflectionTestUtils.setField(service, "nexusBlockChain", chain);
        }
    }

    private static Transaction signedTransaction(Ed25519KeyPair sender, List<TransferItem> items) {
        Transaction tx = Transaction.createEmpty();
        tx.type = Transaction.Type.BATCH_TRANSFER.ordinal();
        tx.nonce = 1;
        tx.from = sender.getPublicKey().getEncoded();
        tx.gasPrice = 1;
        tx.amount = BatchTransferPayload.getTotalAmount(items);
        tx.payload = BatchTransferPayload.build(items);
        tx.to = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
        tx.signature = new Ed25519PrivateKey(sender.getPrivateKey().getEncoded())
                .sign(tx.getRawForSign());
        return tx;
    }

    private static List<TransferItem> items() {
        return List.of(new TransferItem(address(2), 10), new TransferItem(address(3), 20));
    }

    private static String address(int seed) {
        byte[] pub = new byte[Transaction.PUBLIC_KEY_SIZE];
        java.util.Arrays.fill(pub, (byte) seed);
        return KeystoreAction.pubkeyToAddress(pub, (byte) 1);
    }

    private static String hex(byte[] bytes) {
        return Hex.encodeHexString(bytes);
    }
}
