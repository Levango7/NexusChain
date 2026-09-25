package org.nexus.gateway.risk.link;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 大额交易拦截实体 — 记录被拦截的大额交易，等待人工审核或自动超时升级告警。
 *
 * <p>当交易金额超过预设阈值时，系统自动拦截交易并创建拦截记录。
 * 拦截后进入 PENDING_REVIEW 状态，等待人工审核（APPROVED/REJECTED）。
 * 超过 24 小时未审核的记录自动升级为 TIMEOUT_ESCALATED 告警状态。</p>
 */
@Entity
@Table(name = "large_transaction_interceptions",
        uniqueConstraints = @UniqueConstraint(name = "idx_lti_interception_id",
                columnNames = {"interception_id"}))
public class LargeTransactionInterception {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 拦截记录唯一标识 */
    @Column(name = "interception_id", nullable = false, unique = true, length = 64)
    private String interceptionId;

    /** 商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 关联订单号 */
    @Column(name = "order_id", length = 64)
    private String orderId;

    /** 交易金额 */
    @Column(name = "amount", nullable = false, precision = 36, scale = 8)
    private BigDecimal amount;

    /** 币种 */
    @Column(name = "currency", length = 16)
    private String currency = "USD";

    /** 触发拦截的阈值 */
    @Column(name = "threshold", nullable = false, precision = 36, scale = 8)
    private BigDecimal threshold;

    /** 拦截状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "interception_status", nullable = false, length = 32)
    private InterceptionStatus interceptionStatus = InterceptionStatus.PENDING_REVIEW;

    /** 关联风控事件 ID */
    @Column(name = "risk_event_id", length = 64)
    private String riskEventId;

    /** 审核人 ID */
    @Column(name = "reviewer_id")
    private Long reviewerId;

    /** 审核意见 */
    @Column(name = "review_comment", length = 512)
    private String reviewComment;

    /** 审核时间 */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** 超时时间（24h 后） */
    @Column(name = "timeout_at")
    private LocalDateTime timeoutAt;

    /** 升级告警时间 */
    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 乐观锁版本号 */
    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.timeoutAt == null) {
            this.timeoutAt = now.plusHours(24);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getInterceptionId() { return interceptionId; }
    public void setInterceptionId(String interceptionId) { this.interceptionId = interceptionId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getThreshold() { return threshold; }
    public void setThreshold(BigDecimal threshold) { this.threshold = threshold; }

    public InterceptionStatus getInterceptionStatus() { return interceptionStatus; }
    public void setInterceptionStatus(InterceptionStatus interceptionStatus) { this.interceptionStatus = interceptionStatus; }

    public String getRiskEventId() { return riskEventId; }
    public void setRiskEventId(String riskEventId) { this.riskEventId = riskEventId; }

    public Long getReviewerId() { return reviewerId; }
    public void setReviewerId(Long reviewerId) { this.reviewerId = reviewerId; }

    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }

    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }

    public LocalDateTime getTimeoutAt() { return timeoutAt; }
    public void setTimeoutAt(LocalDateTime timeoutAt) { this.timeoutAt = timeoutAt; }

    public LocalDateTime getEscalatedAt() { return escalatedAt; }
    public void setEscalatedAt(LocalDateTime escalatedAt) { this.escalatedAt = escalatedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}