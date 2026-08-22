package org.nexus.gateway.orchestration.service;

import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.nexus.gateway.orchestration.webhook.WebhookDeliveryService;
import org.nexus.common.tracing.BusinessSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.tracing.Tracer;
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
 * <p>P3-T5：Webhook 投递添加业务 span（payment.webhook.notify），
 * 携带 webhook.url / webhook.status / webhook.attempt 属性。</p>
 *
 * <p>P4-T5：可选委托给 {@link WebhookDeliveryService}（增强版投递服务）。
 * 当 Spring 上下文中存在 {@link WebhookDeliveryService} Bean 时，dispatch 委托给它，
 * 使用指数退避 + 抖动重试（max 8 次）、HMAC-SHA256 签名、Kafka DLQ 死信队列、
 * 投递记录持久化。否则降级为原有逻辑（3 次重试 + 内存死信队列）。</p>
 *
 * <p>Delivery guarantees (legacy fallback):
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
    /** Micrometer Tracer：P3-T5 webhook span 注入。可为 null（测试降级 no-op）。 */
    private final Tracer tracer;
    /** P4-T5：增强版投递服务。可为 null（降级为原有逻辑）。 */
    private final WebhookDeliveryService deliveryService;

    /** Production constructor: real RestTemplate with the default backoff schedule. */
    @Autowired
    public OrchestrationWebhookDispatcher(Tracer tracer,
                                          org.springframework.beans.factory.ObjectProvider<WebhookDeliveryService> deliveryServiceProvider) {
        this(new RestTemplate(), DEFAULT_RETRY_DELAYS_MS, tracer, deliveryServiceProvider.getIfAvailable());
    }

    /** Production constructor without tracer (backward compat). */
    public OrchestrationWebhookDispatcher() {
        this(new RestTemplate(), DEFAULT_RETRY_DELAYS_MS, null, null);
    }

    /** Test constructor: injectable RestTemplate + overridable delays (keeps suite fast). */
    OrchestrationWebhookDispatcher(RestTemplate restTemplate, long[] retryDelaysMs, Tracer tracer) {
        this(restTemplate, retryDelaysMs, tracer, null);
    }

    /** Test constructor backward compat: no tracer. */
    OrchestrationWebhookDispatcher(RestTemplate restTemplate, long[] retryDelaysMs) {
        this(restTemplate, retryDelaysMs, null, null);
    }

    /** Full constructor. */
    OrchestrationWebhookDispatcher(RestTemplate restTemplate, long[] retryDelaysMs, Tracer tracer,
                                   WebhookDeliveryService deliveryService) {
        this.restTemplate = restTemplate;
        this.retryDelaysMs = retryDelaysMs;
        this.tracer = tracer;
        this.deliveryService = deliveryService;
    }

    // De-dup: paymentId:status -> first dispatch time. Prevents double notification.
    private final ConcurrentHashMap<String, Instant> dispatchedEvents = new ConcurrentHashMap<>();
    // Dead-letter: deliveries that exhausted all retries.
    private final BlockingQueue<DeadLetter> deadLetterQueue = new LinkedBlockingQueue<>();

    /**
     * Redis 支持（TODO v2.0.0 落地）：多实例共享 dedup + 持久化 DLQ。
     * 可选注入——无 Redis/测试环境回退本地结构（向后兼容）。
     */
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private static final String REDIS_DEDUP_PREFIX = "nexus:webhook:dedup:";
    private static final String REDIS_DLQ_KEY = "nexus:webhook:dead-letter";

    /** 注入 Redis（多实例生产部署用；测试/无 Redis 不注入即回退本地）。 */
    public void setRedisTemplate(org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Redis dedup：SETNX + TTL（多实例共享去重）。 */
    private boolean tryDedup(String dedupKey) {
        if (redisTemplate != null) {
            try {
                Boolean first = redisTemplate.opsForValue().setIfAbsent(
                        REDIS_DEDUP_PREFIX + dedupKey, Instant.now().toString(),
                        java.time.Duration.ofHours(24));
                return Boolean.TRUE.equals(first);
            } catch (RuntimeException e) {
                log.warn("Redis dedup unavailable, fallback local: {}", e.getMessage());
            }
        }
        return dispatchedEvents.putIfAbsent(dedupKey, Instant.now()) == null;
    }

    /** 记录死信：Redis List（持久化）或本地队列（回退）。 */
    private void recordDeadLetter(DeadLetter dl) {
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForList().leftPush(REDIS_DLQ_KEY,
                        dl.paymentId + "|" + dl.status + "|" + dl.url + "|" + dl.failedAt);
                return;
            } catch (RuntimeException e) {
                log.warn("Redis DLQ unavailable, fallback local: {}", e.getMessage());
            }
        }
        deadLetterQueue.offer(dl);
    }

    @Async
    public void dispatch(OrchestratedPayment payment) {
        if (payment.getNotifyUrl() == null || payment.getNotifyUrl().isBlank()) return;

        // P4-T5：优先委托给增强版投递服务
        if (deliveryService != null) {
            dispatchViaDeliveryService(payment);
            return;
        }

        // P3-T5：Webhook 投递 span（payment.webhook.notify）
        try (BusinessSpan webhookSpan = BusinessSpan.start(tracer, "payment.webhook.notify")
                .attr("payment.id", payment.getId())
                .attr("webhook.url", payment.getNotifyUrl())
                .attr("payment.status", payment.getStatus().name())) {

            String dedupKey = payment.getId() + ":" + payment.getStatus().name();
            if (!tryDedup(dedupKey)) {
                log.debug("Webhook dedup: already dispatched paymentId={} status={}",
                        payment.getId(), payment.getStatus().name());
                webhookSpan.attr("webhook.dedup", true);
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
                        webhookSpan.attr("webhook.attempt", attempt + 1)
                                .attr("webhook.status", resp.getStatusCode().value())
                                .success();
                        return;
                    }
                    log.warn("Webhook non-2xx: paymentId={} status={}", payment.getId(), resp.getStatusCode());
                    webhookSpan.attr("webhook.status", resp.getStatusCode().value());
                } catch (RuntimeException e) {
                    log.warn("Webhook attempt {} failed for paymentId={}: {}", attempt + 1, payment.getId(), e.getMessage());
                    webhookSpan.attr("webhook.attempt.error", e.getMessage());
                }

                if (attempt < MAX_RETRIES - 1) {
                    try { Thread.sleep(retryDelaysMs[attempt]); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
            log.error("Webhook delivery exhausted all retries: paymentId={} url={}", payment.getId(), payment.getNotifyUrl());
            webhookSpan.attr("webhook.dead.letter", true).error(null);
            recordDeadLetter(new DeadLetter(payment.getId(), payment.getStatus().name(),
                    payment.getNotifyUrl(), payload, Instant.now()));
        }
    }

    /**
     * P4-T5：通过增强版投递服务投递（指数退避 + 抖动 + HMAC 签名 + Kafka DLQ）。
     */
    private void dispatchViaDeliveryService(OrchestratedPayment payment) {
        try (BusinessSpan webhookSpan = BusinessSpan.start(tracer, "payment.webhook.notify")
                .attr("payment.id", payment.getId())
                .attr("webhook.url", payment.getNotifyUrl())
                .attr("payment.status", payment.getStatus().name())) {
            try {
                Map<String, Object> payload = buildPayload(payment);
                String statusEvent = "payment." + payment.getStatus().name().toLowerCase();
                var record = deliveryService.deliver(
                        payment.getId(), payment.getMerchantId(), payment.getNotifyUrl(),
                        payload, statusEvent);
                if (record != null) {
                    webhookSpan.attr("webhook.delivery.id", record.getDeliveryId())
                            .attr("webhook.delivery.status", record.getStatus().name())
                            .attr("webhook.attempt", record.getAttemptCount());
                    if (record.getStatus() == org.nexus.gateway.orchestration.webhook.WebhookDeliveryStatus.DELIVERED) {
                        webhookSpan.success();
                    } else {
                        webhookSpan.error(null);
                    }
                }
            } catch (RuntimeException e) {
                log.error("Webhook delivery via WebhookDeliveryService failed: paymentId={}, error={}",
                        payment.getId(), e.getMessage(), e);
                webhookSpan.attr("webhook.error", e.getMessage()).error(e);
            }
        }
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
