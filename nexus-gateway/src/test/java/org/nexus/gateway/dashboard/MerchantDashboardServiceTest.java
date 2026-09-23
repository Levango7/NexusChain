package org.nexus.gateway.dashboard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.MerchantService;
import org.nexus.gateway.clearing.SettlementPeriod;
import org.nexus.gateway.dashboard.MerchantDashboardService.*;
import org.nexus.gateway.model.Merchant;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.orchestration.connector.ConnectorRegistry;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.gateway.risk.RiskEvent;
import org.nexus.gateway.risk.RiskEventRepository;
import org.nexus.gateway.settlement.MerchantSettlementConfig;
import org.nexus.gateway.settlement.MerchantSettlementConfigRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link MerchantDashboardService} 单元测试。
 *
 * <p>使用 Mockito mock 所有依赖，覆盖交易汇总、结算汇总、近期交易、
 * 渠道分布、风控摘要和完整仪表盘等场景。</p>
 */
@ExtendWith(MockitoExtension.class)
class MerchantDashboardServiceTest {

    @Mock private PaymentOrderRepository paymentOrderRepository;
    @Mock private RiskEventRepository riskEventRepository;
    @Mock private MerchantService merchantService;
    @Mock private ConnectorRegistry connectorRegistry;
    @Mock private MerchantSettlementConfigRepository settlementConfigRepository;

    private MerchantDashboardService dashboardService;

    private static final Long MERCHANT_ID = 100L;

    @BeforeEach
    void setUp() {
        dashboardService = new MerchantDashboardService(
                paymentOrderRepository,
                riskEventRepository,
                merchantService,
                connectorRegistry,
                settlementConfigRepository
        );
    }

    // ==================== 1. getDashboard - 商户不存在时返回空数据 ====================

    @Test
    @DisplayName("getDashboard：商户不存在时返回空数据")
    void getDashboard_merchantNotFound_returnsEmptyData() {
        when(merchantService.findById(MERCHANT_ID)).thenReturn(Optional.empty());

        DashboardData data = dashboardService.getDashboard(MERCHANT_ID);

        assertNotNull(data);
        assertNotNull(data.getTransactionSummary());
        assertNotNull(data.getSettlementSummary());
        assertNotNull(data.getRecentTransactions());
        assertNotNull(data.getConnectorDistribution());
        assertNotNull(data.getRiskSummary());

        assertEquals(0, data.getTransactionSummary().getTotalCount());
        assertEquals(BigDecimal.ZERO, data.getTransactionSummary().getTotalVolume());
        assertTrue(data.getRecentTransactions().isEmpty());
        assertTrue(data.getConnectorDistribution().isEmpty());
        assertEquals(0, data.getRiskSummary().getTotalRiskEvents());
    }

    // ==================== 2. getTransactionSummary - 有 PAID 订单时正确计算总额 ====================

    @Test
    @DisplayName("getTransactionSummary：有 PAID 订单时正确计算总额")
    void getTransactionSummary_paidOrders_correctTotalVolume() {

        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(
                        createOrder(PaymentOrder.OrderStatus.PAID, new BigDecimal("1000")),
                        createOrder(PaymentOrder.OrderStatus.PAID, new BigDecimal("2000")),
                        createOrder(PaymentOrder.OrderStatus.PENDING, new BigDecimal("500"))
                ));

        TransactionSummary summary = dashboardService.getTransactionSummary(MERCHANT_ID);

