package org.nexus.gateway.orchestration.webhook;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 死信队列持久化记录实体（Wave 8-A5）。
 *
 * <p>将死信消息持久化到数据库，替代纯 Kafka/内存方案，确保即使 Kafka 不可用
 * 也能保留死信记录，支持运维查询与手动重投。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code id}：自增主键</li>
 *   <li>{@code webhookUrl}：回调地址（原 notifyUrl）</li>
 *   <li>{@code eventId}：事件 ID（关联 webhook_deliveries.deliveryId）</li>
 *   <li>{@code payload}：原始 Webhook payload（JSON 字符串）</li>
 *   <li>{@code errorMessage}：失败原因（最后一次错误信息）</li>
 *   <li>{@code retryCount}：已重试次数</li>
 *   <li>{@code status}：死信记录状态（PENDING_REPLAY / REPLAYED / ARCHIVED）</li>
 *   <li>{@code createdAt}：转入死信队列时间</li>
 *   <li>{@code lastRetryAt}：最后重试时间</li>
 * </ul>
 *
 * @since Wave 8-A5 - Webhook 可靠性增强
 */
@Entity
@Table(name = "dead_letter_records", indexes = {
        @Index(name = "idx_dlr_event_id", columnList = "eventId"),
        @Index(name = "idx_dlr_status", columnList = "status"),
        @Index(name = "idx_dlr_created_at", columnList = "createdAt")
})
public class DeadLetterRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String webhookUrl;

    @Column(nullable = false, length = 64)
    private String eventId;

    @Column(nullable = false, length = 4096)
    private String payload;

    @Column(length = 1024)
    private String errorMessage;

    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private DeadLetterRecordStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastRetryAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = DeadLetterRecordStatus.PENDING_REPLAY;
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public DeadLetterRecordStatus getStatus() { return status; }
    public void setStatus(DeadLetterRecordStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastRetryAt() { return lastRetryAt; }
    public void setLastRetryAt(Instant lastRetryAt) { this.lastRetryAt = lastRetryAt; }
}