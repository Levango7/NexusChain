package org.nexus.gateway.orchestration.service;

import org.nexus.gateway.orchestration.connector.*;
import org.nexus.gateway.orchestration.model.OrchPaymentStatus;
import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.nexus.gateway.orchestration.repository.OrchestratedPaymentRepository;
import org.nexus.gateway.orchestration.routing.RoutingEngine;
import org.nexus.gateway.risk.PaymentRequest;
import org.nexus.gateway.risk.PaymentRiskService;
import org.nexus.gateway.risk.RiskDecision;
import org.nexus.common.tracing.BusinessSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.tracing.Tracer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Core orchestration service: creates payments, routes to connectors, handles failover.
 *
 * <p>P3-T5：在支付创建主链路添加业务 span（payment.create → payment.route →
 * payment.connector.submit → payment.webhook.notify），span 树结构见
 * docs/tracing-business-span.md。</p>
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

    @Autowired
    public OrchestrationService(OrchestratedPaymentRepository repo,
                                RoutingEngine routingEngine,
                                ConnectorRegistry connectorRegistry,
                                OrchestrationIdempotencyStore idempotencyStore,
                                OrchestrationWebhookDispatcher webhookDispatcher,
                                PaymentRiskService riskService,
                                Tracer tracer) {
        this.repo = repo;
        this.routingEngine = routingEngine;
        this.connectorRegistry = connectorRegistry;
        this.idempotencyStore = idempotencyStore;
        this.webhookDispatcher = webhookDispatcher;
        this.riskService = riskService;
        this.tracer = tracer;
    }

    /**
     * 测试用兼容构造器：不注入 Tracer，业务 span 降级为 no-op。
     */
    public OrchestrationService(OrchestratedPaymentRepository repo,
                                RoutingEngine routingEngine,
                                ConnectorRegistry connectorRegistry,
                                OrchestrationIdempotencyStore idempotencyStore,
                                OrchestrationWebhookDispatcher webhookDispatcher,
                                PaymentRiskService riskService) {
        this(repo, routingEngine, connectorRegistry, idempotencyStore,
                webhookDispatcher, riskService, null);
    }

    @Transactional
    public OrchestratedPayment createPayment(Long merchantId, long amount, String currency,
                                              String description, String notifyUrl,
                                              String preferredConnector, String metadata,
                                              String requestId) {
        return createPayment(merchantId, amount, currency, description, notifyUrl,
                preferredConnector, metadata, requestId, null, null);
    }

    /**
     * 支付创建主入口（带付款人/收款人链上地址）。
     *
     * <p>P0 安全修复（空地址转账）：新增 {@code payeeAddress}/{@code payerAddress} 参数
     * 并透传到 {@link ConnectorPaymentRequest}，使链上连接器（ChainConnector /
     * ConsortiumConnector）能正确解析收款人公钥哈希，避免因 payeeAddress 缺失
     * 导致 {@code WalletUtils.addressToPubkeyHash(null)} 返回空串绕过校验。</p>
     *
     * <p>P0 安全修复（幂等 TOCTOU）：用原子 {@code tryReserve} 替代非原子的
     * {@code checkDuplicate}+{@code record}，消除 check 与 record 之间的竞态窗口。
     * 预留成功后业务若抛异常，则 {@code release} 回滚预留允许重试。</p>
     *
     * @param payeeAddress 收款人链上地址（可为 null，由连接器校验拒绝）
     * @param payerAddress 付款人链上地址（可为 null，用于退款回退到付款人）
     */
    @Transactional
    public OrchestratedPayment createPayment(Long merchantId, long amount, String currency,
                                              String description, String notifyUrl,
                                              String preferredConnector, String metadata,
                                              String requestId, String payeeAddress, String payerAddress) {
        // P3-T5：支付创建主 span（payment.create），覆盖整个支付全链路
        try (BusinessSpan rootSpan = BusinessSpan.start(tracer, "payment.create")
                .attr("payment.merchant.id", merchantId)
                .attr("payment.amount", amount)
                .attr("payment.currency", currency)
                .attr("payment.request.id", requestId)) {

            String paymentId = "pay_" + UUID.randomUUID().toString().replace("-", "");
            rootSpan.attr("payment.id", paymentId);

            // P0 安全修复（幂等 TOCTOU）：原子预留幂等键，替代非原子的 checkDuplicate+record。
            // tryReserve 在单个原子操作内完成"键不存在则写入、键已存在则拒绝"，
            // 消除原 check 与 record 之间的竞态窗口，防止并发重复创建支付。
            boolean idempotencyReserved = false;
            if (requestId != null && !requestId.isBlank()) {
                idempotencyReserved = idempotencyStore.tryReserve(requestId, paymentId);
                if (!idempotencyReserved) {
                    // 键已存在，返回已关联的支付（幂等重放）
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
                    // 并发竞争窗口：预留被占但记录尚未写入（另一线程在 tryReserve 后、record 前），
                    // fail-closed 拒绝重复创建，防止双花。
                    log.warn("Idempotency reserve failed (concurrent) for requestId={}", requestId);
                    OrchestratedPayment concurrent = new OrchestratedPayment();
                    concurrent.setId(paymentId);
                    concurrent.setMerchantId(merchantId);
                    concurrent.setAmount(amount);
                    concurrent.setCurrency(currency);
                    concurrent.setRequestId(requestId);
                    concurrent.setStatus(OrchPaymentStatus.FAILED);
                    rootSpan.attr("payment.status", "FAILED")
                            .attr("payment.idempotent", true)
                            .error(null);
                    return concurrent;
                }
            }

            try {
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
                // P0 安全修复：透传付款人/收款人地址到连接器，避免空地址转账。
                req.setPayeeAddress(payeeAddress);
                req.setPayerAddress(payerAddress);
                for (PaymentConnector connector : connectors) {
                    // P3-T5：连接器提交 span（payment.connector.submit）
                    try (BusinessSpan connSpan = BusinessSpan.start(tracer, "payment.connector.submit")
                            .attr("payment.id", paymentId)
                            .attr("payment.connector.id", connector.getId())) {
                        try {
                            ConnectorPaymentResult result = connector.createPayment(req);
                            if (result.isSuccess()) {
                                payment.setConnectorId(connector.getId());
                                payment.setConnectorPaymentId(result.getConnectorPaymentId());
                                payment.setTransactionHash(result.getTransactionHash());
                                payment.setStatus(mapStatus(result.getStatus()));
                                if (result.getStatus() == PaymentStatus.SUCCEEDED) {
                                    payment.setConfirmedAt(Instant.now());
                                }
                                persist(requestId, payment);
                                log.info("Payment {} routed to connector {} -> status={}", paymentId, connector.getId(), result.getStatus());
                                rootSpan.attr("payment.connector.id", connector.getId())
                                        .attr("payment.status", payment.getStatus().name());
                                if (result.getTransactionHash() != null) {
                                    rootSpan.attr("payment.tx.hash", result.getTransactionHash());
                                }
                                return payment;
                            }
                            log.warn("Connector {} rejected payment {}: {}", connector.getId(), paymentId, result.getErrorMessage());
                            connSpan.attr("payment.connector.error", result.getErrorMessage());
                        } catch (RuntimeException e) {
                            log.error("Connector {} threw exception for payment {}: {}", connector.getId(), paymentId, e.getMessage());
                            connSpan.error(e);
                        }
                    }
                }

                // All connectors failed
                payment.setStatus(OrchPaymentStatus.FAILED);
                persist(requestId, payment);
                log.error("All connectors failed for payment {}", paymentId);
                rootSpan.attr("payment.status", "FAILED").error(null);
                return payment;
            } catch (RuntimeException e) {
                // 业务执行异常：释放幂等预留，允许相同 requestId 重试。
                // DB 已由 @Transactional 回滚，但幂等键在 Redis/内存中（不在事务内），
                // 须显式释放，否则 requestId 将被永久占用。
                if (idempotencyReserved) {
                    idempotencyStore.release(requestId);
                }
                throw e;
            }
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

            PaymentStatus status = connector.queryPayment(payment.getConnectorPaymentId());
            payment.setStatus(mapStatus(status));
            if (status == PaymentStatus.SUCCEEDED && payment.getConfirmedAt() == null) {
                payment.setConfirmedAt(Instant.now());
            }
            OrchestratedPayment saved = repo.save(payment);
            span.attr("payment.status.new", saved.getStatus().name());
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