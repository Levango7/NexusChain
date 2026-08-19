package org.nexus.gateway.orchestration;

import org.junit.jupiter.api.*;
import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.orchestration.connector.*;
import org.nexus.gateway.orchestration.connectors.ChainConnector;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 支付链路混沌测试：故障注入 + 恢复断言。
 *
 * <p>验证支付主链路在依赖服务（签名服务 / 钱包服务 / 链上 RPC）故障期间的行为：
 * 降级不崩溃、故障恢复后自动恢复正常服务。纯 Java 沙箱，不启动 Spring 容器。
 *
 * <p>混沌场景：
 * <ul>
 *   <li>链上节点宕机 → 查询降级 → 节点恢复 → 查询成功</li>
 *   <li>签名服务间歇性故障 → 最终成功</li>
 *   <li>钱包服务宕机 → 地址解析失败 → 恢复 → 成功</li>
 *   <li>级联故障（签名 + 链上同时宕机）→ 不崩溃 → 恢复 → 正常</li>
 *   <li>签名服务超时 → 支付失败 → 恢复 → 成功</li>
 * </ul>
 *
 * @since 2.9.0
 */
@DisplayName("支付链路混沌测试：故障注入+恢复")
class PaymentChaosTest {

    private static final String PLATFORM_PUBKEY = "platformPk-chaos";

    private ChainRpcClient rpc;
    private SigningServiceFeignClient signing;
    private WalletMgmtFeignClient walletMgmt;
    private GatewayConfig config;

    @BeforeEach
    void setUp() {
        rpc = mock(ChainRpcClient.class);
        signing = mock(SigningServiceFeignClient.class);
        walletMgmt = mock(WalletMgmtFeignClient.class);
        config = gatewayConfigWith(PLATFORM_PUBKEY);
    }

    private ChainConnector newConnector() {
        return new ChainConnector(rpc, signing, walletMgmt, config);
    }

    private GatewayConfig gatewayConfigWith(String platformPubkey) {
        GatewayConfig cfg = mock(GatewayConfig.class);
        GatewayConfig.ExchangeWalletConfig ew = mock(GatewayConfig.ExchangeWalletConfig.class);
        when(cfg.getExchangeWallet()).thenReturn(ew);
        when(ew.getPlatformPubkey()).thenReturn(platformPubkey);
        return cfg;
    }

    private ConnectorPaymentRequest paymentRequest(String id, long amount) {
        ConnectorPaymentRequest req = new ConnectorPaymentRequest(id, amount, "NEX", "chaos test");
        req.setPayerAddress("0xPayer_" + id);
        req.setPayeeAddress("0xPayee_" + id);
        return req;
    }

    // ==================== 混沌测试用例 ====================

    @Test
    @Order(1)
    @DisplayName("1. 链上节点宕机→支付创建成功→查询降级PROCESSING→节点恢复→查询SUCCEEDED")
    void chainNodeDown_thenRecover() {
        when(walletMgmt.addressToPubkeyHash("0xPayee_chaos1")).thenReturn("payeeHash");
        when(walletMgmt.addressToPubkeyHash("0xPayer_chaos1")).thenReturn("payerHash");
        when(signing.signTransfer(anyString(), anyString(), any())).thenReturn("0xchaosTx1");

        // 链上节点宕机：isTransactionConfirmed 抛异常
        AtomicBoolean nodeAlive = new AtomicBoolean(false);
        when(rpc.isTransactionConfirmed("0xchaosTx1")).thenAnswer(inv -> {
            if (!nodeAlive.get()) throw new RuntimeException("node down");
            return true;
        });

        ChainConnector connector = newConnector();

        // 支付创建应成功（签名已广播，不依赖链上查询）
        ConnectorPaymentResult created = connector.createPayment(paymentRequest("chaos1", 50000L));
        assertTrue(created.isSuccess(), "节点宕机期间支付创建应成功（签名已广播）");

        // 查询应降级为 PROCESSING（不崩溃）
        assertEquals(PaymentStatus.PROCESSING, connector.queryPayment(created.getConnectorPaymentId()),
                "节点宕机期间查询应降级为 PROCESSING");

        // 节点恢复
        nodeAlive.set(true);

        // 查询应返回 SUCCEEDED
        assertEquals(PaymentStatus.SUCCEEDED, connector.queryPayment(created.getConnectorPaymentId()),
                "节点恢复后查询应返回 SUCCEEDED");
    }

    @Test
    @Order(2)
    @DisplayName("2. 签名服务间歇性故障→最终成功")
    void signingServiceIntermittent_thenSuccess() {
        when(walletMgmt.addressToPubkeyHash("0xPayee_chaos2")).thenReturn("payeeHash");
        when(walletMgmt.addressToPubkeyHash("0xPayer_chaos2")).thenReturn("payerHash");

        // 签名服务间歇性故障：前2次返回null，第3次成功
        AtomicInteger callCount = new AtomicInteger(0);
        when(signing.signTransfer(anyString(), anyString(), any())).thenAnswer(inv -> {
            int n = callCount.incrementAndGet();
            return n <= 2 ? null : "0xrecoveredTx";
        });

        ChainConnector connector = newConnector();

        // 第1次：故障
        assertFalse(connector.createPayment(paymentRequest("chaos2", 10000L)).isSuccess(),
                "第1次签名故障应失败");
        // 第2次：故障
        assertFalse(connector.createPayment(paymentRequest("chaos2", 10000L)).isSuccess(),
                "第2次签名故障应失败");
        // 第3次：恢复
        ConnectorPaymentResult recovered = connector.createPayment(paymentRequest("chaos2", 10000L));
        assertTrue(recovered.isSuccess(), "第3次签名恢复应成功");
        assertEquals("0xrecoveredTx", recovered.getTransactionHash());
    }