        assertEquals(new BigDecimal("3000"), summary.getTotalVolume());
        assertEquals(3, summary.getTotalCount());
        assertEquals(2, summary.getSuccessCount());
    }

    // ==================== 3. getTransactionSummary - 无订单时返回零值 ====================

    @Test
    @DisplayName("getTransactionSummary：无订单时返回零值")
    void getTransactionSummary_noOrders_returnsZero() {
        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Collections.emptyList());

        TransactionSummary summary = dashboardService.getTransactionSummary(MERCHANT_ID);

        assertEquals(BigDecimal.ZERO, summary.getTotalVolume());
        assertEquals(0, summary.getTotalCount());
        assertEquals(0, summary.getSuccessCount());
        assertEquals(BigDecimal.ZERO, summary.getSuccessRate());
        assertEquals(0, summary.getPendingCount());
        assertEquals(0, summary.getFailedCount());
        assertEquals(0, summary.getRefundedCount());
    }

    // ==================== 4. getTransactionSummary - 成功率计算正确 ====================

    @Test
    @DisplayName("getTransactionSummary：成功率计算正确")
    void getTransactionSummary_successRateCorrect() {
        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(
                        createOrder(PaymentOrder.OrderStatus.PAID, new BigDecimal("100")),
                        createOrder(PaymentOrder.OrderStatus.PAID, new BigDecimal("100")),
                        createOrder(PaymentOrder.OrderStatus.PAID, new BigDecimal("100")),
                        createOrder(PaymentOrder.OrderStatus.FAILED, new BigDecimal("100")),
                        createOrder(PaymentOrder.OrderStatus.EXPIRED, new BigDecimal("100"))
                ));

        TransactionSummary summary = dashboardService.getTransactionSummary(MERCHANT_ID);

        // 3 PAID / 5 total * 100 = 60.00
        assertEquals(5, summary.getTotalCount());
        assertEquals(3, summary.getSuccessCount());
        assertEquals(new BigDecimal("60.00"), summary.getSuccessRate());
    }

    // ==================== 5. getTransactionSummary - 各状态计数正确 ====================

    @Test
    @DisplayName("getTransactionSummary：各状态计数正确")
    void getTransactionSummary_statusCountsCorrect() {
        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(
                        createOrder(PaymentOrder.OrderStatus.PAID, new BigDecimal("100")),
                        createOrder(PaymentOrder.OrderStatus.PENDING, new BigDecimal("100")),
                        createOrder(PaymentOrder.OrderStatus.PAYING, new BigDecimal("100")),
                        createOrder(PaymentOrder.OrderStatus.SUBMITTED, new BigDecimal("100")),
                        createOrder(PaymentOrder.OrderStatus.FAILED, new BigDecimal("100")),
                        createOrder(PaymentOrder.OrderStatus.EXPIRED, new BigDecimal("100")),
                        createOrder(PaymentOrder.OrderStatus.REFUNDED, new BigDecimal("100"))
                ));

        TransactionSummary summary = dashboardService.getTransactionSummary(MERCHANT_ID);

        assertEquals(7, summary.getTotalCount());
        assertEquals(1, summary.getSuccessCount());     // PAID
        assertEquals(3, summary.getPendingCount());     // PENDING + PAYING + SUBMITTED
        assertEquals(2, summary.getFailedCount());      // FAILED + EXPIRED
        assertEquals(1, summary.getRefundedCount());    // REFUNDED
    }

    // ==================== 6. getSettlementSummary - 返回结算周期信息 ====================

    @Test
    @DisplayName("getSettlementSummary：返回结算周期信息")
    void getSettlementSummary_returnsSettlementPeriod() {
        MerchantSettlementConfig config = new MerchantSettlementConfig();
        config.setSettlementPeriod(SettlementPeriod.T2);
        when(settlementConfigRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(config));
        when(paymentOrderRepository.findByMerchantIdAndStatus(MERCHANT_ID, PaymentOrder.OrderStatus.PAID))
                .thenReturn(List.of(createOrder(PaymentOrder.OrderStatus.PAID, new BigDecimal("5000"))));
        when(paymentOrderRepository.findByMerchantIdAndStatus(MERCHANT_ID, PaymentOrder.OrderStatus.REFUNDED))
                .thenReturn(Collections.emptyList());

        SettlementSummary summary = dashboardService.getSettlementSummary(MERCHANT_ID);

        assertEquals("T2", summary.getSettlementPeriod());
        assertEquals(new BigDecimal("5000"), summary.getPendingSettlementAmount());
        assertEquals(BigDecimal.ZERO, summary.getSettledAmount());
    }

    // ==================== 7. getRecentTransactions - 返回最近 10 笔交易 ====================

    @Test
    @DisplayName("getRecentTransactions：返回最近 10 笔交易")
    void getRecentTransactions_returnsLatest10() {
        List<PaymentOrder> orders = new ArrayList<>();
        LocalDateTime baseTime = LocalDateTime.now();
        for (int i = 0; i < 15; i++) {
            PaymentOrder order = createOrder(PaymentOrder.OrderStatus.PAID, new BigDecimal("100"));
            order.setOrderNo("ORD-" + String.format("%03d", i));
            order.setCreatedAt(baseTime.minusMinutes(i)); // 越早创建的 i 越大
            orders.add(order);
        }

        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(orders);

        List<RecentTransaction> recent = dashboardService.getRecentTransactions(MERCHANT_ID);

        assertEquals(10, recent.size());
        // 最新的交易应该排在前面（createdAt 降序）
        assertEquals("ORD-000", recent.get(0).getOrderNo());
        assertEquals("ORD-009", recent.get(9).getOrderNo());
    }

    // ==================== 8. getRecentTransactions - 无交易时返回空列表 ====================

    @Test
    @DisplayName("getRecentTransactions：无交易时返回空列表")
    void getRecentTransactions_noTransactions_returnsEmptyList() {
        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Collections.emptyList());

        List<RecentTransaction> recent = dashboardService.getRecentTransactions(MERCHANT_ID);

        assertNotNull(recent);
        assertTrue(recent.isEmpty());
    }

    // ==================== 9. getConnectorDistribution - 多渠道分布统计正确 ====================

    @Test
    @DisplayName("getConnectorDistribution：多渠道分布统计正确")
    void getConnectorDistribution_multipleConnectors_correctDistribution() {
        PaymentConnector connector1 = mock(PaymentConnector.class);
        when(connector1.getId()).thenReturn("chain");
        when(connector1.getDisplayName()).thenReturn("链上支付");
        when(connector1.getType()).thenReturn("chain");
        when(connector1.isActive()).thenReturn(true);

        PaymentConnector connector2 = mock(PaymentConnector.class);
        when(connector2.getId()).thenReturn("stripe");
        when(connector2.getDisplayName()).thenReturn("Stripe");
        when(connector2.getType()).thenReturn("http_psp");
        when(connector2.isActive()).thenReturn(true);

        PaymentConnector connector3 = mock(PaymentConnector.class);
        when(connector3.getId()).thenReturn("mock");
        when(connector3.getDisplayName()).thenReturn("模拟渠道");
        when(connector3.getType()).thenReturn("mock");
        when(connector3.isActive()).thenReturn(false);

        when(connectorRegistry.getAll())
                .thenReturn(List.of(connector1, connector2, connector3));

        List<ConnectorDistribution> distribution = dashboardService.getConnectorDistribution(MERCHANT_ID);

        assertEquals(3, distribution.size());

        ConnectorDistribution d1 = distribution.get(0);
        assertEquals("chain", d1.getConnectorId());
        assertEquals("链上支付", d1.getDisplayName());
        assertEquals("chain", d1.getType());
        assertTrue(d1.isActive());

        ConnectorDistribution d2 = distribution.get(1);
        assertEquals("stripe", d2.getConnectorId());
        assertEquals("Stripe", d2.getDisplayName());
        assertTrue(d2.isActive());

        ConnectorDistribution d3 = distribution.get(2);
        assertEquals("mock", d3.getConnectorId());
        assertFalse(d3.isActive());
    }

    // ==================== 10. getRiskSummary - 有风控事件时正确统计 ====================

    @Test
    @DisplayName("getRiskSummary：有风控事件时正确统计")
    void getRiskSummary_withEvents_correctStatistics() {
        RiskEvent event1 = createRiskEvent(80, "REJECTED");   // 高风险 + 拦截
        RiskEvent event2 = createRiskEvent(75, "APPROVED");   // 高风险
        RiskEvent event3 = createRiskEvent(30, "APPROVED");   // 低风险
        RiskEvent event4 = createRiskEvent(90, "REJECTED");   // 高风险 + 拦截
        RiskEvent event5 = createRiskEvent(50, "PENDING_REVIEW"); // 低风险

        when(riskEventRepository.findByMerchantIdOrderByOccurredAtDesc(MERCHANT_ID))
                .thenReturn(List.of(event1, event2, event3, event4, event5));

        RiskSummary summary = dashboardService.getRiskSummary(MERCHANT_ID);

        assertEquals(5, summary.getTotalRiskEvents());
        assertEquals(3, summary.getHighRiskEvents());     // riskScore >= 70: 80, 75, 90
        assertEquals(2, summary.getBlockedTransactions()); // REJECTED: event1, event4
    }

    // ==================== 11. getRiskSummary - 无风控事件时返回零值 ====================

    @Test
    @DisplayName("getRiskSummary：无风控事件时返回零值")
    void getRiskSummary_noEvents_returnsZero() {
        when(riskEventRepository.findByMerchantIdOrderByOccurredAtDesc(MERCHANT_ID))
                .thenReturn(Collections.emptyList());

        RiskSummary summary = dashboardService.getRiskSummary(MERCHANT_ID);

        assertEquals(0, summary.getTotalRiskEvents());
        assertEquals(0, summary.getHighRiskEvents());
        assertEquals(0, summary.getBlockedTransactions());
    }

    // ==================== 12. getDashboard - 完整仪表盘数据各字段非 null ====================

    @Test
    @DisplayName("getDashboard：完整仪表盘数据各字段非 null")
    void getDashboard_allFieldsNotNull() {
        Merchant merchant = createMerchant();
        when(merchantService.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(
                        createOrder(PaymentOrder.OrderStatus.PAID, new BigDecimal("1000")),
                        createOrder(PaymentOrder.OrderStatus.PENDING, new BigDecimal("500"))
                ));
        when(paymentOrderRepository.findByMerchantIdAndStatus(MERCHANT_ID, PaymentOrder.OrderStatus.PAID))
                .thenReturn(List.of(createOrder(PaymentOrder.OrderStatus.PAID, new BigDecimal("1000"))));
        when(paymentOrderRepository.findByMerchantIdAndStatus(MERCHANT_ID, PaymentOrder.OrderStatus.REFUNDED))
                .thenReturn(Collections.emptyList());

        when(settlementConfigRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.empty()); // 默认 T1

        PaymentConnector connector = mock(PaymentConnector.class);
        when(connector.getId()).thenReturn("chain");
        when(connector.getDisplayName()).thenReturn("链上支付");
        when(connector.getType()).thenReturn("chain");
        when(connector.isActive()).thenReturn(true);
        when(connectorRegistry.getAll()).thenReturn(List.of(connector));

        when(riskEventRepository.findByMerchantIdOrderByOccurredAtDesc(MERCHANT_ID))
                .thenReturn(List.of(createRiskEvent(85, "REJECTED")));

        DashboardData data = dashboardService.getDashboard(MERCHANT_ID);

        // 各字段非 null
        assertNotNull(data.getTransactionSummary());
        assertNotNull(data.getSettlementSummary());
        assertNotNull(data.getRecentTransactions());
        assertNotNull(data.getConnectorDistribution());
        assertNotNull(data.getRiskSummary());

        // 交易汇总数据正确
        assertEquals(2, data.getTransactionSummary().getTotalCount());
        assertEquals(1, data.getTransactionSummary().getSuccessCount());
        assertEquals(new BigDecimal("1000"), data.getTransactionSummary().getTotalVolume());

        // 结算汇总数据正确
        assertEquals("T1", data.getSettlementSummary().getSettlementPeriod());
        assertEquals(new BigDecimal("1000"), data.getSettlementSummary().getPendingSettlementAmount());

        // 近期交易列表非空
        assertFalse(data.getRecentTransactions().isEmpty());

        // 渠道分布列表非空
        assertFalse(data.getConnectorDistribution().isEmpty());
        assertEquals("chain", data.getConnectorDistribution().get(0).getConnectorId());

        // 风控摘要数据正确
        assertEquals(1, data.getRiskSummary().getTotalRiskEvents());
        assertEquals(1, data.getRiskSummary().getHighRiskEvents());
        assertEquals(1, data.getRiskSummary().getBlockedTransactions());
    }

    // ==================== 辅助方法 ====================

    private Merchant createMerchant() {
        Merchant merchant = new Merchant();
        merchant.setId(MERCHANT_ID);
        merchant.setMerchantCode("M100");
        merchant.setMerchantName("测试商户");
        merchant.setEmail("test@nexus.com");
        merchant.setSettlementAddress("0xSettlementAddress");
        return merchant;
    }

    private PaymentOrder createOrder(PaymentOrder.OrderStatus status, BigDecimal amount) {
        PaymentOrder order = new PaymentOrder();
        order.setId(System.nanoTime());
        order.setOrderNo("ORD-" + UUID.randomUUID().toString().substring(0, 8));
        order.setMerchantId(MERCHANT_ID);
        order.setStatus(status);
        order.setAmount(amount);
        order.setPayeeAddress("0xPayeeAddress");
        order.setCreatedAt(LocalDateTime.now());
        order.setExpiresAt(LocalDateTime.now().plusHours(1));
        return order;
    }

    private RiskEvent createRiskEvent(int riskScore, String riskDecision) {
        RiskEvent event = new RiskEvent();
        event.setId(System.nanoTime());
        event.setEventId(UUID.randomUUID().toString().replace("-", ""));
        event.setEventType(RiskEvent.EventType.PAYMENT_EVALUATION);
        event.setMerchantId(MERCHANT_ID);
        event.setRiskScore(riskScore);
        event.setRiskDecision(riskDecision);
        event.setOccurredAt(LocalDateTime.now());
        return event;
    }
}