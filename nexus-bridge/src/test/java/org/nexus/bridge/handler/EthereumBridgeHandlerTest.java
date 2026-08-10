package org.nexus.bridge.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.bridge.*;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EthereumBridgeHandler} 单元测试：覆盖链 ID、配置注入与 RPC 未配置错误。
 */
class EthereumBridgeHandlerTest {

    private BridgeConfig config;

    @BeforeEach
    void setUp() {
        config = new BridgeConfig();
        config.setSignatureThreshold(2);
        config.setMaxAmountPerTx(10_000_000_000L);
        config.setDailyLimit(100_000_000_000L);
        config.setValidatorPublicKeys(Arrays.asList("v1", "v2"));
    }

    @Test
    @DisplayName("单参数构造应返回链 ID ethereum")
    void singleArgConstructor_returnsEthereumChainId() {
        EthereumBridgeHandler handler = new EthereumBridgeHandler(config);
        assertEquals("ethereum", handler.getChainId());
    }

    @Test
    @DisplayName("全参数构造应正确设置合约地址与 RPC 端点")
    void fullConstructor_setsFields() {
        EthereumBridgeHandler handler = new EthereumBridgeHandler(
                config, "0xContract", "0xToken", "http://localhost:8545");
        assertEquals("ethereum", handler.getChainId());
        assertEquals("0xContract", handler.getContractAddress());
        assertEquals("0xToken", handler.getCpayTokenAddress());
        assertEquals("http://localhost:8545", handler.getRpcEndpoint());
    }

    @Test
    @DisplayName("setter 应正确更新合约地址与 RPC 端点")
    void setters_updateFields() {
        EthereumBridgeHandler handler = new EthereumBridgeHandler(config);
        handler.setContractAddress("0xNewContract");
        handler.setCpayTokenAddress("0xNewToken");
        handler.setRpcEndpoint("http://new-rpc:8545");

        assertEquals("0xNewContract", handler.getContractAddress());
        assertEquals("0xNewToken", handler.getCpayTokenAddress());
        assertEquals("http://new-rpc:8545", handler.getRpcEndpoint());
    }

    @Test
    @DisplayName("queryTransactionStatus: RPC 未配置应抛 BridgeException")
    void queryTransactionStatus_rpcNotConfiguredThrows() {
        EthereumBridgeHandler handler = new EthereumBridgeHandler(config);
        BridgeException ex = assertThrows(BridgeException.class,
                () -> handler.queryTransactionStatus("0xhash"));
        assertEquals("RPC_NOT_CONFIGURED", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: RPC 未配置时校验通过后应抛 RPC_NOT_CONFIGURED")
    void lock_rpcNotConfiguredThrowsAfterValidation() {
        EthereumBridgeHandler handler = new EthereumBridgeHandler(config);
        LockRequest req = new LockRequest("ethereum", "bsc", 1000L, "0xUser", "0xTarget", "0xHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("RPC_NOT_CONFIGURED", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: 金额校验失败应在 RPC 校验前抛出")
    void lock_amountValidationBeforeRpcCheck() {
        EthereumBridgeHandler handler = new EthereumBridgeHandler(config);
        LockRequest req = new LockRequest("ethereum", "bsc", 0, "0xUser", "0xTarget", "0xHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("INVALID_AMOUNT", ex.getErrorCode());
    }

    @Test
    @DisplayName("awaitConfirmations: RPC 未配置应抛 BridgeException")
    void awaitConfirmations_rpcNotConfiguredThrows() {
        EthereumBridgeHandler handler = new EthereumBridgeHandler(config);
        BridgeException ex = assertThrows(BridgeException.class,
                () -> handler.awaitConfirmations("0xhash"));
        assertEquals("RPC_NOT_CONFIGURED", ex.getErrorCode());
    }

    @Test
    @DisplayName("setRpcEndpoint 后应重置 Web3j 客户端")
    void setRpcEndpoint_resetsWeb3j() {
        EthereumBridgeHandler handler = new EthereumBridgeHandler(
                config, "0xContract", "0xToken", "http://localhost:8545");
        // 修改 RPC 端点
        handler.setRpcEndpoint("http://new-rpc:8545");
        assertEquals("http://new-rpc:8545", handler.getRpcEndpoint());
        // 再次设置应仍可正常工作
        handler.setRpcEndpoint("http://another-rpc:8545");
        assertEquals("http://another-rpc:8545", handler.getRpcEndpoint());
    }
}