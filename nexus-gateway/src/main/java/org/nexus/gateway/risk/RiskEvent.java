package org.nexus.gateway.risk;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 风控事件实体 — 记录风控系统中发生的所有关键事件。
 *
 * <p>每次风控评估、规则触发、人工复核、黑名单操作等都会生成一条 RiskEvent，
 * 用于审计追踪、风控仪表盘和事后分析。</p>
 *
 * <ul>
 *   <li>{@link EventType#PAYMENT_EVALUATION} — 支付风控评估</li>
 *   <li>{@link EventType#REFUND_EVALUATION} — 退款风控评估</li>
 *   <li>{@link EventType#RULE_TRIGGERED} — 风控规则触发</li>
 *   <li>{@link EventType#MANUAL_REVIEW} — 人工复核</li>
 *   <li>{@link EventType#BLACKLIST_ACTION} — 黑名单操作</li>
 *   <li>{@link EventType#DEVICE_FINGERPRINT} — 设备指纹事件</li>
 * </ul>
 */
@Entity
@Table(name = "risk_events")
public class RiskEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 事件唯一标识（UUID 去横线格式） */
    @Column(name = "event_id", nullable = false, unique = true, length = 64)
    private String eventId;

    /** 事件类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private EventType eventType;

    /** 商户 ID（可空，系统级事件为 null） */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** 关联订单号 */
    @Column(name = "order_id", length = 64)
    private String orderId;

    /** 付款方地址 */
    @Column(name = "payer_address", length = 66)
    private String payerAddress;

    /** 收款方地址 */
    @Column(name = "payee_address", length = 66)
    private String payeeAddress;

    /** 交易金额 */
    @Column(name = "amount", precision = 36, scale = 8)
    private BigDecimal amount;

    /** 币种 */
    @Column(name = "currency", length = 16)
    private String currency;

    /** 风控决策（APPROVED/REJECTED/PENDING_REVIEW/FROZEN） */
    @Column(name = "risk_decision", length = 32)
    private String riskDecision;

    /** 风控评分（0-100） */
    @Column(name = "risk_score")
    private Integer riskScore;

    /** 触发的规则列表（逗号分隔） */
    @Column(name = "triggered_rules", length = 1024)
    private String triggeredRules;

    /** 事件描述 */
    @Column(name = "description", length = 512)
    private String description;

    /** 关联设备指纹哈希 */
    @Column(name = "fingerprint_hash", length = 128)
    private String fingerprintHash;

    /** IP 地址 */
    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    /** 事件发生时间 */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 记录创建时间（自动维护） */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 乐观锁版本号 */
    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // --- Enumerations ---

    /**
     * 风控事件类型枚举。
     */
    public enum EventType {
        /** 支付风控评估 */
        PAYMENT_EVALUATION,
        /** 退款风控评估 */
        REFUND_EVALUATION,
        /** 风控规则触发 */
        RULE_TRIGGERED,
        /** 人工复核 */
        MANUAL_REVIEW,
        /** 黑名单操作 */
        BLACKLIST_ACTION,
        /** 设备指纹事件 */
        DEVICE_FINGERPRINT
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getPayerAddress() { return payerAddress; }
    public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }

    public String getPayeeAddress() { return payeeAddress; }
    public void setPayeeAddress(String payeeAddress) { this.payeeAddress = payeeAddress; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getRiskDecision() { return riskDecision; }
    public void setRiskDecision(String riskDecision) { this.riskDecision = riskDecision; }

    public Integer getRiskScore() { return riskScore; }
    public void setRiskScore(Integer riskScore) { this.riskScore = riskScore; }

    public String getTriggeredRules() { return triggeredRules; }
    public void setTriggeredRules(String triggeredRules) { this.triggeredRules = triggeredRules; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getFingerprintHash() { return fingerprintHash; }
    public void setFingerprintHash(String fingerprintHash) { this.fingerprintHash = fingerprintHash; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}