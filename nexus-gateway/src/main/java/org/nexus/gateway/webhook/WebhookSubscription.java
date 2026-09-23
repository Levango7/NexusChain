package org.nexus.gateway.webhook;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Webhook 订阅实体。
 *
 * <p>商户通过创建订阅来接收特定事件类型的回调通知。每个订阅绑定一个目标 URL、
 * 一组事件类型、签名密钥和可选的过滤表达式。投递服务在事件发生时查询活跃订阅，
 * 匹配事件类型后向目标 URL 发起 POST 请求，使用 signingSecret 计算 HMAC 签名。</p>
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code id} — 订阅 ID（自增主键）</li>
 *   <li>{@code merchantId} — 商户 ID（租户隔离）</li>
 *   <li>{@code targetUrl} — 回调目标 URL（投递服务向此 URL 发起 POST）</li>
 *   <li>{@code eventTypes} — 订阅的事件类型（逗号分隔，如 "PAYMENT_CONFIRMED,PAYMENT_FAILED"）</li>
 *   <li>{@code status} — 订阅状态（ACTIVE/PAUSED/DELETED）</li>
 *   <li>{@code signingSecret} — 签名密钥（SecureRandom 生成，用于回调 HMAC-SHA256 签名验证）</li>
 *   <li>{@code description} — 订阅描述（可选）</li>
 *   <li>{@code retryPolicy} — 重试策略（JSON，默认 3 次指数退避）</li>
 *   <li>{@code filterExpression} — 可选 JSONPath 过滤表达式（只有匹配的事件才投递）</li>
 *   <li>{@code createdAt} — 创建时间</li>
 *   <li>{@code updatedAt} — 更新时间</li>
 * </ul>
 */
@Entity
@Table(name = "webhook_subscriptions", indexes = {
        @Index(name = "idx_ws_merchant", columnList = "merchantId"),
        @Index(name = "idx_ws_status", columnList = "status"),
        @Index(name = "idx_ws_merchant_status", columnList = "merchantId,status")
})
public class WebhookSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long merchantId;

    @Column(nullable = false, length = 512)
    private String targetUrl;

    /** 订阅的事件类型，逗号分隔（如 "PAYMENT_CONFIRMED,PAYMENT_FAILED"）。 */
    @Column(nullable = false, length = 256)
    private String eventTypes;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private WebhookSubscriptionStatus status;

    /** 签名密钥，用于回调 HMAC-SHA256 签名验证。创建时由 SecureRandom 生成。 */
    @Column(nullable = false, length = 64)
    private String signingSecret;

    @Column(length = 256)
    private String description;

    /** 重试策略（JSON 格式，默认 {"maxRetries":3,"backoff":"exponential"}）。 */
    @Column(length = 512)
    private String retryPolicy;

    /** 可选 JSONPath 过滤表达式，只有匹配的事件才投递。 */
    @Column(length = 512)
    private String filterExpression;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = WebhookSubscriptionStatus.ACTIVE;
        if (retryPolicy == null) retryPolicy = "{\"maxRetries\":3,\"backoff\":\"exponential\"}";
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }

    public String getEventTypes() { return eventTypes; }
    public void setEventTypes(String eventTypes) { this.eventTypes = eventTypes; }

    public WebhookSubscriptionStatus getStatus() { return status; }
    public void setStatus(WebhookSubscriptionStatus status) { this.status = status; }

    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getRetryPolicy() { return retryPolicy; }
    public void setRetryPolicy(String retryPolicy) { this.retryPolicy = retryPolicy; }

    public String getFilterExpression() { return filterExpression; }
    public void setFilterExpression(String filterExpression) { this.filterExpression = filterExpression; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    /**
     * 判断此订阅是否订阅了指定事件类型。
     *
     * @param eventType 事件类型
     * @return {@code true} 若订阅了该事件类型
     */
    public boolean isSubscribedTo(WebhookEventType eventType) {
        if (eventTypes == null || eventTypes.isBlank()) {
            return false;
        }
        for (String type : eventTypes.split(",")) {
            if (type.trim().equalsIgnoreCase(eventType.name())) {
                return true;
            }
        }
        return false;
    }
}