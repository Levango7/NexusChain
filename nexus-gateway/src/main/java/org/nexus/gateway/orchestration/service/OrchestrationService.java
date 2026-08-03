package org.nexus.gateway.orchestration.service;

import org.nexus.gateway.orchestration.connector.*;
import org.nexus.gateway.orchestration.model.OrchPaymentStatus;
import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.nexus.gateway.orchestration.repository.OrchestratedPaymentRepository;
import org.nexus.gateway.orchestration.routing.RoutingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Core orchestration service: creates payments, routes to connectors, handles failover.
 */
@Service
public class OrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationService.class);

    private final OrchestratedPaymentRepository repo;
    private final RoutingEngine routingEngine;
    private final ConnectorRegistry connectorRegistry;
    private final OrchestrationIdempotencyStore idempotencyStore;
    private final OrchestrationWebhookDispatcher webhookDispatcher;

    public OrchestrationService(OrchestratedPaymentRepository repo,
                                RoutingEngine routingEngine,
                                ConnectorRegistry connectorRegistry,
                                OrchestrationIdempotencyStore idempotencyStore,
                                OrchestrationWebhookDispatcher webhookDispatcher) {
        this.repo = repo;
        this.routingEngine = routingEngine;
        this.connectorRegistry = connectorRegistry;
        this.idempotencyStore = idempotencyStore;
        this.webhookDispatcher = webhookDispatcher;
    }

    @Transactional
    public OrchestratedPayment createPayment(Long merchantId, long amount, String currency,
                                              String description, String notifyUrl,
                                              String preferredConnector, String metadata,
                                              String requestId) {
        // Idempotency: replay an already-processed request_id instead of creating a new payment.
        if (requestId != null && !requestId.isBlank()) {
            String existingPaymentId = idempotencyStore.checkDuplicate(requestId);
            if (existingPaymentId != null) {
                OrchestratedPayment existing = repo.findById(existingPaymentId).orElse(null);
                if (existing != null) {
                    log.info("Idempotent replay: requestId={} -> existing paymentId={}",
                            requestId, existingPaymentId);
                    return existing;
                }
            }
        }

        String paymentId = "pay_" + UUID.randomUUID().toString().replace("-", "");

        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId(paymentId);
        payment.setMerchantId(merchantId);
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setDescription(description);
        payment.setNotifyUrl(notifyUrl);
        payment.setMetadata(metadata);
        payment.setRequestId(requestId);
        payment.setStatus(OrchPaymentStatus.CREATED);
        payment.setRoutingStrategy(preferredConnector != null ? "explicit" : "priority");

        // Route to connectors
        List<PaymentConnector> connectors = routingEngine.resolve(currency, amount, preferredConnector);
        if (connectors.isEmpty()) {
            payment.setStatus(OrchPaymentStatus.FAILED);
            persist(requestId, payment);
            log.warn("No connectors available for payment {}", paymentId);
            return payment;
        }

        // Try connectors in order (failover)
        ConnectorPaymentRequest req = new ConnectorPaymentRequest(paymentId, amount, currency, description);
        for (PaymentConnector connector : connectors) {
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
                    return payment;
                }
                log.warn("Connector {} rejected payment {}: {}", connector.getId(), paymentId, result.getErrorMessage());
            } catch (Exception e) {
                log.error("Connector {} threw exception for payment {}: {}", connector.getId(), paymentId, e.getMessage());
            }
        }

        // All connectors failed
        payment.setStatus(OrchPaymentStatus.FAILED);
        persist(requestId, payment);
        log.error("All connectors failed for payment {}", paymentId);
        return payment;
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
        OrchestratedPayment payment = repo.findById(paymentId).orElse(null);
        if (payment == null) return null;
        if (payment.getStatus() == OrchPaymentStatus.SUCCEEDED || payment.getStatus() == OrchPaymentStatus.FAILED) {
            return payment;
        }
        PaymentConnector connector = connectorRegistry.get(payment.getConnectorId()).orElse(null);
        if (connector == null) return payment;

        PaymentStatus status = connector.queryPayment(payment.getConnectorPaymentId());
        payment.setStatus(mapStatus(status));
        if (status == PaymentStatus.SUCCEEDED && payment.getConfirmedAt() == null) {
            payment.setConfirmedAt(Instant.now());
        }
        OrchestratedPayment saved = repo.save(payment);
        webhookDispatcher.dispatch(saved);
        return saved;
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