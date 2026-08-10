package org.nexus.gateway.orchestration.webhook;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Webhook 投递记录实体（P4-T5）。
 *
 * <p>持久化每一次 Webhook 投递的状态，支持投递状态查询 API 与重投。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code deliveryId}：投递 ID（UUID），主键</li>
 *   <li>{@code paymentId}：关联的支付 ID（外键逻辑引用 orchestrated_payments.id）</li>
 *   <li>{@code merchantId}：商户 ID（便于按商户查询）</li>
 *   <li>{@code notifyUrl}：回调地址</li>
 *   <li>{@code payload}：投递 payload（JSON 字符串，便于重投）</li>
 *   <li>{@code status}：投递状态（PENDING/DELIVERED/RETRYING/DEAD_LETTER）</li>
 *   <li>{@code attemptCount}：已尝试次数（0=未尝试，1=首次投递，n=第 n 次重试）</li>
 *   <li>{@code lastError}：最后一次失败的错误信息（截断 1024 字符）</li>
 *   <li>{@code lastAttemptAt}：最后一次尝试时间</li>
 *   <li>{@code deliveredAt}：成功投递时间（仅 DELIVERED 状态有值）</li>
 *   <li>{@code deadLetteredAt}：转入死信队列时间（仅 DEAD_LETTER 状态有值）</li>
 *   <li>{@code signature}：HMAC-SHA256 签名（便于重投时复用）</li>
 * </ul>
 *
 * @since Phase 4 - P4-T5 Webhook 重试与死信队列增强
 */
@Entity
@Table(name = "webhook_deliveries", indexes = {
        @Index(name = "idx_wd_payment", columnList = "paymentId"),
        @Index(name = "idx_wd_merchant", columnList = "merchantId"),
        @Index(name = "idx_wd_status", columnList = "status"),
        @Index(name = "idx_wd_last_attempt", columnList = "lastAttemptAt")
})
public class WebhookDeliveryRecord {

    @Id
    @Column(length = 64)
    private String deliveryId;

    @Column(nullable = false, length = 64)
    private String paymentId;

    @Column(nullable = false)
    private Long merchantId;

    @Column(nullable = false, length = 512)
    private String notifyUrl;

    @Column(nullable = false, length = 4096)
    private String payload;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private WebhookDeliveryStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(length = 1024)
    private String lastError;

    private Instant lastAttemptAt;

    private Instant deliveredAt;

    private Instant deadLetteredAt;

    @Column(length = 128)
    private String signature;

    @Column(nullable = false)
    private Instant createdAt;

    @Version
    private Long version;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = WebhookDeliveryStatus.PENDING;
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

    public WebhookDeliveryStatus getStatus() { return status; }
    public void setStatus(WebhookDeliveryStatus status) { this.status = status; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }

    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }

    public Instant getDeadLetteredAt() { return deadLetteredAt; }
    public void setDeadLetteredAt(Instant deadLetteredAt) { this.deadLetteredAt = deadLetteredAt; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}