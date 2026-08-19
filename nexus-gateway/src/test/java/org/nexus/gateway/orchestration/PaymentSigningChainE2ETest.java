package org.nexus.gateway.orchestration;

import org.junit.jupiter.api.*;
import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.orchestration.connector.*;
import org.nexus.gateway.orchestration.connectors.ChainConnector;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 支付→签名→链上 端到端集成测试。
 *
 * <p>产品主链路最后验证：{@link ChainConnector} 真实运行（不 mock 整个 connector），
 * 仅 mock 最底层依赖（{@link SigningServiceFeignClient} / {@link WalletMgmtFeignClient}
 * / {@link ChainRpcClient}），验证完整的支付编排链路：
 * <pre>
 *   支付请求 → 地址解析(walletMgmt) → MPC签名+广播(signingService) → 链上确认(chainRpc) → 退款
 * </pre>
 *
 * <p>纯 Java 沙箱，不启动 Spring 容器，所有组件直接 new 构造。
 * 覆盖 {@link ChainConnectorTest} 未覆盖的端到端编排场景：完整生命周期、故障恢复、
 * 退款方向策略、并发支付、链上 RPC 降级。
 *
 * <p>与第4轮 {@code PaymentE2EIntegrationTest} 的区别：后者用 {@code @MockBean ChainConnector}
 * mock 了整个连接器，只验证 HTTP 层；本测试让 ChainConnector 真实执行，验证
 * 地址解析→签名→链上确认的完整委托链路。
 *
 * @since 2.8.0
 */
@DisplayName("支付→签名→链上 端到端集成测试")
class PaymentSigningChainE2ETest {

