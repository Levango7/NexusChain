package org.nexus.bridge.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.bridge.solana.SolanaRpcClient;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SolanaAdapter} 单元测试：覆盖 ChainAdapter 接口实现与 SPL Token 余额查询。
 */
@ExtendWith(MockitoExtension.class)
class SolanaAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SolanaRpcClient rpcClient;

    private SolanaAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SolanaAdapter(
                SolanaAdapter.CHAIN_ID_MAINNET,
                SolanaAdapter.DEFAULT_SPL_TOKEN_PROGRAM,
                rpcClient);
    }

    // ==================== getChainId 测试 ====================

    @Test
    @DisplayName("getChainId 应返回构造时指定的链 ID")
    void getChainId_returnsConfigured() {
        assertEquals(SolanaAdapter.CHAIN_ID_MAINNET, adapter.getChainId());
    }

    @Test
    @DisplayName("自定义链 ID 应正确返回")
    void getChainId_custom() {
        SolanaAdapter custom = new SolanaAdapter(
                "solana-devnet", SolanaAdapter.DEFAULT_SPL_TOKEN_PROGRAM, rpcClient);
        assertEquals("solana-devnet", custom.getChainId());
    }

    // ==================== getBlockHeight 测试 ====================

    @Test
    @DisplayName("getBlockHeight: 委托 RPC 客户端并返回结果")
    void getBlockHeight_delegatesToRpc() {
        when(rpcClient.getBlockHeight()).thenReturn(12345L);
        assertEquals(12345L, adapter.getBlockHeight());
    }

    @Test
    @DisplayName("getBlockHeight: RPC 失败时返回 -1")
    void getBlockHeight_rpcFailure() {
        when(rpcClient.getBlockHeight()).thenReturn(-1L);
        assertEquals(-1L, adapter.getBlockHeight());
    }

    // ==================== sendTransaction 测试 ====================

    @Test
    @DisplayName("sendTransaction: null 字节抛 IllegalArgumentException")
    void sendTransaction_nullThrows() {
        assertThrows(IllegalArgumentException.class, () -> adapter.sendTransaction(null));
    }

    @Test
    @DisplayName("sendTransaction: 空字节数组抛 IllegalArgumentException")
    void sendTransaction_emptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> adapter.sendTransaction(new byte[0]));
    }

    @Test
    @DisplayName("sendTransaction: 字节以 base64 编码提交并返回签名")
    void sendTransaction_success() throws Exception {
        byte[] txBytes = {1, 2, 3, 4, 5};
        String expectedBase64 = Base64.getEncoder().encodeToString(txBytes);
        when(rpcClient.sendTransaction(expectedBase64)).thenReturn("sig123");
        String sig = adapter.sendTransaction(txBytes);
        assertEquals("sig123", sig);
    }

    // ==================== getTransactionReceipt 测试 ====================

    @Test
    @DisplayName("getTransactionReceipt: null 哈希返回 null")
    void getTransactionReceipt_nullReturnsNull() {
        assertNull(adapter.getTransactionReceipt(null));
    }

    @Test
    @DisplayName("getTransactionReceipt: 空哈希返回 null")
    void getTransactionReceipt_emptyReturnsNull() {
        assertNull(adapter.getTransactionReceipt(""));
    }

    @Test
    @DisplayName("getTransactionReceipt: 委托 RPC 客户端")
    void getTransactionReceipt_delegates() {
        JsonNode mockNode = objectMapper.createObjectNode().put("slot", 123);
        when(rpcClient.getTransaction("sig")).thenReturn(mockNode);
        Object result = adapter.getTransactionReceipt("sig");
        assertSame(mockNode, result);
    }

    // ==================== callContract 测试 ====================

    @Test
    @DisplayName("callContract: null 地址抛 IllegalArgumentException")
    void callContract_nullAddressThrows() {
        assertThrows(IllegalArgumentException.class, () -> adapter.callContract(null, "data"));
    }

    @Test
    @DisplayName("callContract: 空地址抛 IllegalArgumentException")
    void callContract_emptyAddressThrows() {
        assertThrows(IllegalArgumentException.class, () -> adapter.callContract("", "data"));
    }

    @Test
    @DisplayName("callContract: null data 返回 null")
    void callContract_nullDataReturnsNull() {
        assertNull(adapter.callContract("programId", null));
    }

    @Test
    @DisplayName("callContract: 空 data 返回 null")
    void callContract_emptyDataReturnsNull() {
        assertNull(adapter.callContract("programId", ""));
    }

    @Test
    @DisplayName("callContract: 合法 base64 data 调用 simulateTransaction 返回 JSON")
    void callContract_validBase64() {
        String base64Data = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        SolanaRpcClient.SimulationResult sim = new SolanaRpcClient.SimulationResult();
        sim.err = null;
        sim.unitsConsumed = 150;
        sim.logs = java.util.Arrays.asList("log1", "log2");
        when(rpcClient.simulateTransaction(base64Data)).thenReturn(sim);

        String result = adapter.callContract("programId", base64Data);
        assertNotNull(result);
        assertTrue(result.contains("\"unitsConsumed\":150"));
        assertTrue(result.contains("log1"));
    }

    @Test
    @DisplayName("callContract: 模拟失败时 err 写入返回 JSON")
    void callContract_simulationFailed() {
        String base64Data = Base64.getEncoder().encodeToString(new byte[]{1});
        SolanaRpcClient.SimulationResult sim = new SolanaRpcClient.SimulationResult();
        sim.err = "InsufficientFunds";
        when(rpcClient.simulateTransaction(base64Data)).thenReturn(sim);

        String result = adapter.callContract("programId", base64Data);
        assertNotNull(result);
        assertTrue(result.contains("InsufficientFunds"));
    }

    @Test
    @DisplayName("callContract: 非 base64 data 按 UTF-8 转 base64")
    void callContract_nonBase64Data() {
        String data = "hello";
        String expectedBase64 = Base64.getEncoder().encodeToString(data.getBytes());
        SolanaRpcClient.SimulationResult sim = new SolanaRpcClient.SimulationResult();
        sim.err = null;
        sim.unitsConsumed = 0;
        when(rpcClient.simulateTransaction(expectedBase64)).thenReturn(sim);

        String result = adapter.callContract("programId", data);
        assertNotNull(result);
    }

    @Test
    @DisplayName("callContract: simulateTransaction 返回 null 时返回 null")
    void callContract_simReturnsNull() {
        String base64Data = Base64.getEncoder().encodeToString(new byte[]{1});
        when(rpcClient.simulateTransaction(base64Data)).thenReturn(null);
        assertNull(adapter.callContract("programId", base64Data));
    }

    // ==================== getSolBalance 测试 ====================

    @Test
    @DisplayName("getSolBalance: 委托 RPC 客户端")
    void getSolBalance_delegates() {
        when(rpcClient.getBalance("pubkey")).thenReturn(5_000_000_000L);
        assertEquals(5_000_000_000L, adapter.getSolBalance("pubkey"));
    }

    // ==================== getAddressBalance (SPL Token) 测试 ====================

    @Test
    @DisplayName("getAddressBalance: null owner 返回 -1")
    void getAddressBalance_nullOwner() {
        assertEquals(-1L, adapter.getAddressBalance(null, "mint"));
    }

    @Test
    @DisplayName("getAddressBalance: 空 owner 返回 -1")
    void getAddressBalance_emptyOwner() {
        assertEquals(-1L, adapter.getAddressBalance("", "mint"));
    }

    @Test
    @DisplayName("getAddressBalance: null mint 返回 -1")
    void getAddressBalance_nullMint() {
        assertEquals(-1L, adapter.getAddressBalance("owner", null));
    }

    @Test
    @DisplayName("getAddressBalance: 成功时累加匹配 mint 的 amount")
    void getAddressBalance_success() {
        // 构造 getTokenAccountsByOwner 返回：两个 Token Account，一个 mint 匹配，一个不匹配
        ObjectNode account1 = objectMapper.createObjectNode();
        account1.putObject("account").putObject("data").putObject("parsed").putObject("info")
                .put("mint", "targetMint")
                .putObject("tokenAmount").put("amount", "1000000").put("decimals", 6);
        ObjectNode account2 = objectMapper.createObjectNode();
        account2.putObject("account").putObject("data").putObject("parsed").putObject("info")
                .put("mint", "otherMint")
                .putObject("tokenAmount").put("amount", "2000000").put("decimals", 6);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode value = result.putArray("value");
        value.add(account1);
        value.add(account2);

        when(rpcClient.getTokenAccountsByOwner(any(JsonNode.class))).thenReturn(result);
        long balance = adapter.getAddressBalance("owner", "targetMint");
        assertEquals(1_000_000L, balance);
    }

    @Test
    @DisplayName("getAddressBalance: 多个匹配 mint 的 Token Account 累加")
    void getAddressBalance_multipleMatches() {
        ObjectNode account1 = objectMapper.createObjectNode();
        account1.putObject("account").putObject("data").putObject("parsed").putObject("info")
                .put("mint", "m").putObject("tokenAmount").put("amount", "100");
        ObjectNode account2 = objectMapper.createObjectNode();
        account2.putObject("account").putObject("data").putObject("parsed").putObject("info")
                .put("mint", "m").putObject("tokenAmount").put("amount", "200");
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode value = result.putArray("value");
        value.add(account1);
        value.add(account2);

        when(rpcClient.getTokenAccountsByOwner(any(JsonNode.class))).thenReturn(result);
        assertEquals(300L, adapter.getAddressBalance("owner", "m"));
    }

    @Test
    @DisplayName("getAddressBalance: RPC 返回 null 时返回 -1")
    void getAddressBalance_rpcNull() {
        when(rpcClient.getTokenAccountsByOwner(any(JsonNode.class))).thenReturn(null);
        assertEquals(-1L, adapter.getAddressBalance("owner", "mint"));
    }

    @Test
    @DisplayName("getAddressBalance: 无匹配 mint 时返回 0")
    void getAddressBalance_noMatch() {
        ObjectNode account1 = objectMapper.createObjectNode();
        account1.putObject("account").putObject("data").putObject("parsed").putObject("info")
                .put("mint", "otherMint").putObject("tokenAmount").put("amount", "100");
        ObjectNode result = objectMapper.createObjectNode();
        result.putArray("value").add(account1);

        when(rpcClient.getTokenAccountsByOwner(any(JsonNode.class))).thenReturn(result);
        assertEquals(0L, adapter.getAddressBalance("owner", "targetMint"));
    }

    // ==================== getLatestBlockhash / simulateTransaction 测试 ====================

    @Test
    @DisplayName("getLatestBlockhash: 委托 RPC 客户端")
    void getLatestBlockhash_delegates() {
        SolanaRpcClient.Blockhash bh = new SolanaRpcClient.Blockhash("hash", 100L);
        when(rpcClient.getLatestBlockhash()).thenReturn(bh);
        SolanaRpcClient.Blockhash result = adapter.getLatestBlockhash();
        assertSame(bh, result);
    }

    @Test
    @DisplayName("simulateTransaction: 委托 RPC 客户端")
    void simulateTransaction_delegates() {
        SolanaRpcClient.SimulationResult sim = new SolanaRpcClient.SimulationResult();
        sim.err = null;
        when(rpcClient.simulateTransaction("tx")).thenReturn(sim);
        SolanaRpcClient.SimulationResult result = adapter.simulateTransaction("tx");
        assertSame(sim, result);
    }

    // ==================== Getter / shutdown 测试 ====================

    @Test
    @DisplayName("getSplTokenProgramId: 返回构造时指定的 Program ID")
    void getSplTokenProgramId_returnsConfigured() {
        assertEquals(SolanaAdapter.DEFAULT_SPL_TOKEN_PROGRAM, adapter.getSplTokenProgramId());
    }

    @Test
    @DisplayName("getRpcClient: 返回注入的 RPC 客户端")
    void getRpcClient_returnsInjected() {
        assertSame(rpcClient, adapter.getRpcClient());
    }

    @Test
    @DisplayName("shutdown: 不抛异常")
    void shutdown_noException() {
        assertDoesNotThrow(adapter::shutdown);
    }

    // ==================== 常量测试 ====================

    @Test
    @DisplayName("常量: CHAIN_ID_MAINNET 应为 solana-mainnet")
    void constant_chainIdMainnet() {
        assertEquals("solana-mainnet", SolanaAdapter.CHAIN_ID_MAINNET);
    }

    @Test
    @DisplayName("常量: DEFAULT_SPL_TOKEN_PROGRAM 应为官方 Token Program")
    void constant_defaultSplTokenProgram() {
        assertEquals("TokenkegQfeZyiNwAJbNbGKPFXCWuBhf924s93HX2TN",
                SolanaAdapter.DEFAULT_SPL_TOKEN_PROGRAM);
    }
}