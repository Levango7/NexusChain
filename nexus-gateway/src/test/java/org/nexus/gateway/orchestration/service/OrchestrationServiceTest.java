package org.nexus.gateway.orchestration.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.analytics.event.PaymentCompletedEvent;
import org.nexus.gateway.orchestration.connector.*;
import org.nexus.gateway.orchestration.model.OrchPaymentStatus;
import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.nexus.gateway.orchestration.repository.OrchestratedPaymentRepository;
import org.nexus.gateway.orchestration.routing.RoutingEngine;
import org.nexus.gateway.orchestration.routing.ai.MetricsCollector;
import org.nexus.gateway.risk.PaymentRequest;
import org.nexus.gateway.risk.PaymentRiskService;
import org.nexus.gateway.risk.RiskDecision;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.InOrder;

/**
 * {@link OrchestrationService} 单元测试：覆盖幂等重放、风控拒绝、路由空、
 * 连接器成功/失败/异常、refreshStatus 终态/非终态、listPayments、事件发布等分支。
 */
@ExtendWith(MockitoExtension.class)
class OrchestrationServiceTest {

    @Mock private OrchestratedPaymentRepository repo;
    @Mock private RoutingEngine routingEngine;
    @Mock private ConnectorRegistry connectorRegistry;
    @Mock private OrchestrationIdempotencyStore idempotencyStore;
    @Mock private OrchestrationWebhookDispatcher webhookDispatcher;
    @Mock private PaymentRiskService riskService;
    @Mock private MetricsCollector metricsCollector;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private OrchestrationService service;

    @BeforeEach
    void setUp() {
        service = new OrchestrationService(repo, routingEngine, connectorRegistry,
                idempotencyStore, webhookDispatcher, riskService, null, metricsCollector,
                applicationEventPublisher);
    }

    // === createPayment: 幂等重放 ===

    @Test
    @DisplayName("createPayment: 相同 requestId 重放返回已存在支付")
    void createPayment_idempotentReplay() {
        OrchestratedPayment existing = samplePayment("pay_existing", OrchPaymentStatus.SUCCEEDED);
        when(idempotencyStore.checkDuplicate("req-1")).thenReturn("pay_existing");
        when(repo.findById("pay_existing")).thenReturn(Optional.of(existing));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "desc",
                "http://notify", null, null, "req-1");

