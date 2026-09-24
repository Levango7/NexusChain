package org.nexus.gateway.reconciliation;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 挂账资金记录 JPA 实体。
 *
 * <p>对账发现差错时自动创建挂账记录，追踪差错资金的处置过程。
 * 挂账生命周期：PENDING → RESOLVED / WRITTEN_OFF。</p>
 *
 * <p>差错类型映射：
 * <ul>
 *   <li>{@code LONG_PAYMENT}：长款 — 渠道有记录但内部无，渠道多收钱</li>
 *   <li>{@code SHORT_PAYMENT}：短款 — 内部有记录但渠道无，内部多记钱</li>
 *   <li>{@code AMOUNT_MISMATCH}：金额不一致 — 双方都有但金额不同</li>
 *   <li>{@code STATUS_MISMATCH}：状态不一致 — 双方都有但状态不同</li>
 * </ul>
 * </p>
 */
@Entity
@Table(name = "suspense_accounts")
public class SuspenseAccount {

    /**
     * 挂账差错类型。
     */
    public enum DiscrepancyType {
        /** 长款：渠道有记录但内部无 → 渠道多收钱 */
        LONG_PAYMENT,
        /** 短款：内部有记录但渠道无 → 内部多记钱 */
        SHORT_PAYMENT,
        /** 金额不一致：双方都有但金额不同 */
        AMOUNT_MISMATCH,
        /** 状态不一致：双方都有但状态不同 */
        STATUS_MISMATCH
    }

    /**
     * 挂账状态流转。
     */
    public enum SuspenseStatus {
        /** 待处理 — 挂账创建后等待核销或注销 */
        PENDING,
        /** 已核销 — 差错资金已通过人工或自动方式解决 */
        RESOLVED,
        /** 已注销 — 无法追回的资金，经审批后注销 */
        WRITTEN_OFF
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的对账差错记录 ID */
    @Column(name = "discrepancy_id", nullable = false)
    private Long discrepancyId;

    /** 商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 挂账金额 */
    @Column(name = "amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal amount;

    /** 差错类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", nullable = false, length = 32)
    private DiscrepancyType discrepancyType;

    /** 挂账状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SuspenseStatus status = SuspenseStatus.PENDING;

    /** 挂账描述 */
    @Column(name = "description", length = 1024)
    private String description;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 核销/注销时间 */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /** 核销/注销操作人 */
    @Column(name = "resolved_by", length = 64)
    private String resolvedBy;

    /** 核销/注销备注 */
    @Column(name = "resolution_note", length = 1024)
    private String resolutionNote;

    /** 人工处理方案（提交审批时填写） */
    @Column(name = "proposal", length = 1024)
    private String proposal;

    /** 方案提交人 */
    @Column(name = "proposed_by", length = 64)
    private String proposedBy;

    /** 方案审批状态：null=未提交, PENDING=待审批, APPROVED=已通过, REJECTED=已拒绝 */
    @Column(name = "proposal_status", length = 32)
    private String proposalStatus;

    /** 审批人 */
    @Column(name = "approved_by", length = 64)
    private String approvedBy;

    /** 拒绝原因 */
    @Column(name = "rejection_reason", length = 1024)
    private String rejectionReason;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDiscrepancyId() { return discrepancyId; }
    public void setDiscrepancyId(Long discrepancyId) { this.discrepancyId = discrepancyId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public DiscrepancyType getDiscrepancyType() { return discrepancyType; }
    public void setDiscrepancyType(DiscrepancyType discrepancyType) { this.discrepancyType = discrepancyType; }

    public SuspenseStatus getStatus() { return status; }
    public void setStatus(SuspenseStatus status) { this.status = status; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }

    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }

    public String getProposal() { return proposal; }
    public void setProposal(String proposal) { this.proposal = proposal; }

    public String getProposedBy() { return proposedBy; }
    public void setProposedBy(String proposedBy) { this.proposedBy = proposedBy; }

    public String getProposalStatus() { return proposalStatus; }
    public void setProposalStatus(String proposalStatus) { this.proposalStatus = proposalStatus; }

    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
}