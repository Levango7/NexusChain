package org.nexus.gateway.orchestration.service;

import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Async webhook dispatcher for orchestration payment events.
 * Notifies merchants when payment status changes (PROCESSING -> SUCCEEDED/FAILED).
 *
 * <p>Delivery guarantees:
 * <ul>
 *   <li>Retry: exponential backoff, 3 attempts (5s, 30s, 120s).</li>
 *   <li>De-duplication: keyed by payment id + status, so repeated dispatch calls
 *       for the same terminal status do not double-notify.</li>
 *   <li>Dead-letter: deliveries that exhaust all retries are parked in an in-process
 *       dead-letter queue for later inspection / replay.</li>
 * </ul>
 *
 * <p>Note: the de-dup set and dead-letter queue are in-process. In a multi-instance
 * production deployment these should be backed by Redis (shared dedup key, durable
 * dead-letter store). TODO(v2.0.0): swap the local structures for a Redis-backed store — tracked in v2.0.0 roadmap</p>
 */
@Component
public class OrchestrationWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationWebhookDispatcher.class);
    private static final int MAX_RETRIES = 3;
    private static final long[] DEFAULT_RETRY_DELAYS_MS = {5000, 30000, 120000};

    private final RestTemplate restTemplate;
    private final long[] retryDelaysMs;

    /** Production constructor: real RestTemplate with the default backoff schedule. */
    public OrchestrationWebhookDispatcher() {
        this(new RestTemplate(), DEFAULT_RETRY_DELAYS_MS);
    }

    /** Test constructor: injectable RestTemplate + overridable delays (keeps suite fast). */
    OrchestrationWebhookDispatcher(RestTemplate restTemplate, long[] retryDelaysMs) {
        this.restTemplate = restTemplate;
        this.retryDelaysMs = retryDelaysMs;
    }

    // De-dup: paymentId:status -> first dispatch time. Prevents double notification.
    private final ConcurrentHashMap<String, Instant> dispatchedEvents = new ConcurrentHashMap<>();
    // Dead-letter: deliveries that exhausted all retries.
    private final BlockingQueue<DeadLetter> deadLetterQueue = new LinkedBlockingQueue<>();

    @Async
    public void dispatch(OrchestratedPayment payment) {
        if (payment.getNotifyUrl() == null || payment.getNotifyUrl().isBlank()) return;

        String dedupKey = payment.getId() + ":" + payment.getStatus().name();
        if (dispatchedEvents.putIfAbsent(dedupKey, Instant.now()) != null) {
            log.debug("Webhook dedup: already dispatched paymentId={} status={}",
                    payment.getId(), payment.getStatus().name());
            return;
        }

        Map<String, Object> payload = buildPayload(payment);
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("X-NexusChain-Event", "payment." + payment.getStatus().name().toLowerCase());
                headers.set("X-NexusChain-Payment-Id", payment.getId());
                headers.set("X-NexusChain-Timestamp", Instant.now().toString());

                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
                ResponseEntity<String> resp = restTemplate.exchange(
                        payment.getNotifyUrl(), HttpMethod.POST, entity, String.class);

                if (resp.getStatusCode().is2xxSuccessful()) {
                    log.info("Webhook delivered: paymentId={} url={} attempt={}", payment.getId(), payment.getNotifyUrl(), attempt + 1);
                    return;
                }
                log.warn("Webhook non-2xx: paymentId={} status={}", payment.getId(), resp.getStatusCode());
            } catch (Exception e) {
                log.warn("Webhook attempt {} failed for paymentId={}: {}", attempt + 1, payment.getId(), e.getMessage());
            }

            if (attempt < MAX_RETRIES - 1) {
                try { Thread.sleep(retryDelaysMs[attempt]); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
        log.error("Webhook delivery exhausted all retries: paymentId={} url={}", payment.getId(), payment.getNotifyUrl());
        deadLetterQueue.offer(new DeadLetter(payment.getId(), payment.getStatus().name(),
                payment.getNotifyUrl(), payload, Instant.now()));
    }

    public int getDeadLetterCount() {
        return deadLetterQueue.size();
    }

    public java.util.List<DeadLetter> drainDeadLetters() {
        java.util.List<DeadLetter> drained = new java.util.ArrayList<>();
        deadLetterQueue.drainTo(drained);
        return drained;
    }

    private Map<String, Object> buildPayload(OrchestratedPayment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", "payment." + p.getStatus().name().toLowerCase());
        m.put("payment_id", p.getId());
        m.put("status", p.getStatus().name());
        m.put("amount", p.getAmount());
        m.put("currency", p.getCurrency());
        m.put("connector", p.getConnectorId());
        m.put("transaction_hash", p.getTransactionHash());
        m.put("created_at", p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
        m.put("confirmed_at", p.getConfirmedAt() != null ? p.getConfirmedAt().toString() : null);
        return m;
    }

    /**
     * Dead-letter entry for a webhook delivery that exhausted all retries.
     */
    public static class DeadLetter {
        private final String paymentId;
        private final String status;
        private final String url;
        private final Map<String, Object> payload;
        private final Instant failedAt;

        public DeadLetter(String paymentId, String status, String url, Map<String, Object> payload, Instant failedAt) {
            this.paymentId = paymentId;
            this.status = status;
            this.url = url;
            this.payload = payload;
            this.failedAt = failedAt;
        }

        public String getPaymentId() { return paymentId; }
        public String getStatus() { return status; }
        public String getUrl() { return url; }
        public Map<String, Object> getPayload() { return payload; }
        public Instant getFailedAt() { return failedAt; }
    }
}