    @Test
    @Order(3)
    @DisplayName("3. 钱包服务宕机→地址解析失败→恢复→成功")
    void walletServiceDown_thenRecover() {
        // 钱包服务宕机：地址解析抛异常
        AtomicBoolean walletAlive = new AtomicBoolean(false);
        when(walletMgmt.addressToPubkeyHash("0xPayee_chaos3")).thenAnswer(inv -> {
            if (!walletAlive.get()) throw new RuntimeException("wallet service down");
            return "payeeHash";
        });
        when(walletMgmt.addressToPubkeyHash("0xPayer_chaos3")).thenReturn("payerHash");
        when(signing.signTransfer(anyString(), anyString(), any())).thenReturn("0xwalletTx");

        ChainConnector connector = newConnector();

        // 钱包服务宕机：支付失败
        ConnectorPaymentResult failed = connector.createPayment(paymentRequest("chaos3", 20000L));
        assertFalse(failed.isSuccess(), "钱包服务宕机应导致支付失败");

        // 钱包服务恢复
        walletAlive.set(true);

        // 支付成功
        ConnectorPaymentResult success = connector.createPayment(paymentRequest("chaos3", 20000L));
        assertTrue(success.isSuccess(), "钱包服务恢复后应成功");
        assertEquals("0xwalletTx", success.getTransactionHash());
    }

    @Test
    @Order(4)
    @DisplayName("4. 级联故障：签名+链上同时宕机→不崩溃→恢复→正常")
    void cascadingFailure_signingAndChain_thenRecover() {
        when(walletMgmt.addressToPubkeyHash("0xPayee_chaos4")).thenReturn("payeeHash");
        when(walletMgmt.addressToPubkeyHash("0xPayer_chaos4")).thenReturn("payerHash");

        // 级联故障：签名服务先宕机，恢复后链上又宕机，最终全部恢复
        AtomicInteger signingCalls = new AtomicInteger(0);
        when(signing.signTransfer(anyString(), anyString(), any())).thenAnswer(inv -> {
            int n = signingCalls.incrementAndGet();
            if (n == 1) return null; // 第1次：签名宕机
            return "0xcascadeTx";     // 第2次起：签名恢复
        });

        AtomicBoolean chainAlive = new AtomicBoolean(false);
        when(rpc.isTransactionConfirmed("0xcascadeTx")).thenAnswer(inv -> {
            if (!chainAlive.get()) throw new RuntimeException("chain down");
            return true;
        });

        ChainConnector connector = newConnector();

        // 阶段1：签名宕机 → 支付失败
        assertFalse(connector.createPayment(paymentRequest("chaos4", 30000L)).isSuccess(),
                "签名宕机应导致支付失败");

        // 阶段2：签名恢复，链上宕机 → 支付创建成功，查询降级
        ConnectorPaymentResult created = connector.createPayment(paymentRequest("chaos4", 30000L));
        assertTrue(created.isSuccess(), "签名恢复后支付应成功");
        assertEquals(PaymentStatus.PROCESSING, connector.queryPayment(created.getConnectorPaymentId()),
                "链上宕机期间查询应降级为 PROCESSING");

        // 阶段3：链上恢复 → 查询成功
        chainAlive.set(true);
        assertEquals(PaymentStatus.SUCCEEDED, connector.queryPayment(created.getConnectorPaymentId()),
                "链上恢复后查询应返回 SUCCEEDED");
    }

    @Test
    @Order(5)
    @DisplayName("5. 签名服务超时→支付失败→恢复→成功")
    void signingTimeout_thenRecover() {
        when(walletMgmt.addressToPubkeyHash("0xPayee_chaos5")).thenReturn("payeeHash");
        when(walletMgmt.addressToPubkeyHash("0xPayer_chaos5")).thenReturn("payerHash");

        // 签名服务超时（模拟超时异常）
        AtomicBoolean signingResponsive = new AtomicBoolean(false);
        when(signing.signTransfer(anyString(), anyString(), any())).thenAnswer(inv -> {
            if (!signingResponsive.get()) throw new RuntimeException("signing timeout");
            return "0xtimeoutRecoverTx";
        });

        ChainConnector connector = newConnector();

        // 超时：支付失败（ChainConnector 捕获异常返回 FAILED）
        ConnectorPaymentResult timeout = connector.createPayment(paymentRequest("chaos5", 15000L));
        assertFalse(timeout.isSuccess(), "签名超时应导致支付失败");

        // 恢复
        signingResponsive.set(true);

        // 成功
        ConnectorPaymentResult success = connector.createPayment(paymentRequest("chaos5", 15000L));
        assertTrue(success.isSuccess(), "签名恢复后应成功");
        assertEquals("0xtimeoutRecoverTx", success.getTransactionHash());
    }
}