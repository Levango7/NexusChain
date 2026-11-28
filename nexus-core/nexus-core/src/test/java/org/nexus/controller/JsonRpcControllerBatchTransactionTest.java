package org.nexus.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.nexus.core.Block;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.core.account.Transaction;
import org.nexus.core.payment.BatchTransferPayload;
import org.nexus.keystore.wallet.KeystoreAction;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JsonRpcControllerBatchTransactionTest {

    @Test
    void batchFieldsAreAdditiveAndLegacyShapeIsUnchanged() throws Exception {
        JsonRpcController controller = new JsonRpcController();
        NexusChainBlockChain chain = mock(NexusChainBlockChain.class);
        Block confirmed = new Block();
        confirmed.nTime = 123;
        when(chain.getCanonicalBlock(anyLong())).thenReturn(confirmed);
        ReflectionTestUtils.setField(controller, "bc", chain);
        Method serializer = JsonRpcController.class.getDeclaredMethod("toRpcTransaction", Transaction.class);
        serializer.setAccessible(true);

        Transaction batch = transaction(Transaction.Type.BATCH_TRANSFER);
        batch.amount = 30;
        batch.nonce = 2;
        batch.gasPrice = 1;
        batch.payload = BatchTransferPayload.build(List.of(
                new BatchTransferPayload.TransferItem(address(2), 30)));
        ObjectNode batchJson = (ObjectNode) serializer.invoke(controller, batch);

        assertEquals(19, batchJson.get("type").asInt());
        assertEquals("BATCH_TRANSFER", batchJson.get("typeName").asText());
        assertEquals("50000", batchJson.get("fee").asText());
        assertEquals("2", batchJson.get("nonce").asText());
        assertEquals("30", batchJson.get("amount").asText());

        Transaction legacy = transaction(Transaction.Type.TRANSFER);
        legacy.amount = 5;
        ObjectNode legacyJson = (ObjectNode) serializer.invoke(controller, legacy);
        assertFalse(legacyJson.has("type"));
        assertFalse(legacyJson.has("typeName"));
        assertFalse(legacyJson.has("fee"));
        assertFalse(legacyJson.has("nonce"));
    }

    private static Transaction transaction(Transaction.Type type) {
        Transaction tx = Transaction.createEmpty();
        tx.type = type.ordinal();
        tx.height = 100;
        tx.from = new byte[Transaction.PUBLIC_KEY_SIZE];
        tx.to = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
        return tx;
    }

    private static String address(int seed) {
        byte[] pub = new byte[Transaction.PUBLIC_KEY_SIZE];
        java.util.Arrays.fill(pub, (byte) seed);
        return KeystoreAction.pubkeyToAddress(pub, (byte) 1);
    }
}
