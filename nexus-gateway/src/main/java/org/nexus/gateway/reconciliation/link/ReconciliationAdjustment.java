package org.nexus.gateway.reconciliation.link;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对账差异调整记录 JPA 实体。
 *
 * <p>记录每一笔对账差异导致的资金调整，包含调整类型、金额、审批状态和执行状态。
 * 调整生命周期：创建 → 审批（自动/人工） → 执行资金调整。</p>
 *
 * <p>审批规则：
 * <ul>
 *   <li>金额 ≤ 1000 元：AUTO_APPROVED，自动执行资金调整</li>
 *   <li>金额 > 1000 元：PENDING_APPROVAL，需人工审批后执行</li>
 * </ul>
 * </p>
 *
 * <p>幂等保证：{@code reference} 字段为唯一键，确保同一业务凭证不重复调整。</p>
 */
@Entity
@Table(name = "reconciliation_adjustments")
public class ReconciliationAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 关联的对账差错记录 ID */
    @Column(name = "discrepancy_id")
    private Long discrepancyId;

    /** 调整类型：CREDIT_ADJUST(加钱) / DEBIT_ADJUST(扣钱) */
    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 32)
    private AdjustmentType adjustmentType;

    /** 调整金额 */
    @Column(name = "amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal amount;

    /** 审批状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 32)
    private ApprovalStatus approvalStatus = ApprovalStatus.PENDING_APPROVAL;

    /** 关联业务凭证（幂等键，唯一） */
    @Column(name = "reference", nullable = false, unique = true, length = 128)
    private String reference;

    /** 调整描述 */
    @Column(name = "description", length = 1024)
    private String description;

    /** 审批人 */
    @Column(name = "approved_by", length = 64)
    private String approvedBy;

    /** 审批时间 */
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /** 拒绝原因 */
    @Column(name = "rejection_reason", length = 1024)
    private String rejectionReason;

    /** 是否已执行资金调整 */
    @Column(name = "executed", nullable = false)
    private Boolean executed = false;

    /** 资金调整执行时间 */
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
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
        if (this.approvalStatus == null) {
            this.approvalStatus = ApprovalStatus.PENDING_APPROVAL;
        }
        if (this.executed == null) {
            this.executed = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public Long getDiscrepancyId() { return discrepancyId; }
    public void setDiscrepancyId(Long discrepancyId) { this.discrepancyId = discrepancyId; }

    public AdjustmentType getAdjustmentType() { return adjustmentType; }
    public void setAdjustmentType(AdjustmentType adjustmentType) { this.adjustmentType = adjustmentType; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public ApprovalStatus getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(ApprovalStatus approvalStatus) { this.approvalStatus = approvalStatus; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }

    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public Boolean getExecuted() { return executed; }
    public void setExecuted(Boolean executed) { this.executed = executed; }

    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}