    private static final String PLATFORM_PUBKEY = "platformPubkey-0x1234";

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
        System.clearProperty("nexus.refund.direction");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nexus.refund.direction");
    }

    // ==================== 辅助方法 ====================

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

    private ConnectorPaymentRequest paymentRequest(String paymentId, long amount) {
        ConnectorPaymentRequest req = new ConnectorPaymentRequest(paymentId, amount, "NEX", "e2e test");
        req.setPayerAddress("0xPayer_" + paymentId);
        req.setPayeeAddress("0xPayee_" + paymentId);
        return req;
    }

    // ==================== 测试用例 ====================

    @Test
    @Order(1)
    @DisplayName("1. 完整支付生命周期：create→query(PROCESSING)→query(SUCCEEDED)→refund(REFUNDED)")
    void fullPaymentLifecycle_createConfirmRefund() {
        // given: 地址解析成功，签名返回真实 txHash
        when(walletMgmt.addressToPubkeyHash("0xPayee_pay_1")).thenReturn("payeeHash001");
        when(walletMgmt.addressToPubkeyHash("0xPayer_pay_1")).thenReturn("payerHash001");
        when(signing.signTransfer(eq(PLATFORM_PUBKEY), eq("payeeHash001"), any()))
                .thenReturn("0xtxHashA1b2c3");
        // 链上第一次未确认，第二次确认
        when(rpc.isTransactionConfirmed("0xtxHashA1b2c3"))
                .thenReturn(false)
                .thenReturn(true);

        ChainConnector connector = newConnector();

        // when: 创建支付
        ConnectorPaymentResult created = connector.createPayment(paymentRequest("pay_1", 50000L));
        // then: PROCESSING + 真实 txHash
        assertTrue(created.isSuccess(), "创建应成功");
        assertEquals(PaymentStatus.PROCESSING, created.getStatus(), "初始状态应为 PROCESSING");
        assertEquals("0xtxHashA1b2c3", created.getTransactionHash(), "应返回签名服务给出的 txHash");
        assertNotNull(created.getConnectorPaymentId(), "应有 connectorPaymentId");

        // when: 第一次查询（未确认）
        PaymentStatus s1 = connector.queryPayment(created.getConnectorPaymentId());
        assertEquals(PaymentStatus.PROCESSING, s1, "未确认时应保持 PROCESSING");

        // when: 第二次查询（已确认）
        PaymentStatus s2 = connector.queryPayment(created.getConnectorPaymentId());
        assertEquals(PaymentStatus.SUCCEEDED, s2, "确认后应为 SUCCEEDED");

        // when: 退款（默认退给 payer）
        when(signing.signTransfer(eq(PLATFORM_PUBKEY), eq("payerHash001"), any()))
                .thenReturn("0xrefundHashX9y8");
        ConnectorRefundResult refund = connector.refund(created.getConnectorPaymentId(), 50000L);
        // then: 退款成功
        assertTrue(refund.isSuccess(), "退款应成功");
        assertNotNull(refund.getRefundId(), "应有 refundId");

        // when: 查询退款后状态
        PaymentStatus s3 = connector.queryPayment(created.getConnectorPaymentId());
        assertEquals(PaymentStatus.REFUNDED, s3, "退款后应为 REFUNDED");
    }

    @Test
    @Order(2)
    @DisplayName("2. 签名服务返回真实 txHash 格式验证")
    void signingServiceReturnsRealTxHash() {
        // 真实链上 txHash：0x + 64 hex chars（32 字节），用两个 UUID 拼接
        String realTxHash = "0x" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        when(walletMgmt.addressToPubkeyHash("0xPayee_pay_1")).thenReturn("payeeHash");
        when(walletMgmt.addressToPubkeyHash("0xPayer_pay_1")).thenReturn("payerHash");
        when(signing.signTransfer(anyString(), anyString(), any())).thenReturn(realTxHash);

        ChainConnector connector = newConnector();
        ConnectorPaymentResult result = connector.createPayment(paymentRequest("pay_1", 100000L));

        assertTrue(result.isSuccess());
        assertEquals(realTxHash, result.getTransactionHash());
        assertTrue(result.getTransactionHash().startsWith("0x"), "txHash 应以 0x 开头");
        assertEquals(66, result.getTransactionHash().length(), "txHash 应为 0x + 64 hex chars");
    }

    @Test
    @Order(3)
    @DisplayName("3. 收款地址解析失败 → 支付 FAILED，不调用签名服务")
    void invalidPayeeAddress_failsPayment() {
        when(walletMgmt.addressToPubkeyHash("0xPayee_pay_1")).thenReturn(null);

        ChainConnector connector = newConnector();
        ConnectorPaymentResult result = connector.createPayment(paymentRequest("pay_1", 50000L));

        assertFalse(result.isSuccess(), "无效地址应失败");
        assertEquals(PaymentStatus.FAILED, result.getStatus());
        assertNotNull(result.getErrorMessage(), "应有错误信息");
        verify(signing, never()).signTransfer(anyString(), anyString(), any());
    }

    @Test
    @Order(4)
    @DisplayName("4. 签名服务故障 → FAILED；恢复后重试 → 成功")
    void signingServiceFailure_thenRecovery() {
        when(walletMgmt.addressToPubkeyHash("0xPayee_pay_1")).thenReturn("payeeHash");
        when(walletMgmt.addressToPubkeyHash("0xPayer_pay_1")).thenReturn("payerHash");
        // 第一次签名故障（null），第二次恢复
        when(signing.signTransfer(anyString(), anyString(), any()))
                .thenReturn(null)
                .thenReturn("0xrecoveredTx");

        ChainConnector connector = newConnector();

        // 第一次：故障
        ConnectorPaymentResult r1 = connector.createPayment(paymentRequest("pay_1", 50000L));
        assertFalse(r1.isSuccess(), "签名故障应导致支付失败");

        // 第二次：恢复
        ConnectorPaymentResult r2 = connector.createPayment(paymentRequest("pay_1", 50000L));
        assertTrue(r2.isSuccess(), "签名恢复后应成功");
        assertEquals("0xrecoveredTx", r2.getTransactionHash());
    }

    @Test
    @Order(5)
    @DisplayName("5. 链上确认轮询：未确认→确认状态转换 + 确认后缓存")
    void chainConfirmationPolling_stateTransition() {
        when(walletMgmt.addressToPubkeyHash("0xPayee_pay_1")).thenReturn("payeeHash");
        when(walletMgmt.addressToPubkeyHash("0xPayer_pay_1")).thenReturn("payerHash");
        when(signing.signTransfer(anyString(), anyString(), any())).thenReturn("0xpollTx");
        when(rpc.isTransactionConfirmed("0xpollTx"))
                .thenReturn(false)
                .thenReturn(false)
                .thenReturn(true);

        ChainConnector connector = newConnector();
        ConnectorPaymentResult created = connector.createPayment(paymentRequest("pay_1", 30000L));
        String id = created.getConnectorPaymentId();

        assertEquals(PaymentStatus.PROCESSING, connector.queryPayment(id), "t1: PROCESSING");
        assertEquals(PaymentStatus.PROCESSING, connector.queryPayment(id), "t2: PROCESSING");
        assertEquals(PaymentStatus.SUCCEEDED, connector.queryPayment(id), "t3: SUCCEEDED");
        assertEquals(PaymentStatus.SUCCEEDED, connector.queryPayment(id), "t4: 缓存 SUCCEEDED 不再查链");
        verify(rpc, times(3)).isTransactionConfirmed("0xpollTx");
    }

    @Test
    @Order(6)
    @DisplayName("6. 退款方向策略：默认退 payer，payer 未知 fallback 退 payee")
    void refundDirectionStrategy_payerThenPayeeFallback() {
        // --- 场景1: payer 已知 → 退给 payer ---
        when(walletMgmt.addressToPubkeyHash("0xPayee_pay_1")).thenReturn("payeeHash");
        when(walletMgmt.addressToPubkeyHash("0xPayer_pay_1")).thenReturn("payerHash");
        when(signing.signTransfer(eq(PLATFORM_PUBKEY), eq("payeeHash"), any())).thenReturn("0xpayTx");
        when(signing.signTransfer(eq(PLATFORM_PUBKEY), eq("payerHash"), any())).thenReturn("0xrefundToPayer");

        ChainConnector connector = newConnector();
        ConnectorPaymentResult created = connector.createPayment(paymentRequest("pay_1", 50000L));
        ConnectorRefundResult r1 = connector.refund(created.getConnectorPaymentId(), 50000L);
        assertTrue(r1.isSuccess(), "退款应成功");
        verify(signing).signTransfer(eq(PLATFORM_PUBKEY), eq("payerHash"), any());

        // --- 场景2: payer 未知 → fallback 退给 payee ---
        SigningServiceFeignClient signing2 = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient wallet2 = mock(WalletMgmtFeignClient.class);
        when(wallet2.addressToPubkeyHash("0xPayee_pay_1")).thenReturn("payeeHash2");
        when(wallet2.addressToPubkeyHash("0xPayer_pay_1")).thenReturn(null);
        when(signing2.signTransfer(eq(PLATFORM_PUBKEY), eq("payeeHash2"), any()))
                .thenReturn("0xpayTx2")
                .thenReturn("0xrefundToPayee");

        ChainConnector connector2 = new ChainConnector(rpc, signing2, wallet2, config);
        ConnectorPaymentResult created2 = connector2.createPayment(paymentRequest("pay_1", 50000L));
        assertTrue(created2.isSuccess(), "创建应成功（payer 未知不影响创建）");
        ConnectorRefundResult r2 = connector2.refund(created2.getConnectorPaymentId(), 50000L);
        assertTrue(r2.isSuccess(), "payer 未知时应 fallback 退给 payee");
        verify(signing2, times(2)).signTransfer(eq(PLATFORM_PUBKEY), eq("payeeHash2"), any());
    }

    @Test
    @Order(7)
    @DisplayName("7. 并发支付：10 笔并发，每笔独立 txHash")
    void concurrentPayments_independentTxHashes() throws InterruptedException {
        int n = 10;
        ChainConnector connector = newConnector();

        for (int i = 0; i < n; i++) {
            String payerAddr = "0xPayer_pay_" + i;
            String payeeAddr = "0xPayee_pay_" + i;
            when(walletMgmt.addressToPubkeyHash(payeeAddr)).thenReturn("payeeHash_" + i);
            when(walletMgmt.addressToPubkeyHash(payerAddr)).thenReturn("payerHash_" + i);
            when(signing.signTransfer(eq(PLATFORM_PUBKEY), eq("payeeHash_" + i), any()))
                    .thenReturn("0xconcurrentTx_" + i);
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(n);
        ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();

        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    ConnectorPaymentRequest req = paymentRequest("pay_" + idx, 10000L);
                    ConnectorPaymentResult r = connector.createPayment(req);
                    if (r.isSuccess()) {
                        results.put(req.getPaymentId(), r.getTransactionHash());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "所有并发支付应在 5s 内完成");
        pool.shutdown();

        assertEquals(n, results.size(), "应有 n 笔成功支付");
        long uniqueTx = results.values().stream().distinct().count();
        assertEquals(n, uniqueTx, "每笔支付应有唯一 txHash");
    }

    @Test
    @Order(8)
    @DisplayName("8. 链上 RPC 故障 → queryPayment 降级返回 PROCESSING 不崩溃")
    void chainRpcFailure_queryPaymentDegradesToProcessing() {
        when(walletMgmt.addressToPubkeyHash("0xPayee_pay_1")).thenReturn("payeeHash");
        when(walletMgmt.addressToPubkeyHash("0xPayer_pay_1")).thenReturn("payerHash");
        when(signing.signTransfer(anyString(), anyString(), any())).thenReturn("0xrpcFailTx");
        when(rpc.isTransactionConfirmed("0xrpcFailTx"))
                .thenThrow(new RuntimeException("RPC timeout"));

        ChainConnector connector = newConnector();
        ConnectorPaymentResult created = connector.createPayment(paymentRequest("pay_1", 50000L));
        PaymentStatus status = connector.queryPayment(created.getConnectorPaymentId());
        assertEquals(PaymentStatus.PROCESSING, status, "RPC 故障应降级为 PROCESSING 而非崩溃");
    }

    @Test
    @Order(9)
    @DisplayName("9. healthCheck：节点健康/故障")
    void healthCheck_nodeHealthyAndDown() {
        when(rpc.getBlockHeight()).thenReturn(12345L);
        ChainConnector connector = newConnector();
        ConnectorHealth healthy = connector.healthCheck();
        assertTrue(healthy.isHealthy(), "正区块高度应健康");
        assertEquals("chain", healthy.getConnectorId());
        assertTrue(healthy.getLatencyMs() >= 0, "延迟应非负");

        when(rpc.getBlockHeight()).thenThrow(new RuntimeException("node unreachable"));
        ConnectorHealth down = connector.healthCheck();
        assertFalse(down.isHealthy(), "RPC 异常应标记不健康");
        assertNotNull(down.getMessage(), "应有故障原因");
        assertTrue(down.getMessage().contains("unreachable"), "故障信息应包含原因");
    }
}