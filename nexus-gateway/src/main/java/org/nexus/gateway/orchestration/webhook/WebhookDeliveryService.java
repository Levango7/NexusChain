package org.nexus.gateway.orchestration.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.nexus.gateway.webhook.WebhookUrlValidator;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook 投递服务（P4-T5 / A3 长事务修复 2026-09-03）。
 *
 * <p>核心投递流程：
 * <ol>
 *   <li>创建投递记录（PENDING）</li>
 *   <li>计算 HMAC-SHA256 签名</li>
 *   <li>发起 HTTP POST 投递（携带 {@code X-NexusChain-Signature} 头）</li>
 *   <li>失败时按指数退避 + 抖动重试（最多 8 次）</li>
 *   <li>重试耗尽后转入死信队列（Kafka DLQ topic）</li>
 *   <li>全程更新投递记录状态（PENDING → RETRYING → DELIVERED/DEAD_LETTER）</li>
 * </ol>
 *
 * <h3>事务边界（A3 修复）</h3>
 * <p>本服务所有方法<b>不持有</b>方法级 {@code @Transactional}：
 * <ul>
 *   <li>每次状态更新（{@code repository.save}）由 JpaRepository 的
 *       {@code SimpleJpaRepository.save} 自带事务独立提交（短事务，毫秒级）</li>
 *   <li>HTTP 投递与指数退避等待（{@code WebhookRetryService.awaitRetry}，
 *       最长 8 次退避可达数十秒）在<b>事务外</b>执行，不再占用数据库连接</li>
 * </ul>
 * 修复前：方法级事务将「PENDING 落库 + 9 次 HTTP + 8 次退避 + 多次状态更新」
 * 包在同一长事务里，高峰期会拖垮连接池（连接被投递等待独占数秒到数十秒）。
 * 修复后：投递全程仅按需获取连接（每次 save 短暂借用即归还），连接持有时间
 * 与投递时长解耦。</p>
 * <p>原子性取舍：PENDING 创建与首次投递不再同事务——若进程在两者之间崩溃，
 * 会残留 PENDING 记录（无投递发生），由 {@code replay}（死信重投）或
 * 运维查询发现；换取的是连接池健康。SSRF 校验（validate 抛异常）仍在
 * save 之前，非法 URL 不落库的语义保持不变。</p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>幂等：同一支付 + 同一状态只投递一次（通过 deliveryId 去重）</li>
 *   <li>可观测：每次投递/重试/死信都更新数据库记录，便于查询</li>
 *   <li>解耦：{@link WebhookRetryService}（重试策略）、{@link WebhookSignatureService}（签名）、
 *       {@link DeadLetterSender}（死信队列）均可独立替换</li>
 *   <li>测试友好：构造器注入所有依赖，便于 Mock</li>
 * </ul>
 *
 * @since Phase 4 - P4-T5 Webhook 重试与死信队列增强
 */