        assertEquals("pay_existing", result.getId());
        verify(riskService, never()).evaluatePayment(any());
    }

    @Test
    @DisplayName("createPayment: 幂等命中但 repo 无记录则继续新建流程")
    void createPayment_idempotentHitButMissing() {
        when(idempotencyStore.checkDuplicate("req-2")).thenReturn("pay_ghost");
        when(repo.findById("pay_ghost")).thenReturn(Optional.empty());
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        PaymentConnector connector = mock(PaymentConnector.class);
        when(connector.getId()).thenReturn("chain");
        when(connector.createPayment(any())).thenReturn(
                ConnectorPaymentResult.ok("c-1", PaymentStatus.SUCCEEDED, "0xTx"));
        when(routingEngine.resolve("NEX", 1000, null)).thenReturn(List.of(connector));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, "req-2");

        assertEquals(OrchPaymentStatus.SUCCEEDED, result.getStatus());
    }

    // === createPayment: 风控拒绝 ===

    @Test
    @DisplayName("createPayment: 风控 REJECTED -> FAILED")
    void createPayment_riskRejected() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.REJECTED);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, null);

        assertEquals(OrchPaymentStatus.FAILED, result.getStatus());
        verify(routingEngine, never()).resolve(any(), anyLong(), any());
    }

    @Test
    @DisplayName("createPayment: 风控 FROZEN -> FAILED")
    void createPayment_riskFrozen() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.FROZEN);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, null);

        assertEquals(OrchPaymentStatus.FAILED, result.getStatus());
    }

    @Test
    @DisplayName("createPayment: 风控 PENDING_REVIEW 仍继续路由")
    void createPayment_riskPendingReview() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.PENDING_REVIEW);
        PaymentConnector connector = mock(PaymentConnector.class);
        when(connector.getId()).thenReturn("chain");
        when(connector.createPayment(any())).thenReturn(
                ConnectorPaymentResult.ok("c-1", PaymentStatus.PROCESSING, "0xTx"));
        when(routingEngine.resolve("NEX", 1000, null)).thenReturn(List.of(connector));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, null);

        assertEquals(OrchPaymentStatus.PROCESSING, result.getStatus());
    }

    // === createPayment: 路由空 ===

    @Test
    @DisplayName("createPayment: 路由返回空列表 -> FAILED")
    void createPayment_noConnectors() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        when(routingEngine.resolve("NEX", 1000, null)).thenReturn(List.of());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, null);

        assertEquals(OrchPaymentStatus.FAILED, result.getStatus());
    }

    // === createPayment: 连接器成功 ===

    @Test
    @DisplayName("createPayment: 首个连接器返回 SUCCEEDED -> SUCCEEDED + confirmedAt")
    void createPayment_firstConnectorSucceeded() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        PaymentConnector connector = mock(PaymentConnector.class);
        when(connector.getId()).thenReturn("chain");
        when(connector.createPayment(any())).thenReturn(
                ConnectorPaymentResult.ok("c-1", PaymentStatus.SUCCEEDED, "0xTx"));
        when(routingEngine.resolve("NEX", 1000, "chain")).thenReturn(List.of(connector));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                "http://notify", "chain", null, "req-3");

        assertEquals(OrchPaymentStatus.SUCCEEDED, result.getStatus());
        assertEquals("chain", result.getConnectorId());
        assertEquals("c-1", result.getConnectorPaymentId());
        assertEquals("0xTx", result.getTransactionHash());
        assertNotNull(result.getConfirmedAt());
        verify(idempotencyStore).record("req-3", result.getId());
        verify(webhookDispatcher).dispatch(any());
    }

    @Test
    @DisplayName("createPayment: 连接器返回 PROCESSING -> PROCESSING，无 confirmedAt")
    void createPayment_connectorProcessing() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        PaymentConnector connector = mock(PaymentConnector.class);
        when(connector.getId()).thenReturn("chain");
        when(connector.createPayment(any())).thenReturn(
                ConnectorPaymentResult.ok("c-1", PaymentStatus.PROCESSING, "0xTx"));
        when(routingEngine.resolve("NEX", 1000, null)).thenReturn(List.of(connector));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, null);

        assertEquals(OrchPaymentStatus.PROCESSING, result.getStatus());
        assertNull(result.getConfirmedAt());
    }

    // === createPayment: failover ===

    @Test
    @DisplayName("createPayment: 首连接器失败 -> failover 到第二个连接器成功")
    void createPayment_failover() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        PaymentConnector c1 = mock(PaymentConnector.class);
        when(c1.getId()).thenReturn("chain");
        when(c1.createPayment(any())).thenReturn(ConnectorPaymentResult.fail("down"));
        PaymentConnector c2 = mock(PaymentConnector.class);
        when(c2.getId()).thenReturn("consortium");
        when(c2.createPayment(any())).thenReturn(
                ConnectorPaymentResult.ok("c-2", PaymentStatus.PROCESSING, "0xTx2"));
        when(routingEngine.resolve("NEX", 1000, null)).thenReturn(List.of(c1, c2));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, null);

        assertEquals(OrchPaymentStatus.PROCESSING, result.getStatus());
        assertEquals("consortium", result.getConnectorId());
    }

    @Test
    @DisplayName("createPayment: 首连接器抛异常 -> failover 到第二个连接器成功")
    void createPayment_failoverOnException() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        PaymentConnector c1 = mock(PaymentConnector.class);
        when(c1.getId()).thenReturn("chain");
        when(c1.createPayment(any())).thenThrow(new RuntimeException("conn error"));
        PaymentConnector c2 = mock(PaymentConnector.class);
        when(c2.getId()).thenReturn("consortium");
        when(c2.createPayment(any())).thenReturn(
                ConnectorPaymentResult.ok("c-2", PaymentStatus.PROCESSING, "0xTx2"));
        when(routingEngine.resolve("NEX", 1000, null)).thenReturn(List.of(c1, c2));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, null);

        assertEquals(OrchPaymentStatus.PROCESSING, result.getStatus());
        assertEquals("consortium", result.getConnectorId());
    }

    @Test
    @DisplayName("createPayment: 所有连接器失败 -> FAILED")
    void createPayment_allConnectorsFail() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        PaymentConnector c1 = mock(PaymentConnector.class);
        when(c1.getId()).thenReturn("chain");
        when(c1.createPayment(any())).thenReturn(ConnectorPaymentResult.fail("down1"));
        PaymentConnector c2 = mock(PaymentConnector.class);
        when(c2.getId()).thenReturn("consortium");
        when(c2.createPayment(any())).thenReturn(ConnectorPaymentResult.fail("down2"));
        when(routingEngine.resolve("NEX", 1000, null)).thenReturn(List.of(c1, c2));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, null);

        assertEquals(OrchPaymentStatus.FAILED, result.getStatus());
    }

    @Test
    @DisplayName("createPayment: preferredConnector 非空 -> routingStrategy=explicit")
    void createPayment_explicitRouting() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        PaymentConnector connector = mock(PaymentConnector.class);
        when(connector.getId()).thenReturn("stripe");
        when(connector.createPayment(any())).thenReturn(
                ConnectorPaymentResult.ok("c-1", PaymentStatus.PROCESSING, "0xTx"));
        when(routingEngine.resolve("USD", 1000, "stripe")).thenReturn(List.of(connector));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.createPayment(100L, 1000, "USD", "d",
                null, "stripe", null, null);

        assertEquals("explicit", result.getRoutingStrategy());
    }

    // === createPayment: 延迟 / 成本采集 ===

    @Test
    @DisplayName("createPayment: connector 未回填 latency/cost 时 -> 外层兜底（计时 + feeBasisPoints）")
    void createPayment_metricsFallback() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        PaymentConnector connector = mock(PaymentConnector.class);
        when(connector.getId()).thenReturn("chain");
        when(connector.feeBasisPoints()).thenReturn(5);
        when(connector.createPayment(any())).thenAnswer(inv -> {
            // 模拟 connector 内部未填 latency/cost
            Thread.sleep(15);
            return ConnectorPaymentResult.ok("c-1", PaymentStatus.SUCCEEDED, "0xTx");
        });
        when(routingEngine.resolve("NEX", 1000, null)).thenReturn(List.of(connector));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, null);

        assertEquals(OrchPaymentStatus.SUCCEEDED, result.getStatus());
        // 验证 MetricsCollector.record 收到兜底值：success=true, latency>=15ms, cost=5bps
        ArgumentCaptor<Long> latCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> costCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(metricsCollector).record(eq("chain"), eq(true), latCaptor.capture(), costCaptor.capture());
        assertTrue(latCaptor.getValue() >= 10, "外层兜底延迟应 >= 10ms（实际 15ms）");
        assertEquals(5, costCaptor.getValue());
    }

    @Test
    @DisplayName("createPayment: connector 已回填 latency/cost 时 -> 不覆盖")
    void createPayment_metricsKeepConnectorValues() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        PaymentConnector connector = mock(PaymentConnector.class);
        when(connector.getId()).thenReturn("consortium");
        lenient().when(connector.feeBasisPoints()).thenReturn(2);
        when(connector.createPayment(any())).thenAnswer(inv -> {
            // connector 自身已测量延迟并报告
            return ConnectorPaymentResult.ok("c-1", PaymentStatus.SUCCEEDED, "0xTx")
                    .withLatencyMs(42L)
                    .withCostBps(7); // 注意：7 != feeBasisPoints(2)，应被保留
        });
        when(routingEngine.resolve("NEX", 1000, null)).thenReturn(List.of(connector));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, null);

        assertEquals(OrchPaymentStatus.SUCCEEDED, result.getStatus());
        verify(metricsCollector).record(eq("consortium"), eq(true), eq(42L), eq(7));
    }

    @Test
    @DisplayName("createPayment: connector 失败时 MetricsCollector 收到 success=false")
    void createPayment_metricsRecordFailure() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        PaymentConnector connector = mock(PaymentConnector.class);
        when(connector.getId()).thenReturn("chain");
        when(connector.feeBasisPoints()).thenReturn(5);
        when(connector.createPayment(any())).thenReturn(ConnectorPaymentResult.fail("down"));
        PaymentConnector fallback = mock(PaymentConnector.class);
        when(fallback.getId()).thenReturn("consortium");
        when(fallback.feeBasisPoints()).thenReturn(2);
        when(fallback.createPayment(any())).thenReturn(
                ConnectorPaymentResult.ok("c-2", PaymentStatus.SUCCEEDED, "0xTx2"));
        when(routingEngine.resolve("NEX", 1000, null)).thenReturn(List.of(connector, fallback));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, null);

        assertEquals(OrchPaymentStatus.SUCCEEDED, result.getStatus());
        // 第一次调用：chain 失败
        InOrder order = inOrder(metricsCollector);
        order.verify(metricsCollector).record(eq("chain"), eq(false), anyLong(), eq(5));
        order.verify(metricsCollector).record(eq("consortium"), eq(true), anyLong(), eq(2));
    }

    @Test
    @DisplayName("createPayment: connector 抛异常时 MetricsCollector 仍记录 failure")
    void createPayment_metricsRecordException() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        PaymentConnector c1 = mock(PaymentConnector.class);
        when(c1.getId()).thenReturn("chain");
        lenient().when(c1.feeBasisPoints()).thenReturn(5);
        when(c1.createPayment(any())).thenThrow(new RuntimeException("conn error"));
        PaymentConnector c2 = mock(PaymentConnector.class);
        when(c2.getId()).thenReturn("consortium");
        when(c2.feeBasisPoints()).thenReturn(2);
        when(c2.createPayment(any())).thenReturn(
                ConnectorPaymentResult.ok("c-2", PaymentStatus.SUCCEEDED, "0xTx2"));
        when(routingEngine.resolve("NEX", 1000, null)).thenReturn(List.of(c1, c2));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, null);

        assertEquals(OrchPaymentStatus.SUCCEEDED, result.getStatus());
        verify(metricsCollector).record(eq("chain"), eq(false), anyLong(), eq(0));
        verify(metricsCollector).record(eq("consortium"), eq(true), anyLong(), eq(2));
    }

    @Test
    @DisplayName("createPayment: MetricsCollector 抛异常不影响主流程")
    void createPayment_metricsCollectorExceptionSwallowed() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        PaymentConnector connector = mock(PaymentConnector.class);
        when(connector.getId()).thenReturn("chain");
        when(connector.feeBasisPoints()).thenReturn(5);
        when(connector.createPayment(any())).thenReturn(
                ConnectorPaymentResult.ok("c-1", PaymentStatus.SUCCEEDED, "0xTx"));
        when(routingEngine.resolve("NEX", 1000, null)).thenReturn(List.of(connector));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("metrics down"))
                .when(metricsCollector).record(anyString(), anyBoolean(), anyLong(), anyInt());

        // 主流程不应被指标采集异常打断
        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, null);

        assertEquals(OrchPaymentStatus.SUCCEEDED, result.getStatus());
        assertEquals("chain", result.getConnectorId());
    }

    // === createPayment: 事件发布 ===

    @Test
    @DisplayName("createPayment: 成功路径发布 PaymentCompletedEvent，含 latencyMs/costBps")
    void createPayment_success_publishesAnalyticsEvent() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        PaymentConnector connector = mock(PaymentConnector.class);
        when(connector.getId()).thenReturn("chain");
        lenient().when(connector.feeBasisPoints()).thenReturn(5);
        when(connector.createPayment(any())).thenReturn(
                ConnectorPaymentResult.ok("c-1", PaymentStatus.SUCCEEDED, "0xTx")
                        .withLatencyMs(42L).withCostBps(8));
        when(routingEngine.resolve("NEX", 1000, null)).thenReturn(List.of(connector));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, null);

        assertEquals(OrchPaymentStatus.SUCCEEDED, result.getStatus());

        ArgumentCaptor<PaymentCompletedEvent> captor = ArgumentCaptor.forClass(PaymentCompletedEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        PaymentCompletedEvent event = captor.getValue();
        assertEquals(new java.math.BigDecimal("1000"), event.getAmount());
        assertEquals("chain", event.getConnector());
        assertEquals("0xTx", event.getChainTxHash());
        assertEquals(42L, event.getLatencyMs());
        assertEquals(8, event.getCostBps());
    }

    @Test
    @DisplayName("createPayment: 失败路径不发布 PaymentCompletedEvent")
    void createPayment_failure_doesNotPublishAnalyticsEvent() {
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.REJECTED);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createPayment(100L, 1000, "NEX", "d", null, null, null, null);

        verify(applicationEventPublisher, never()).publishEvent(any(PaymentCompletedEvent.class));
    }

    // === getPayment / listPayments ===

    @Test
    @DisplayName("getPayment: 存在返回实体，不存在返回 null")
    void getPayment() {
        when(repo.findById("pay-1")).thenReturn(Optional.of(samplePayment("pay-1", OrchPaymentStatus.SUCCEEDED)));
        assertNotNull(service.getPayment("pay-1"));

        when(repo.findById("pay-2")).thenReturn(Optional.empty());
        assertNull(service.getPayment("pay-2"));
    }

    @Test
    @DisplayName("listPayments: 带 status 走 findByMerchantIdAndStatus")
    void listPayments_withStatus() {
        Page<OrchestratedPayment> page = new PageImpl<>(List.of());
        when(repo.findByMerchantIdAndStatus(eq(100L), eq(OrchPaymentStatus.SUCCEEDED), any()))
                .thenReturn(page);
        Page<OrchestratedPayment> result = service.listPayments(100L, "SUCCEEDED", 0, 10);
        assertNotNull(result);
    }

    @Test
    @DisplayName("listPayments: 不带 status 走 findByMerchantId")
    void listPayments_withoutStatus() {
        Page<OrchestratedPayment> page = new PageImpl<>(List.of());
        when(repo.findByMerchantId(eq(100L), any())).thenReturn(page);
        Page<OrchestratedPayment> result = service.listPayments(100L, null, 0, 10);
        assertNotNull(result);
    }

    @Test
    @DisplayName("listPayments: 空白 status 走 findByMerchantId")
    void listPayments_blankStatus() {
        Page<OrchestratedPayment> page = new PageImpl<>(List.of());
        when(repo.findByMerchantId(eq(100L), any())).thenReturn(page);
        Page<OrchestratedPayment> result = service.listPayments(100L, "  ", 0, 10);
        assertNotNull(result);
    }

    // === refreshStatus ===

    @Test
    @DisplayName("refreshStatus: 不存在返回 null")
    void refreshStatus_notFound() {
        when(repo.findById("pay-x")).thenReturn(Optional.empty());
        assertNull(service.refreshStatus("pay-x"));
    }

    @Test
    @DisplayName("refreshStatus: SUCCEEDED 终态直接返回，不查连接器")
    void refreshStatus_terminalSucceeded() {
        OrchestratedPayment p = samplePayment("pay-1", OrchPaymentStatus.SUCCEEDED);
        when(repo.findById("pay-1")).thenReturn(Optional.of(p));

        OrchestratedPayment result = service.refreshStatus("pay-1");
        assertEquals(OrchPaymentStatus.SUCCEEDED, result.getStatus());
        verify(connectorRegistry, never()).get(any());
    }

    @Test
    @DisplayName("refreshStatus: FAILED 终态直接返回")
    void refreshStatus_terminalFailed() {
        OrchestratedPayment p = samplePayment("pay-1", OrchPaymentStatus.FAILED);
        when(repo.findById("pay-1")).thenReturn(Optional.of(p));

        OrchestratedPayment result = service.refreshStatus("pay-1");
        assertEquals(OrchPaymentStatus.FAILED, result.getStatus());
    }

    @Test
    @DisplayName("refreshStatus: 连接器不存在直接返回原状态")
    void refreshStatus_connectorMissing() {
        OrchestratedPayment p = samplePayment("pay-1", OrchPaymentStatus.PROCESSING);
        p.setConnectorId("gone");
        when(repo.findById("pay-1")).thenReturn(Optional.of(p));
        when(connectorRegistry.get("gone")).thenReturn(Optional.empty());

        OrchestratedPayment result = service.refreshStatus("pay-1");
        assertEquals(OrchPaymentStatus.PROCESSING, result.getStatus());
    }

    @Test
    @DisplayName("refreshStatus: 连接器查询 SUCCEEDED -> 更新 + webhook")
    void refreshStatus_connectorSucceeded() {
        OrchestratedPayment p = samplePayment("pay-1", OrchPaymentStatus.PROCESSING);
        p.setConnectorId("chain");
        p.setConnectorPaymentId("c-1");
        when(repo.findById("pay-1")).thenReturn(Optional.of(p));
        PaymentConnector connector = mock(PaymentConnector.class);
        when(connectorRegistry.get("chain")).thenReturn(Optional.of(connector));
        when(connector.queryPayment("c-1")).thenReturn(PaymentStatus.SUCCEEDED);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.refreshStatus("pay-1");
        assertEquals(OrchPaymentStatus.SUCCEEDED, result.getStatus());
        assertNotNull(result.getConfirmedAt());
        verify(webhookDispatcher).dispatch(any());
    }

    @Test
    @DisplayName("refreshStatus: 连接器查询 PROCESSING -> 状态保持")
    void refreshStatus_connectorProcessing() {
        OrchestratedPayment p = samplePayment("pay-1", OrchPaymentStatus.PROCESSING);
        p.setConnectorId("chain");
        p.setConnectorPaymentId("c-1");
        when(repo.findById("pay-1")).thenReturn(Optional.of(p));
        PaymentConnector connector = mock(PaymentConnector.class);
        when(connectorRegistry.get("chain")).thenReturn(Optional.of(connector));
        when(connector.queryPayment("c-1")).thenReturn(PaymentStatus.PROCESSING);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrchestratedPayment result = service.refreshStatus("pay-1");
        assertEquals(OrchPaymentStatus.PROCESSING, result.getStatus());
        verify(webhookDispatcher).dispatch(any());
    }

    private OrchestratedPayment samplePayment(String id, OrchPaymentStatus status) {
        OrchestratedPayment p = new OrchestratedPayment();
        p.setId(id);
        p.setMerchantId(100L);
        p.setAmount(1000L);
        p.setCurrency("NEX");
        p.setStatus(status);
        p.setCreatedAt(Instant.now());
        return p;
    }
}
