package org.nexus.gateway.orchestration.service;

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
import org.nexus.common.tracing.BusinessSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.tracing.Tracer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Core orchestration service: creates payments, routes to connectors, handles failover.
 *
 * <p>P3-T5：在支付创建主链路添加业务 span（payment.create → payment.route →
 * payment.connector.submit → payment.webhook.notify），span 树结构见
 * docs/tracing-business-span.md。</p>
 *
 * <p><b>指标采集</b>：在每个 connector 调用前后用 {@link System#nanoTime()}
 * 计时（外层兜底），并结合 connector 自己回填的 {@code latencyMs / costBps}。
 * 任一字段为 0 时使用兜底值（外层计时 / {@code connector.feeBasisPoints()}）。
 * 最终值通过 {@link MetricsCollector#record} 喂给 AI 路由时间桶窗口（若 bean
 * 存在；AI 路由关闭时 MetricsCollector 未装配，本字段采集仅在 span 属性中可见）。</p>
 */
@Service
public class OrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationService.class);

    private final OrchestratedPaymentRepository repo;
    private final RoutingEngine routingEngine;
    private final ConnectorRegistry connectorRegistry;
    private final OrchestrationIdempotencyStore idempotencyStore;
    private final OrchestrationWebhookDispatcher webhookDispatcher;
    private final PaymentRiskService riskService;
    /** Micrometer Tracer：P3-T5 业务 span 注入。可为 null（测试环境降级 no-op）。 */
    private final Tracer tracer;
    /**
     * AI 路由指标采集器（可选）。仅当 {@code nexus.routing.ai.enabled=true} 时由
     * {@code AiRoutingConfiguration} 装配；关闭时本字段为 null，调用
     * {@link #recordConnectorOutcome} 会自动 no-op。
     */
    private final MetricsCollector metricsCollector;
    /** Spring 事件发布器。支付成功后发布 {@link PaymentCompletedEvent}，nexus-analytics 异步采集。 */
    private final ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    public OrchestrationService(OrchestratedPaymentRepository repo,
                                RoutingEngine routingEngine,
                                ConnectorRegistry connectorRegistry,
                                OrchestrationIdempotencyStore idempotencyStore,
                                OrchestrationWebhookDispatcher webhookDispatcher,
                                PaymentRiskService riskService,
                                Tracer tracer,
                                @Autowired(required = false) MetricsCollector metricsCollector,
                                ApplicationEventPublisher applicationEventPublisher) {
        this.repo = repo;
        this.routingEngine = routingEngine;
        this.connectorRegistry = connectorRegistry;
        this.idempotencyStore = idempotencyStore;
        this.webhookDispatcher = webhookDispatcher;
        this.riskService = riskService;
        this.tracer = tracer;
        this.metricsCollector = metricsCollector;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * 测试用兼容构造器：不注入 Tracer 与 MetricsCollector（业务 span 降级为 no-op，指标采集跳过）。
     */
    public OrchestrationService(OrchestratedPaymentRepository repo,
                                RoutingEngine routingEngine,
                                ConnectorRegistry connectorRegistry,
                                OrchestrationIdempotencyStore idempotencyStore,
                                OrchestrationWebhookDispatcher webhookDispatcher,
                                PaymentRiskService riskService) {
        this(repo, routingEngine, connectorRegistry, idempotencyStore,
                webhookDispatcher, riskService, null, null, null);
    }

    /**
     * 测试用兼容构造器：不注入 MetricsCollector（AI 路由关闭场景）。
     */
    public OrchestrationService(OrchestratedPaymentRepository repo,
                                RoutingEngine routingEngine,
                                ConnectorRegistry connectorRegistry,
                                OrchestrationIdempotencyStore idempotencyStore,
                                OrchestrationWebhookDispatcher webhookDispatcher,
                                PaymentRiskService riskService,
                                Tracer tracer) {
        this(repo, routingEngine, connectorRegistry, idempotencyStore,
                webhookDispatcher, riskService, tracer, null, null);
    }

    @Transactional
    public OrchestratedPayment createPayment(Long merchantId, long amount, String currency,
                                              String description, String notifyUrl,
                                              String preferredConnector, String metadata,
                                              String requestId) {
        // P3-T5：支付创建主 span（payment.create），覆盖整个支付全链路
        try (BusinessSpan rootSpan = BusinessSpan.start(tracer, "payment.create")
                .attr("payment.merchant.id", merchantId)
                .attr("payment.amount", amount)
                .attr("payment.currency", currency)
                .attr("payment.request.id", requestId)) {

            // Idempotency: replay an already-processed request_id instead of creating a new payment.
            if (requestId != null && !requestId.isBlank()) {
                String existingPaymentId = idempotencyStore.checkDuplicate(requestId);
                if (existingPaymentId != null) {
                    OrchestratedPayment existing = repo.findById(existingPaymentId).orElse(null);
                    if (existing != null) {
                        log.info("Idempotent replay: requestId={} -> existing paymentId={}",
                                requestId, existingPaymentId);
                        rootSpan.attr("payment.id", existingPaymentId)
                                .attr("payment.idempotent", true);
                        return existing;
                    }
                }
            }

            String paymentId = "pay_" + UUID.randomUUID().toString().replace("-", "");
            rootSpan.attr("payment.id", paymentId);

            // Risk gate: evaluate before routing to connectors. REJECTED/FROZEN -> FAILED immediately.
            PaymentRequest riskRequest = new PaymentRequest(
                    merchantId, null, BigDecimal.valueOf(amount), currency);
            riskRequest.setIdempotencyKey(requestId);
            RiskDecision riskDecision = riskService.evaluatePayment(riskRequest);

            OrchestratedPayment payment = new OrchestratedPayment();
            payment.setId(paymentId);
            payment.setMerchantId(merchantId);
            payment.setAmount(amount);
            payment.setCurrency(currency);
            payment.setDescription(description);
            payment.setNotifyUrl(notifyUrl);
            payment.setMetadata(metadata);
            payment.setRequestId(requestId);
            payment.setRoutingStrategy(preferredConnector != null ? "explicit" : "priority");

            if (riskDecision == RiskDecision.REJECTED || riskDecision == RiskDecision.FROZEN) {
                payment.setStatus(OrchPaymentStatus.FAILED);
                persist(requestId, payment);
                log.warn("Orchestrated payment rejected by risk control: paymentId={}, merchantId={}, decision={}",
                        paymentId, merchantId, riskDecision);
                rootSpan.attr("payment.status", "FAILED")
                        .attr("payment.risk.decision", riskDecision.name())
                        .error(null);
                return payment;
            }
            if (riskDecision == RiskDecision.PENDING_REVIEW) {
                log.info("Orchestrated payment flagged for manual risk review: paymentId={}, merchantId={}",
                        paymentId, merchantId);
                rootSpan.attr("payment.risk.decision", "PENDING_REVIEW");
            }

            payment.setStatus(OrchPaymentStatus.CREATED);

            // P3-T5：路由决策 span（payment.route）
            List<PaymentConnector> connectors;
            try (BusinessSpan routeSpan = BusinessSpan.start(tracer, "payment.route")
                    .attr("payment.id", paymentId)
                    .attr("payment.currency", currency)
                    .attr("payment.amount", amount)) {
                connectors = routingEngine.resolve(currency, amount, preferredConnector);
                routeSpan.attr("payment.route.strategy", payment.getRoutingStrategy())
                        .attr("payment.route.connectors", connectors.stream()
                                .map(PaymentConnector::getId).toList().toString());
            }

            if (connectors.isEmpty()) {
                payment.setStatus(OrchPaymentStatus.FAILED);
                persist(requestId, payment);
                log.warn("No connectors available for payment {}", paymentId);
                rootSpan.attr("payment.status", "FAILED").error(null);
                return payment;
            }

            // Try connectors in order (failover)
            ConnectorPaymentRequest req = new ConnectorPaymentRequest(paymentId, amount, currency, description);
            for (PaymentConnector connector : connectors) {
                // P3-T5：连接器提交 span（payment.connector.submit）
                try (BusinessSpan connSpan = BusinessSpan.start(tracer, "payment.connector.submit")
                        .attr("payment.id", paymentId)
                        .attr("payment.connector.id", connector.getId())) {
                    // 外层兜底计时（connector 自身可能未回填 latencyMs）
                    long t0 = System.nanoTime();
                    ConnectorPaymentResult result;
                    try {
                        result = connector.createPayment(req);
                    } catch (RuntimeException e) {
                        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                        log.error("Connector {} threw exception for payment {}: {}",
                                connector.getId(), paymentId, e.getMessage());
                        // 异常也喂给 AI 路由（成本 = 0，延迟 = 已耗时）
                        recordConnectorOutcome(connector.getId(), false, elapsedMs, 0);
                        connSpan.attr("payment.connector.latency_ms", elapsedMs)
                                .attr("payment.connector.error", e.getMessage())
                                .error(e);
                        continue;
                    }
                    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                    if (result.getLatencyMs() <= 0) {
                        result.setLatencyMs(elapsedMs);
                    }
                    if (result.getCostBps() <= 0) {
                        result.setCostBps(connector.feeBasisPoints());
                    }
                    connSpan.attr("payment.connector.latency_ms", result.getLatencyMs())
                            .attr("payment.connector.cost_bps", result.getCostBps());

                    if (result.isSuccess()) {
                        payment.setConnectorId(connector.getId());
                        payment.setConnectorPaymentId(result.getConnectorPaymentId());
                        payment.setTransactionHash(result.getTransactionHash());
                        payment.setStatus(mapStatus(result.getStatus()));
                        if (result.getStatus() == PaymentStatus.SUCCEEDED) {
                            payment.setConfirmedAt(Instant.now());
                        }
                        // 持久化延迟/成本可观测字段（用于历史审计与报表）
                        payment.setLatencyMs(result.getLatencyMs());
                        payment.setCostBps(result.getCostBps());
                        persist(requestId, payment);
                        log.info("Payment {} routed to connector {} -> status={} (latency={}ms, cost={}bps)",
                                paymentId, connector.getId(), result.getStatus(),
                                result.getLatencyMs(), result.getCostBps());
                        recordConnectorOutcome(connector.getId(), true, result.getLatencyMs(), result.getCostBps());
                        publishPaymentCompleted(payment, result.getTransactionHash(),
                                result.getLatencyMs(), result.getCostBps());
                        rootSpan.attr("payment.connector.id", connector.getId())
                                .attr("payment.status", payment.getStatus().name())
                                .attr("payment.connector.latency_ms", result.getLatencyMs())
                                .attr("payment.connector.cost_bps", result.getCostBps());
                        if (result.getTransactionHash() != null) {
                            rootSpan.attr("payment.tx.hash", result.getTransactionHash());
                        }
                        return payment;
                    }
                    log.warn("Connector {} rejected payment {}: {}",
                            connector.getId(), paymentId, result.getErrorMessage());
                    recordConnectorOutcome(connector.getId(), false, result.getLatencyMs(), result.getCostBps());
                    connSpan.attr("payment.connector.error", result.getErrorMessage());
                }
            }

            // All connectors failed
            payment.setStatus(OrchPaymentStatus.FAILED);
            persist(requestId, payment);
            log.error("All connectors failed for payment {}", paymentId);
            rootSpan.attr("payment.status", "FAILED").error(null);
            return payment;
        }
    }

    public OrchestratedPayment getPayment(String paymentId) {
        return repo.findById(paymentId).orElse(null);
    }

    public Page<OrchestratedPayment> listPayments(Long merchantId, String status, int page, int size) {
        PageRequest pr = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        if (status != null && !status.isBlank()) {
            return repo.findByMerchantIdAndStatus(merchantId, OrchPaymentStatus.valueOf(status.toUpperCase()), pr);
        }
        return repo.findByMerchantId(merchantId, pr);
    }

    @Transactional
    public OrchestratedPayment refreshStatus(String paymentId) {
        // P3-T5：支付状态刷新 span（payment.status.refresh）
        try (BusinessSpan span = BusinessSpan.start(tracer, "payment.status.refresh")
                .attr("payment.id", paymentId)) {
            OrchestratedPayment payment = repo.findById(paymentId).orElse(null);
            if (payment == null) {
                span.attr("payment.found", false);
                return null;
            }
            span.attr("payment.status", payment.getStatus().name());
            if (payment.getStatus() == OrchPaymentStatus.SUCCEEDED || payment.getStatus() == OrchPaymentStatus.FAILED) {
                return payment;
            }
            PaymentConnector connector = connectorRegistry.get(payment.getConnectorId()).orElse(null);
            if (connector == null) {
                span.attr("payment.connector.available", false);
                return payment;
            }

            long t0 = System.nanoTime();
            PaymentStatus status = connector.queryPayment(payment.getConnectorPaymentId());
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            payment.setStatus(mapStatus(status));
            if (status == PaymentStatus.SUCCEEDED && payment.getConfirmedAt() == null) {
                payment.setConfirmedAt(Instant.now());
            }
            OrchestratedPayment saved = repo.save(payment);
            span.attr("payment.status.new", saved.getStatus().name())
                    .attr("payment.connector.latency_ms", elapsedMs);
            webhookDispatcher.dispatch(saved);
            return saved;
        }
    }

    /**
     * Persist the payment within the surrounding transaction, record the idempotency
     * mapping (if a request_id was supplied), and dispatch the merchant webhook for
     * the resulting status. The webhook dispatch is asynchronous and de-duplicated
     * inside {@link OrchestrationWebhookDispatcher}.
     */
    private void persist(String requestId, OrchestratedPayment payment) {
        OrchestratedPayment saved = repo.save(payment);
        if (requestId != null && !requestId.isBlank()) {
            idempotencyStore.record(requestId, saved.getId());
        }
        webhookDispatcher.dispatch(saved);
    }

    /**
     * 把 connector 调用结果喂给 AI 路由时间桶窗口。MetricsCollector 未注入时 no-op
     * （AI 路由关闭 / 测试环境）。
     */
    private void recordConnectorOutcome(String connectorId, boolean success, long latencyMs, int costBps) {
        if (metricsCollector == null) return;
        try {
            metricsCollector.record(connectorId, success, latencyMs, costBps);
        } catch (RuntimeException e) {
            log.warn("MetricsCollector.record failed for {}: {}", connectorId, e.getMessage());
        }
    }

    /**
     * 发布支付完成事件，由 nexus-analytics 异步采集（进程内 Spring Event）。
     * paymentId 用 hashCode 转换为 Long（仅作事件关联标识，不存储到 analytics）。
     */
    private void publishPaymentCompleted(OrchestratedPayment p, String txHash,
                                          long latencyMs, int costBps) {
        if (applicationEventPublisher == null) return;
        try {
            Long paymentId = p.getId() != null ? (long) p.getId().hashCode() : null;
            applicationEventPublisher.publishEvent(new PaymentCompletedEvent(
                    this,
                    paymentId,
                    BigDecimal.valueOf(p.getAmount()),
                    p.getCurrency(),
                    p.getConnectorId(),
                    p.getMerchantId(),
                    txHash,
                    null, null,
                    Instant.now(),
                    latencyMs,
                    costBps
            ));
        } catch (RuntimeException e) {
            log.warn("Failed to publish PaymentCompletedEvent for {}: {}", p.getId(), e.getMessage());
        }
    }

    private OrchPaymentStatus mapStatus(PaymentStatus ps) {
        return switch (ps) {
            case CREATED -> OrchPaymentStatus.CREATED;
            case PROCESSING -> OrchPaymentStatus.PROCESSING;
            case SUCCEEDED -> OrchPaymentStatus.SUCCEEDED;
            case FAILED -> OrchPaymentStatus.FAILED;
            case EXPIRED -> OrchPaymentStatus.EXPIRED;
            case CANCELLED -> OrchPaymentStatus.CANCELLED;
            case REFUNDED -> OrchPaymentStatus.REFUNDED;
        };
    }
}
