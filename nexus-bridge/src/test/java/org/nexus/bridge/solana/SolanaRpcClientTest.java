package org.nexus.bridge.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SolanaRpcClient} 单元测试：Mock HttpClient 响应，覆盖 6 个 RPC 方法。
 */
@ExtendWith(MockitoExtension.class)
class SolanaRpcClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    private SolanaRpcClient rpcClient;

    @BeforeEach
    void setUp() throws Exception {
        rpcClient = new SolanaRpcClient("https://api.devnet.solana.com", httpClient);
        lenient().when(httpResponse.statusCode()).thenReturn(200);
    }

    /**
     * 模拟 HTTP 响应返回指定 JSON body。
     *
     * @param resultJson result 字段的 JSON 字符串
     */
    @SuppressWarnings("unchecked")
    private void mockResponse(String resultJson) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + resultJson + "}";
        when(httpResponse.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
    }

    /**
     * 模拟 HTTP 响应返回 RPC error。
     *
     * @param code    错误码
     * @param message 错误信息
     */
    @SuppressWarnings("unchecked")
    private void mockErrorResponse(int code, String message) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":" + code
                + ",\"message\":\"" + message + "\"}}";
        when(httpResponse.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
    }

    // ==================== getBalance 测试 ====================

    @Test
    @DisplayName("getBalance: 成功时返回 lamports 余额")
    void getBalance_success() throws Exception {
        mockResponse("{\"context\":{\"slot\":12345},\"value\":1000000000}");
        long balance = rpcClient.getBalance("11111111111111111111111111111111");
        assertEquals(1_000_000_000L, balance);
    }

    @Test
    @DisplayName("getBalance: null 公钥返回 -1")
    void getBalance_nullPubkey() {
        assertEquals(-1L, rpcClient.getBalance(null));
    }

    @Test
    @DisplayName("getBalance: 空公钥返回 -1")
    void getBalance_emptyPubkey() {
        assertEquals(-1L, rpcClient.getBalance(""));
    }

    @Test
    @DisplayName("getBalance: 含非法字符返回 -1")
    void getBalance_invalidChar() {
        assertEquals(-1L, rpcClient.getBalance("0OIl"));
    }

    @Test
    @DisplayName("getBalance: RPC error 时返回 -1")
    void getBalance_rpcError() throws Exception {
        mockErrorResponse(-32000, "Account not found");
        assertEquals(-1L, rpcClient.getBalance("11111111111111111111111111111111"));
    }

    // ==================== getLatestBlockhash 测试 ====================

    @Test
    @DisplayName("getLatestBlockhash: 成功时返回 blockhash 与 lastValidBlockHeight")
    void getLatestBlockhash_success() throws Exception {
        String blockhash = "EwrtB6mR5IuJZ8ZyQXqXqXqXqXqXqXqXqXqXqXqXqXq";
        mockResponse("{\"context\":{\"slot\":12345},\"value\":{"
                + "\"blockhash\":\"" + blockhash + "\","
                + "\"lastValidBlockHeight\":12350}}");
        SolanaRpcClient.Blockhash bh = rpcClient.getLatestBlockhash();
        assertNotNull(bh);
        assertEquals(blockhash, bh.blockhash);
        assertEquals(12350L, bh.lastValidBlockHeight);
    }

    @Test
    @DisplayName("getLatestBlockhash: HTTP 错误时返回 null")
    void getLatestBlockhash_httpError() throws Exception {
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("Internal Server Error");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
        assertNull(rpcClient.getLatestBlockhash());
    }

    // ==================== sendTransaction 测试 ====================

    @Test
    @DisplayName("sendTransaction: 成功时返回交易签名")
    void sendTransaction_success() throws Exception {
        String signature = "5xQF4tW2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y";
        mockResponse("\"" + signature + "\"");
        String result = rpcClient.sendTransaction("dGVzdA==");
        assertEquals(signature, result);
    }

    @Test
    @DisplayName("sendTransaction: null 入参抛 IllegalArgumentException")
    void sendTransaction_nullThrows() {
        assertThrows(IllegalArgumentException.class, () -> rpcClient.sendTransaction(null));
    }

    @Test
    @DisplayName("sendTransaction: 空入参抛 IllegalArgumentException")
    void sendTransaction_emptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> rpcClient.sendTransaction(""));
    }

    @Test
    @DisplayName("sendTransaction: RPC error 时抛 SolanaRpcException")
    void sendTransaction_rpcError() throws Exception {
        mockErrorResponse(-32003, "Transaction signature verification failure");
        SolanaRpcException ex = assertThrows(SolanaRpcException.class,
                () -> rpcClient.sendTransaction("dGVzdA=="));
        assertTrue(ex.getErrorCode().startsWith("RPC_ERROR"));
    }

    // ==================== simulateTransaction 测试 ====================

    @Test
    @DisplayName("simulateTransaction: 成功时返回 err=null 的结果")
    void simulateTransaction_success() throws Exception {
        mockResponse("{\"context\":{\"slot\":12345},\"value\":{"
                + "\"err\":null,"
                + "\"logs\":[\"Program log: ok\"],"
                + "\"unitsConsumed\":150}}");
        SolanaRpcClient.SimulationResult result = rpcClient.simulateTransaction("dGVzdA==");
        assertNotNull(result);
        assertTrue(result.success());
        assertEquals(150L, result.unitsConsumed);
        assertNotNull(result.logs);
        assertEquals(1, result.logs.size());
        assertEquals("Program log: ok", result.logs.get(0));
    }

    @Test
    @DisplayName("simulateTransaction: 失败时返回 err 非空")
    void simulateTransaction_failure() throws Exception {
        mockResponse("{\"context\":{\"slot\":12345},\"value\":{"
                + "\"err\":\"InstructionFallback\","
                + "\"logs\":[\"Program log: failed\"],"
                + "\"unitsConsumed\":0}}");
        SolanaRpcClient.SimulationResult result = rpcClient.simulateTransaction("dGVzdA==");
        assertNotNull(result);
        assertFalse(result.success());
        assertEquals("InstructionFallback", result.err);
    }

    @Test
    @DisplayName("simulateTransaction: 空入参返回 err 结果")
    void simulateTransaction_empty() {
        SolanaRpcClient.SimulationResult result = rpcClient.simulateTransaction("");
        assertNotNull(result);
        assertFalse(result.success());
    }

    // ==================== getTransaction 测试 ====================

    @Test
    @DisplayName("getTransaction: 成功时返回交易详情")
    void getTransaction_success() throws Exception {
        mockResponse("{\"slot\":12345,\"transaction\":{\"message\":{}},\"meta\":{\"err\":null}}");
        JsonNode tx = rpcClient.getTransaction("5xQF4tW3W2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y");
        assertNotNull(tx);
        assertEquals(12345, tx.path("slot").asInt());
    }

    @Test
    @DisplayName("getTransaction: 交易未确认时返回 null result")
    void getTransaction_notFound() throws Exception {
        mockResponse("null");
        JsonNode tx = rpcClient.getTransaction("5xQF4tW3W2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y2Y");
        assertNull(tx);
    }

    @Test
    @DisplayName("getTransaction: 空签名返回 null")
    void getTransaction_emptySignature() {
        assertNull(rpcClient.getTransaction(""));
    }

    // ==================== getSlot / getBlockHeight 测试 ====================

    @Test
    @DisplayName("getSlot: 成功时返回 slot 数值")
    void getSlot_success() throws Exception {
        mockResponse("123456");
        assertEquals(123456L, rpcClient.getSlot());
    }

    @Test
    @DisplayName("getSlot: RPC error 时返回 -1")
    void getSlot_rpcError() throws Exception {
        mockErrorResponse(-32603, "Internal error");
        assertEquals(-1L, rpcClient.getSlot());
    }

    @Test
    @DisplayName("getBlockHeight: 成功时返回区块高度")
    void getBlockHeight_success() throws Exception {
        mockResponse("789012");
        assertEquals(789012L, rpcClient.getBlockHeight());
    }

    @Test
    @DisplayName("getBlockHeight: HTTP 500 时返回 -1")
    void getBlockHeight_httpError() throws Exception {
        when(httpResponse.statusCode()).thenReturn(503);
        when(httpResponse.body()).thenReturn("Service Unavailable");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
        assertEquals(-1L, rpcClient.getBlockHeight());
    }

    // ==================== getTokenAccountsByOwner 测试 ====================

    @Test
    @DisplayName("getTokenAccountsByOwner: 成功时返回 value 数组")
    void getTokenAccountsByOwner_success() throws Exception {
        mockResponse("{\"context\":{\"slot\":1},\"value\":[]}");
        JsonNode result = rpcClient.getTokenAccountsByOwner(objectMapper.createArrayNode());
        assertNotNull(result);
        assertTrue(result.has("value"));
    }

    @Test
    @DisplayName("getTokenAccountsByOwner: RPC error 时返回 null")
    void getTokenAccountsByOwner_rpcError() throws Exception {
        mockErrorResponse(-32000, "Invalid param");
        assertNull(rpcClient.getTokenAccountsByOwner(objectMapper.createArrayNode()));
    }

    // ==================== 构造与配置测试 ====================

    @Test
    @DisplayName("构造: null endpoint 抛 IllegalArgumentException")
    void constructor_nullEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new SolanaRpcClient(null));
    }

    @Test
    @DisplayName("构造: 空 endpoint 抛 IllegalArgumentException")
    void constructor_emptyEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new SolanaRpcClient(""));
    }

    @Test
    @DisplayName("getRpcEndpoint: 返回构造时指定的 endpoint")
    void getRpcEndpoint_returnsConfigured() {
        SolanaRpcClient client = new SolanaRpcClient(
                "https://api.testnet.solana.com", httpClient);
        assertEquals("https://api.testnet.solana.com", client.getRpcEndpoint());
    }

    @Test
    @DisplayName("endpoint 常量: mainnet/testnet/devnet 应为非空字符串")
    void endpointConstants_nonEmpty() {
        assertNotNull(SolanaRpcClient.MAINNET_ENDPOINT);
        assertNotNull(SolanaRpcClient.TESTNET_ENDPOINT);
        assertNotNull(SolanaRpcClient.DEVNET_ENDPOINT);
        assertTrue(SolanaRpcClient.MAINNET_ENDPOINT.contains("mainnet"));
        assertTrue(SolanaRpcClient.TESTNET_ENDPOINT.contains("testnet"));
        assertTrue(SolanaRpcClient.DEVNET_ENDPOINT.contains("devnet"));
    }
}