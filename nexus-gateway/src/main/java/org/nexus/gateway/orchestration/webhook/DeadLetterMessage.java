package org.nexus.gateway.orchestration.webhook;

import java.time.Instant;

/**
 * 死信队列消息（P4-T5）。
 *
 * <p>DLQ 消息包含：
 * <ul>
 *   <li>{@code deliveryId}：投递记录 ID（关联 webhook_deliveries 表）</li>
 *   <li>{@code paymentId}：原始支付 ID</li>
 *   <li>{@code merchantId}：商户 ID</li>
 *   <li>{@code notifyUrl}：回调地址</li>
 *   <li>{@code payload}：原始 Webhook payload（JSON 字符串）</li>
 *   <li>{@code signature}：HMAC-SHA256 签名（重投时复用）</li>
 *   <li>{@code failureReason}：失败原因（最后一次错误信息）</li>
 *   <li>{@code retryCount}：已重试次数</li>
 *   <li>{@code firstAttemptAt}：首次尝试时间</li>
 *   <li>{@code lastAttemptAt}：最后重试时间</li>
 *   <li>{@code deadLetteredAt}：转入死信队列时间</li>
 * </ul>
 *
 * <p>序列化为 JSON 后写入 Kafka topic {@code webhook-dlq}。
 *
 * @since Phase 4 - P4-T5 Webhook 重试与死信队列增强
 */
public class DeadLetterMessage {

    private String deliveryId;
    private String paymentId;
    private Long merchantId;
    private String notifyUrl;
    private String payload;
    private String signature;
    private String failureReason;
    private int retryCount;
    private Instant firstAttemptAt;
    private Instant lastAttemptAt;
    private Instant deadLetteredAt;

    /** Jackson 反序列化需要的无参构造器。 */
    public DeadLetterMessage() {
    }

    public DeadLetterMessage(String deliveryId, String paymentId, Long merchantId,
                             String notifyUrl, String payload, String signature,
                             String failureReason, int retryCount,
                             Instant firstAttemptAt, Instant lastAttemptAt,
                             Instant deadLetteredAt) {
        this.deliveryId = deliveryId;
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.notifyUrl = notifyUrl;
        this.payload = payload;
        this.signature = signature;
        this.failureReason = failureReason;
        this.retryCount = retryCount;
        this.firstAttemptAt = firstAttemptAt;
        this.lastAttemptAt = lastAttemptAt;
        this.deadLetteredAt = deadLetteredAt;
    }

    /** 从投递记录构造死信消息。 */
    public static DeadLetterMessage fromRecord(WebhookDeliveryRecord record, String failureReason) {
        return new DeadLetterMessage(
                record.getDeliveryId(),
                record.getPaymentId(),
                record.getMerchantId(),
                record.getNotifyUrl(),
                record.getPayload(),
                record.getSignature(),
                failureReason,
                record.getAttemptCount(),
                record.getCreatedAt(),
                record.getLastAttemptAt(),
                Instant.now()
        );
    }

    // --- Getters and Setters ---

    public String getDeliveryId() { return deliveryId; }
    public void setDeliveryId(String deliveryId) { this.deliveryId = deliveryId; }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getNotifyUrl() { return notifyUrl; }
    public void setNotifyUrl(String notifyUrl) { this.notifyUrl = notifyUrl; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public Instant getFirstAttemptAt() { return firstAttemptAt; }
    public void setFirstAttemptAt(Instant firstAttemptAt) { this.firstAttemptAt = firstAttemptAt; }

    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }

    public Instant getDeadLetteredAt() { return deadLetteredAt; }
    public void setDeadLetteredAt(Instant deadLetteredAt) { this.deadLetteredAt = deadLetteredAt; }
}