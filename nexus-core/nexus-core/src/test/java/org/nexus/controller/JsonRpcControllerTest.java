package org.nexus.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.nexus.core.Block;
import org.nexus.core.NexusChainBlockChain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** JSON-RPC 2.0 信封方向回归：ObjectNode 既可能是成功结果，也可能是错误。 */
class JsonRpcControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void successfulBlockObjectIsReturnedInResult() throws Exception {
        JsonRpcController controller = new JsonRpcController();
        NexusChainBlockChain chain = mock(NexusChainBlockChain.class);
        controller.bc = chain;
        Block block = mock(Block.class);
        block.nHeight = 7L;
        block.nTime = 1_700_000_000L;
        when(block.getHashHexString()).thenReturn("0abc");
        when(chain.getCanonicalBlock(7L)).thenReturn(block);

        JsonNode response = mapper.readTree(controller.handleRpc("""
                {"jsonrpc":"2.0","id":11,"method":"nexus_getBlockByHeight","params":[7]}
                """));

        assertFalse(response.has("error"));
        assertTrue(response.has("result"));
        assertEquals(11L, response.path("id").asLong());
        assertEquals(7L, response.path("result").path("height").asLong());
        assertEquals("0abc", response.path("result").path("hash").asText());
    }

    @Test
    void missingBlockRemainsAnErrorEnvelope() throws Exception {
        JsonRpcController controller = new JsonRpcController();
        controller.bc = mock(NexusChainBlockChain.class);

        JsonNode response = mapper.readTree(controller.handleRpc("""
                {"jsonrpc":"2.0","id":12,"method":"nexus_getBlockByHeight","params":[404]}
                """));

        assertFalse(response.has("result"));
        assertEquals(-32001, response.path("error").path("code").asInt());
        assertTrue(response.path("error").path("message").asText().contains("block not found"));
    }

    @Test
    void unknownMethodRemainsAnErrorEnvelope() throws Exception {
        JsonRpcController controller = new JsonRpcController();

        JsonNode response = mapper.readTree(controller.handleRpc("""
                {"jsonrpc":"2.0","id":13,"method":"nexus_noSuchMethod","params":[]}
                """));

        assertFalse(response.has("result"));
        assertEquals(-32601, response.path("error").path("code").asInt());
    }
}
