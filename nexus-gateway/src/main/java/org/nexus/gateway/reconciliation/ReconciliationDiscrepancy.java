package org.nexus.gateway.reconciliation;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对账差错记录 JPA 实体。
 *
 * <p>记录每一条在自动对账过程中发现的差异，包含差异类型分类、双边金额/状态信息、
 * 处置规则和状态流转。差错生命周期：DISCOVERED → INVESTIGATING → RESOLVED/ESCALATED。</p>
 */
@Entity
@Table(name = "reconciliation_discrepancies")
public class ReconciliationDiscrepancy {

    /**
     * 差异类型分类。
     */
    public enum DiscrepancyType {
        /** 长款：渠道有记录但内部无 → 渠道多收钱 */
        LONG_AMOUNT,
        /** 短款：内部有记录但渠道无 → 内部多记钱 */
        SHORT_AMOUNT,
        /** 金额不一致：双方都有但金额不同 */
        AMOUNT_MISMATCH,
        /** 状态不一致：双方都有但状态不同 */
        STATUS_MISMATCH,
        /** 信息不一致：双方都有但其他信息不同 */
        INFO_MISMATCH
    }

    /**
     * 处置规则。
     */
    public enum ResolutionType {
        /** 自动解决：金额容差范围内 */
        AUTO_RESOLVE,
        /** 人工审核：需要人工介入 */
        MANUAL_REVIEW,
        /** 挂账待查：暂时挂起，待后续调查 */
        PENDING_INVESTIGATION
    }

    /**
     * 差错状态流转。
     */
    public enum DiscrepancyStatus {
        /** 已发现，待处理 */
        DISCOVERED,
        /** 调查中 */
        INVESTIGATING,
        /** 已解决 */
        RESOLVED,
        /** 已升级（需上级介入） */
        ESCALATED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 关联的对账文件记录 ID */
    @Column(name = "reconciliation_file_id")
    private Long reconciliationFileId;

    /** 差异类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", nullable = false, length = 32)
    private DiscrepancyType discrepancyType;

    /** 处置规则 */
    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_type", nullable = false, length = 32)
    private ResolutionType resolutionType;

    /** 差错状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DiscrepancyStatus status = DiscrepancyStatus.DISCOVERED;

    /** 交易 ID（订单号或渠道交易号） */
    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    /** 渠道侧金额 */
    @Column(name = "channel_amount", precision = 36, scale = 0)
    private BigDecimal channelAmount;

    /** 内部侧金额 */
    @Column(name = "internal_amount", precision = 36, scale = 0)
    private BigDecimal internalAmount;

    /** 金额差异（channelAmount - internalAmount 的绝对值） */
    @Column(name = "amount_diff", precision = 36, scale = 0)
    private BigDecimal amountDiff;

    /** 渠道侧状态 */
    @Column(name = "channel_status", length = 32)
    private String channelStatus;

    /** 内部侧状态 */
    @Column(name = "internal_status", length = 32)
    private String internalStatus;

    /** 差错描述 */
    @Column(name = "description", length = 1024)
    private String description;

    /** 解决备注（人工审核时填写） */
    @Column(name = "resolution_note", length = 1024)
    private String resolutionNote;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 解决时间 */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
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

    public Long getReconciliationFileId() { return reconciliationFileId; }
    public void setReconciliationFileId(Long reconciliationFileId) {
        this.reconciliationFileId = reconciliationFileId;
    }

    public DiscrepancyType getDiscrepancyType() { return discrepancyType; }
    public void setDiscrepancyType(DiscrepancyType discrepancyType) {
        this.discrepancyType = discrepancyType;
    }

    public ResolutionType getResolutionType() { return resolutionType; }
    public void setResolutionType(ResolutionType resolutionType) {
        this.resolutionType = resolutionType;
    }

    public DiscrepancyStatus getStatus() { return status; }
    public void setStatus(DiscrepancyStatus status) { this.status = status; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public BigDecimal getChannelAmount() { return channelAmount; }
    public void setChannelAmount(BigDecimal channelAmount) { this.channelAmount = channelAmount; }

    public BigDecimal getInternalAmount() { return internalAmount; }
    public void setInternalAmount(BigDecimal internalAmount) { this.internalAmount = internalAmount; }

    public BigDecimal getAmountDiff() { return amountDiff; }
    public void setAmountDiff(BigDecimal amountDiff) { this.amountDiff = amountDiff; }

    public String getChannelStatus() { return channelStatus; }
    public void setChannelStatus(String channelStatus) { this.channelStatus = channelStatus; }

    public String getInternalStatus() { return internalStatus; }
    public void setInternalStatus(String internalStatus) { this.internalStatus = internalStatus; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
}