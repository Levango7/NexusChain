package org.nexus.gateway.reconciliation.link;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 交易对账审计记录 JPA 实体。
 *
 * <p>记录每日审计的结果，包括正向对账（内部→渠道）和反向对账（渠道→内部）
 * 的匹配数、差异金额和审计结论。</p>
 */
@Entity
@Table(name = "transaction_audit_records")
public class TransactionAuditRecord {

    /** 审计方向 */
    public enum AuditDirection {
        /** 正向对账：内部交易 → 渠道记录 */
        FORWARD,
        /** 反向对账：渠道记录 → 内部交易 */
        REVERSE
    }

    /** 审计结论 */
    public enum AuditConclusion {
        /** 平衡 — 正向和反向均无差异 */
        BALANCED,
        /** 有差异 — 存在未匹配或金额不一致 */
        DISCREPANCY_FOUND,
        /** 审计失败 — 数据异常或系统错误 */
        AUDIT_FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 审计日期 */
    @Column(name = "audit_date", nullable = false)
    private LocalDate auditDate;

    /** 商户 ID（null 表示全量审计） */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** 审计方向 */
    @Enumerated(EnumType.STRING)
    @Column(name = "audit_direction", nullable = false, length = 32)
    private AuditDirection auditDirection;

    /** 内部侧总交易数 */
    @Column(name = "internal_total_count")
    private Long internalTotalCount;

    /** 渠道侧总交易数 */
    @Column(name = "channel_total_count")
    private Long channelTotalCount;

    /** 正向匹配数 */
    @Column(name = "forward_matched_count")
    private Long forwardMatchedCount;

    /** 反向匹配数 */
    @Column(name = "reverse_matched_count")
    private Long reverseMatchedCount;

    /** 差异总数 */
    @Column(name = "discrepancy_count")
    private Long discrepancyCount;

    /** 差异总金额 */
    @Column(name = "discrepancy_amount", precision = 36, scale = 0)
    private BigDecimal discrepancyAmount;

    /** 审计结论 */
    @Enumerated(EnumType.STRING)
    @Column(name = "conclusion", nullable = false, length = 32)
    private AuditConclusion conclusion;

    /** 审计描述 */
    @Column(name = "description", length = 2048)
    private String description;

    /** 审计执行时间 */
    @Column(name = "executed_at", nullable = false)
    private LocalDateTime executedAt;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getAuditDate() { return auditDate; }
    public void setAuditDate(LocalDate auditDate) { this.auditDate = auditDate; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public AuditDirection getAuditDirection() { return auditDirection; }
    public void setAuditDirection(AuditDirection auditDirection) { this.auditDirection = auditDirection; }

    public Long getInternalTotalCount() { return internalTotalCount; }
    public void setInternalTotalCount(Long internalTotalCount) { this.internalTotalCount = internalTotalCount; }

    public Long getChannelTotalCount() { return channelTotalCount; }
    public void setChannelTotalCount(Long channelTotalCount) { this.channelTotalCount = channelTotalCount; }

    public Long getForwardMatchedCount() { return forwardMatchedCount; }
    public void setForwardMatchedCount(Long forwardMatchedCount) { this.forwardMatchedCount = forwardMatchedCount; }

    public Long getReverseMatchedCount() { return reverseMatchedCount; }
    public void setReverseMatchedCount(Long reverseMatchedCount) { this.reverseMatchedCount = reverseMatchedCount; }

    public Long getDiscrepancyCount() { return discrepancyCount; }
    public void setDiscrepancyCount(Long discrepancyCount) { this.discrepancyCount = discrepancyCount; }

    public BigDecimal getDiscrepancyAmount() { return discrepancyAmount; }
    public void setDiscrepancyAmount(BigDecimal discrepancyAmount) { this.discrepancyAmount = discrepancyAmount; }

    public AuditConclusion getConclusion() { return conclusion; }
    public void setConclusion(AuditConclusion conclusion) { this.conclusion = conclusion; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}