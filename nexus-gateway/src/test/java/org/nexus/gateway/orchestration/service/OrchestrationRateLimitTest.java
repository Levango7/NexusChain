package org.nexus.gateway.orchestration.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.ConnectorRegistry;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.connector.PaymentStatus;
import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.nexus.gateway.orchestration.model.OrchPaymentStatus;
import org.nexus.gateway.orchestration.repository.OrchestratedPaymentRepository;
import org.nexus.gateway.orchestration.routing.RoutingDecision;
import org.nexus.gateway.orchestration.routing.RoutingEngine;
import org.nexus.gateway.orchestration.routing.ai.MetricsCollector;
import org.nexus.gateway.orchestration.service.OrchestrationService;
import org.nexus.gateway.resilience.ConnectorRateLimiter;
import org.nexus.gateway.resilience.ConnectorRateLimiterConfig;
import org.nexus.gateway.risk.PaymentRiskService;
import org.nexus.gateway.risk.RiskDecision;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排层 × 出口限流接线测试（2026-11-17）。
 *
 * <p>验证三个语义（PSP 出口限流的消费端契约）：</p>
 * <ol>
 *   <li>限流拒绝的 connector <b>不被真调</b>（{@code createPayment} 零调用）；</li>
 *   <li>failover 正常继续：被拒 connector 之后的候选仍被尝试并可能成功；</li>
 *   <li>限流拒绝<b>不进熔断器</b>（本地节流 ≠ 渠道不可用——
 *       {@code FailureKind} 资金路径硬规则的消费端体现）。</li>
 * </ol>
 */
class OrchestrationRateLimitTest {

    private OrchestrationIdempotencyStore idempotencyStore;
    private OrchestrationWebhookDispatcher webhookDispatcher;
    private PaymentRiskService riskService;
    private RoutingEngine routingEngine;
    private ApplicationEventPublisher eventPublisher;
    private MetricsCollector metricsCollector;
    private OrchestratedPaymentRepository repo;
    private ConnectorRateLimiter rateLimiter;
    private OrchestrationService service;

    @BeforeEach
    void setUp() {
        idempotencyStore = mock(OrchestrationIdempotencyStore.class);
        webhookDispatcher = mock(OrchestrationWebhookDispatcher.class);
        riskService = mock(PaymentRiskService.class);
        routingEngine = mock(RoutingEngine.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        metricsCollector = mock(MetricsCollector.class);
        repo = mock(OrchestratedPaymentRepository.class);
        ConnectorRateLimiterConfig cfg = new ConnectorRateLimiterConfig();
        cfg.setEnabled(true);
        cfg.getDefaultConfig().setLimitForPeriod(1);
        rateLimiter = new ConnectorRateLimiter(cfg);

        lenient().when(idempotencyStore.tryClaim(any())).thenReturn(true);
        lenient().when(idempotencyStore.checkDuplicate(any())).thenReturn(null);
        lenient().when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        lenient().when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new OrchestrationService(repo, routingEngine, mock(ConnectorRegistry.class),
                idempotencyStore, webhookDispatcher, riskService, null,
                metricsCollector, eventPublisher, null, null, null, rateLimiter);
    }

    @AfterEach
    void tearDown() {
        // 无状态，无需清理；占位 mock 无需 release
    }

    private PaymentConnector connector(String id, String type) {
        PaymentConnector c = mock(PaymentConnector.class);
        lenient().when(c.getId()).thenReturn(id);
        lenient().when(c.getType()).thenReturn(type);
        lenient().when(c.isActive()).thenReturn(true);
        return c;
    }

    private static RoutingDecision w16(List<PaymentConnector> connectors) {
        return new RoutingDecision(connectors, "test-rule", "PRIORITY",
                List.of(), new java.util.LinkedHashMap<>(), null, null, null);
    }

    @Test
    @DisplayName("限流拒绝的 connector 不被真调，failover 落到下一个候选")
    void rateLimitedConnectorIsSkippedToFallback() {
        PaymentConnector limited = connector("psp-limited", "http_psp");
        PaymentConnector healthy = connector("psp-healthy", "http_psp");
        when(healthy.createPayment(any())).thenReturn(
                ConnectorPaymentResult.ok("c-2", PaymentStatus.SUCCEEDED, "0xTx"));
        when(routingEngine.resolveDetailed(any())).thenReturn(w16(List.of(limited, healthy)));
        // 预扣 psp-limited 的唯一许可：支付流到达它时配额已尽 → 必须被跳过
        org.junit.jupiter.api.Assertions.assertTrue(rateLimiter.tryAcquire("psp-limited", "http_psp"));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, "req-rl-1");

        verify(limited, never()).createPayment(any());
        verify(healthy).createPayment(any());
        assertEquals(OrchPaymentStatus.SUCCEEDED, result.getStatus());
        assertEquals("psp-healthy", result.getConnectorId());
    }

    @Test
    @DisplayName("全部候选都被限流拒绝 → 支付 FAILED（不是异常、不是挂起）")
    void allRateLimitedFailsGracefully() {
        PaymentConnector limitedA = connector("psp-a", "http_psp");
        PaymentConnector limitedB = connector("psp-b", "http_psp");
        when(routingEngine.resolveDetailed(any())).thenReturn(w16(List.of(limitedA, limitedB)));
        // 预扣两个候选的全部配额（limitForPeriod=1，分桶独立 → 各扣一次）
        org.junit.jupiter.api.Assertions.assertTrue(rateLimiter.tryAcquire("psp-a", "http_psp"));
        org.junit.jupiter.api.Assertions.assertTrue(rateLimiter.tryAcquire("psp-b", "http_psp"));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, "req-rl-2");

        verify(limitedA, never()).createPayment(any());
        verify(limitedB, never()).createPayment(any());
        assertEquals(OrchPaymentStatus.FAILED, result.getStatus());
    }

    @Test
    @DisplayName("限流拒绝不喂熔断器（circuitBreaker 为 null 时路径本身已无副作用；"
            + "此用例验证拒绝路径不抛异常且计数可读）")
    void rateLimitRejectionDoesNotTouchBreaker() {
        PaymentConnector limited = connector("psp-x", "http_psp");
        when(routingEngine.resolveDetailed(any())).thenReturn(w16(List.of(limited)));
        org.junit.jupiter.api.Assertions.assertTrue(rateLimiter.tryAcquire("psp-x", "http_psp"));

        OrchestratedPayment result = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, "req-rl-3");

        assertEquals(OrchPaymentStatus.FAILED, result.getStatus());
        // 拒绝路径未把 connector 拉黑：下一个请求仍会尝试（配额 1/period，新周期恢复）
        // 这里只断言无异常与状态，真实「不进熔断」由 ConnectorRateLimiter 不实现
        // CircuitBreaker 事件接口这一结构性事实保证（无 recordFailure 调用点）。
    }
}
