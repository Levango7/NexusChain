package org.nexus.bridge.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AvalancheAdapter} 单元测试：覆盖链 ID、RPC 端点配置、
 * 无连接时的错误处理与 shutdown 行为。
 *
 * <p>Avalanche C-Chain 兼容 EVM，复用 {@link AbstractEvmChainAdapter} 通用逻辑，
 * 本测试重点验证 Avalanche 特有的链 ID（{@code 0xA86A} 主网 / {@code 0xA869} Fuji 测试网）
 * 与默认 RPC 端点配置。</p>
 */
class AvalancheAdapterTest {

    /** Avalanche 主网 Chain ID（十六进制）。 */
    private static final String CHAIN_ID_MAINNET = "0xA86A";

    /** Avalanche Fuji 测试网 Chain ID（十六进制）。 */
    private static final String CHAIN_ID_FUJI = "0xA869";

    /** Avalanche 主网 RPC 端点。 */
    private static final String RPC_MAINNET = "https://api.avax.network/ext/bc/C/rpc";

    /** Avalanche Fuji 测试网 RPC 端点。 */
    private static final String RPC_FUJI = "https://api.avax-test.network/ext/bc/C/rpc";

    // ==================== 链 ID 测试 ====================

    @Test
    @DisplayName("主网适配器应返回链 ID 0xA86A")
    void mainnetAdapter_returnsMainnetChainId() {
        AvalancheAdapter adapter = new AvalancheAdapter(RPC_MAINNET, CHAIN_ID_MAINNET);
        assertEquals(CHAIN_ID_MAINNET, adapter.getChainId());
    }

    @Test
    @DisplayName("Fuji 测试网适配器应返回链 ID 0xA869")
    void fujiAdapter_returnsFujiChainId() {
        AvalancheAdapter adapter = new AvalancheAdapter(RPC_FUJI, CHAIN_ID_FUJI);
        assertEquals(CHAIN_ID_FUJI, adapter.getChainId());
    }

    @Test
    @DisplayName("自定义链 ID 应正确返回")
    void customChainId_returnsConfigured() {
        AvalancheAdapter adapter = new AvalancheAdapter("http://localhost:9650/ext/bc/C/rpc", "0x999");
        assertEquals("0x999", adapter.getChainId());
    }

    // ==================== getBlockHeight 测试（无连接） ====================

    @Test
    @DisplayName("getBlockHeight: 无连接时返回 -1（主网端点）")
    void getBlockHeight_noConnection_mainnet() {
        AvalancheAdapter adapter = new AvalancheAdapter("http://invalid-localhost:9999", CHAIN_ID_MAINNET);
        assertEquals(-1L, adapter.getBlockHeight());
    }

    @Test
    @DisplayName("getBlockHeight: 无连接时返回 -1（Fuji 端点）")
    void getBlockHeight_noConnection_fuji() {
        AvalancheAdapter adapter = new AvalancheAdapter("http://invalid-localhost:9999", CHAIN_ID_FUJI);
        assertEquals(-1L, adapter.getBlockHeight());
    }

    // ==================== callContract 测试（无连接） ====================

    @Test
    @DisplayName("callContract: 无连接时返回 null")
    void callContract_noConnection_returnsNull() {
        AvalancheAdapter adapter = new AvalancheAdapter("http://invalid-localhost:9999", CHAIN_ID_MAINNET);
        assertNull(adapter.callContract("0xcontract", "0xdata"));
    }

    @Test
    @DisplayName("callContract: null 地址抛 IllegalArgumentException")
    void callContract_nullAddressThrows() {
        AvalancheAdapter adapter = new AvalancheAdapter(RPC_MAINNET, CHAIN_ID_MAINNET);
        assertThrows(IllegalArgumentException.class, () -> adapter.callContract(null, "0xdata"));
    }