@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

    private final WebhookDeliveryRepository repository;
    private final WebhookRetryService retryService;
    private final WebhookSignatureService signatureService;
    private final DeadLetterSender deadLetterSender;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String signingSecret;
    /**
     * 出站 URL 校验器（SSRF 防护，P1 审计项 2026-08-29）。
     * 无状态组件，通过构造器注入以便测试可 mock（避免 DNS 解析的环境依赖）。
     */
    private final WebhookUrlValidator urlValidator;

    @Autowired
    public WebhookDeliveryService(
            WebhookDeliveryRepository repository,
            WebhookRetryService retryService,
            WebhookSignatureService signatureService,
            DeadLetterSender deadLetterSender,
            org.springframework.web.client.RestTemplate restTemplate,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            @Value("${nexus.webhook.callback-secret:}") String signingSecret,
            WebhookUrlValidator urlValidator) {
        this.repository = repository;
        this.retryService = retryService;
        this.signatureService = signatureService;
        this.deadLetterSender = deadLetterSender;
        this.restTemplate = restTemplate;
        this.signingSecret = signingSecret;
        this.objectMapper = objectMapper;
        this.urlValidator = urlValidator;
    }

    /** 测试构造器：可注入 RestTemplate + UrlValidator。 */
    public WebhookDeliveryService(
            WebhookDeliveryRepository repository,
            WebhookRetryService retryService,
            WebhookSignatureService signatureService,
            DeadLetterSender deadLetterSender,
            RestTemplate restTemplate,
            String signingSecret,
            WebhookUrlValidator urlValidator) {
        this.repository = repository;
        this.retryService = retryService;
        this.signatureService = signatureService;
        this.deadLetterSender = deadLetterSender;
        this.restTemplate = restTemplate;
        this.signingSecret = signingSecret;
        this.objectMapper = new ObjectMapper();
        this.urlValidator = urlValidator;
    }

    /**
     * 投递 Webhook 通知。
     *
     * <p>同步执行：包含重试与退避等待。调用方若需异步，应包装在 {@code @Async} 中。
     *
     * <h3>事务边界（A3 修复）</h3>
     * <p>无方法级事务：PENDING 落库与每次状态更新均为 repository 短事务；
     * HTTP 投递与退避等待在事务外，不占用数据库连接。
     * SSRF 校验仍在落库之前（非法 URL 不产生投递记录）。</p>
     *
     * @param paymentId    支付 ID
     * @param merchantId   商户 ID
     * @param notifyUrl    回调地址（null/空则跳过）
     * @param payload      投递 payload（Map 形式）
     * @param statusEvent  状态事件名（如 "payment.succeeded"，用于去重与日志）
     * @return 投递记录（最终状态：DELIVERED 或 DEAD_LETTER）；若 notifyUrl 为空则返回 null
     */
    public WebhookDeliveryRecord deliver(String paymentId, Long merchantId, String notifyUrl,
                                         Map<String, Object> payload, String statusEvent) {
        if (notifyUrl == null || notifyUrl.isBlank()) {
            log.debug("Webhook skipped: no notifyUrl, paymentId={}", paymentId);
            return null;
        }

        // SSRF 防护：投递前校验回调 URL（非法/内网地址抛异常 → 事务回滚，不落投递记录）
        urlValidator.validate(notifyUrl);

        // 去重：同一支付 + 同一状态事件只投递一次
        WebhookDeliveryRecord existing = repository
                .findByPaymentIdAndStatus(paymentId, statusEvent).orElse(null);
        if (existing != null) {
            log.debug("Webhook dedup: already delivered paymentId={} status={}", paymentId, statusEvent);
            return existing;
        }

        // 创建投递记录
        String deliveryId = UUID.randomUUID().toString().replace("-", "");
        String payloadJson = serializePayload(payload);
        String signature = signatureService.sign(payload, signingSecret);

        WebhookDeliveryRecord record = new WebhookDeliveryRecord();
        record.setDeliveryId(deliveryId);
        record.setPaymentId(paymentId);
        record.setMerchantId(merchantId);
        record.setNotifyUrl(notifyUrl);
        record.setPayload(payloadJson);
        record.setSignature(signature);
        record.setStatus(WebhookDeliveryStatus.PENDING);
        record.setAttemptCount(0);
        record = repository.save(record);

        // 执行投递（含重试）
        return executeWithRetry(record, payload);
    }

    /**
     * 从死信队列重投。
     *
     * <p>将死信消息重新投递，状态从 DEAD_LETTER 转为 RETRYING。
     * 事务边界同 {@link #deliver}：状态更新走 repository 短事务，
     * HTTP 与退避在事务外。
     *
     * @param message 死信消息
     * @return 投递记录（最终状态）
     */
    public WebhookDeliveryRecord replay(DeadLetterMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("dead letter message must not be null");
        }
        log.info("Webhook replay: deliveryId={}, paymentId={}",
                message.getDeliveryId(), message.getPaymentId());

        // SSRF 防护：死信重投同样校验回调 URL
        urlValidator.validate(message.getNotifyUrl());

        WebhookDeliveryRecord record = repository.findById(message.getDeliveryId()).orElse(null);
        if (record == null) {
            log.warn("Replay: delivery record not found, creating new one: deliveryId={}",
                    message.getDeliveryId());
            record = new WebhookDeliveryRecord();
            record.setDeliveryId(message.getDeliveryId());
            record.setPaymentId(message.getPaymentId());
            record.setMerchantId(message.getMerchantId());
            record.setNotifyUrl(message.getNotifyUrl());
            record.setPayload(message.getPayload());
            record.setSignature(message.getSignature());
            record.setStatus(WebhookDeliveryStatus.PENDING);
            record.setAttemptCount(0);
            record = repository.save(record);
        } else {
            record.setStatus(WebhookDeliveryStatus.RETRYING);
            record.setDeadLetteredAt(null);
            record = repository.save(record);
        }

        Map<String, Object> payload = deserializePayload(record.getPayload());
        return executeWithRetry(record, payload);
    }

    /**
     * 查询投递状态。
     *
     * @param deliveryId 投递 ID
     * @return 投递记录（若不存在返回 null）
     */
    public WebhookDeliveryRecord getDelivery(String deliveryId) {
        return repository.findById(deliveryId).orElse(null);
    }

    /**
     * 按支付 ID 查询所有投递记录。
     */
    public java.util.List<WebhookDeliveryRecord> getByPaymentId(String paymentId) {
        return repository.findByPaymentId(paymentId);
    }

    /**
     * 执行投递（含重试逻辑）。
     *
     * <p>重试策略由 {@link WebhookRetryService} 控制：
     * <ul>
     *   <li>首次投递（attempt=0）</li>
     *   <li>失败后 {@code shouldRetry(1)} → 退避 {@code computeDelayMs(0)} → 重试</li>
     *   <li>...直到成功或重试耗尽</li>
     *   <li>重试耗尽后转入死信队列</li>
     * </ul>
     */
    private WebhookDeliveryRecord executeWithRetry(WebhookDeliveryRecord record, Map<String, Object> payload) {
        int maxRetries = retryService.getMaxRetries();
        String lastError = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            record.setAttemptCount(attempt);
            record.setLastAttemptAt(Instant.now());

            try {
                HttpHeaders headers = buildHeaders(record, payload);
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
                ResponseEntity<String> resp = restTemplate.exchange(
                        record.getNotifyUrl(), HttpMethod.POST, entity, String.class);

                if (resp.getStatusCode().is2xxSuccessful()) {
                    record.setStatus(WebhookDeliveryStatus.DELIVERED);
                    record.setDeliveredAt(Instant.now());
                    record.setLastError(null);
                    record = repository.save(record);
                    log.info("Webhook delivered: deliveryId={}, paymentId={}, attempt={}",
                            record.getDeliveryId(), record.getPaymentId(), attempt + 1);
                    return record;
                }
                lastError = "HTTP " + resp.getStatusCode().value();
                log.warn("Webhook non-2xx: deliveryId={}, status={}, attempt={}",
                        record.getDeliveryId(), resp.getStatusCode(), attempt + 1);
            } catch (RuntimeException e) {
                lastError = truncate(e.getMessage(), 1024);
                log.warn("Webhook attempt {} failed: deliveryId={}, error={}",
                        attempt + 1, record.getDeliveryId(), e.getMessage());
            }

            // 更新状态为 RETRYING（若还不是）
            if (record.getStatus() != WebhookDeliveryStatus.RETRYING) {
                record.setStatus(WebhookDeliveryStatus.RETRYING);
            }
            record.setLastError(lastError);
            record = repository.save(record);

            // 是否继续重试
            if (attempt < maxRetries) {
                if (!retryService.awaitRetry(attempt)) {
                    log.info("Webhook retry interrupted: deliveryId={}", record.getDeliveryId());
                    break;
                }
            }
        }

        // 重试耗尽，转入死信队列
        record.setStatus(WebhookDeliveryStatus.DEAD_LETTER);
        record.setDeadLetteredAt(Instant.now());
        record.setLastError(lastError);
        record = repository.save(record);

        DeadLetterMessage dlqMessage = DeadLetterMessage.fromRecord(record, lastError);
        try {
            deadLetterSender.sendToDeadLetter(dlqMessage);
        } catch (RuntimeException e) {
            log.error("Failed to send to DLQ: deliveryId={}, error={}",
                    record.getDeliveryId(), e.getMessage(), e);
        }

        log.error("Webhook dead-lettered: deliveryId={}, paymentId={}, attempts={}",
                record.getDeliveryId(), record.getPaymentId(), record.getAttemptCount());
        return record;
    }

    /**
     * 构建投递请求头（含签名）。
     */
    private HttpHeaders buildHeaders(WebhookDeliveryRecord record, Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // payload 中 event 字段已包含完整事件名（如 "payment.succeeded"）
        Object event = payload.get("event");
        headers.set("X-NexusChain-Event", event != null ? String.valueOf(event) : "unknown");
        headers.set("X-NexusChain-Payment-Id", record.getPaymentId());
        headers.set("X-NexusChain-Timestamp", Instant.now().toString());
        headers.set("X-NexusChain-Delivery-Id", record.getDeliveryId());
        if (record.getSignature() != null && !record.getSignature().isEmpty()) {
            headers.set(WebhookSignatureService.SIGNATURE_HEADER, record.getSignature());
        }
        return headers;
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize webhook payload", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializePayload(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize webhook payload", e);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}