    @Test
    @DisplayName("callContract: 空地址抛 IllegalArgumentException")
    void callContract_emptyAddressThrows() {
        AvalancheAdapter adapter = new AvalancheAdapter(RPC_MAINNET, CHAIN_ID_MAINNET);
        assertThrows(IllegalArgumentException.class, () -> adapter.callContract("", "0xdata"));
    }

    // ==================== getTransactionReceipt 测试（无连接） ====================

    @Test
    @DisplayName("getTransactionReceipt: 无连接时返回 null")
    void getTransactionReceipt_noConnection_returnsNull() {
        AvalancheAdapter adapter = new AvalancheAdapter("http://invalid-localhost:9999", CHAIN_ID_MAINNET);
        assertNull(adapter.getTransactionReceipt("0xhash"));
    }

    @Test
    @DisplayName("getTransactionReceipt: null 哈希返回 null")
    void getTransactionReceipt_nullHash_returnsNull() {
        AvalancheAdapter adapter = new AvalancheAdapter(RPC_MAINNET, CHAIN_ID_MAINNET);
        assertNull(adapter.getTransactionReceipt(null));
    }

    @Test
    @DisplayName("getTransactionReceipt: 空哈希返回 null")
    void getTransactionReceipt_emptyHash_returnsNull() {
        AvalancheAdapter adapter = new AvalancheAdapter(RPC_MAINNET, CHAIN_ID_MAINNET);
        assertNull(adapter.getTransactionReceipt(""));
    }

    // ==================== sendTransaction 测试（无连接） ====================

    @Test
    @DisplayName("sendTransaction: 无连接时抛 RuntimeException")
    void sendTransaction_noConnection_throwsRuntimeException() {
        AvalancheAdapter adapter = new AvalancheAdapter("http://invalid-localhost:9999", CHAIN_ID_MAINNET);
        assertThrows(RuntimeException.class, () -> adapter.sendTransaction(new byte[]{1, 2, 3}));
    }

    @Test
    @DisplayName("sendTransaction: null 字节抛 IllegalArgumentException")
    void sendTransaction_nullBytes_throws() {
        AvalancheAdapter adapter = new AvalancheAdapter(RPC_MAINNET, CHAIN_ID_MAINNET);
        assertThrows(IllegalArgumentException.class, () -> adapter.sendTransaction(null));
    }

    @Test
    @DisplayName("sendTransaction: 空字节数组抛 IllegalArgumentException")
    void sendTransaction_emptyBytes_throws() {
        AvalancheAdapter adapter = new AvalancheAdapter(RPC_MAINNET, CHAIN_ID_MAINNET);
        assertThrows(IllegalArgumentException.class, () -> adapter.sendTransaction(new byte[0]));
    }

    // ==================== shutdown 测试 ====================

    @Test
    @DisplayName("shutdown: 主网适配器不抛异常")
    void shutdown_mainnet_noException() {
        AvalancheAdapter adapter = new AvalancheAdapter(RPC_MAINNET, CHAIN_ID_MAINNET);
        assertDoesNotThrow(adapter::shutdown);
    }

    @Test
    @DisplayName("shutdown: Fuji 测试网适配器不抛异常")
    void shutdown_fuji_noException() {
        AvalancheAdapter adapter = new AvalancheAdapter(RPC_FUJI, CHAIN_ID_FUJI);
        assertDoesNotThrow(adapter::shutdown);
    }

    // ==================== Chain ID 十进制对照测试 ====================

    @Test
    @DisplayName("主网 Chain ID 0xA86A 应对应十进制 43114")
    void mainnetChainId_decimal43114() {
        // 0xA86A = 43114
        assertEquals(43114, Integer.parseInt(CHAIN_ID_MAINNET.substring(2), 16));
    }

    @Test
    @DisplayName("Fuji 测试网 Chain ID 0xA869 应对应十进制 43113")
    void fujiChainId_decimal43113() {
        // 0xA869 = 43113
        assertEquals(43113, Integer.parseInt(CHAIN_ID_FUJI.substring(2), 16));
    }
}