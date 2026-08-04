package org.nexus.gateway.refund;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Refund request entity representing a refund that flows through an approval
 * workflow before execution.
 *
 * <p>Lifecycle: {@code PENDING -> APPROVED -> EXECUTED} or
 * {@code PENDING -> REJECTED} or {@code APPROVED -> FAILED}.</p>
 */
@Entity
@Table(name = "refund_requests")
public class RefundRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique refund number. */
    @Column(name = "refund_no", unique = true, nullable = false, length = 64)
    private String refundNo;

    /** Original order ID being refunded. */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** Owning merchant ID. */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** Refund amount in the smallest unit of the token. */
    @Column(name = "amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal amount;

    /** Free-text refund reason. */
    @Column(name = "reason", length = 256)
    private String reason;

    /** Current refund status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RefundStatus status = RefundStatus.PENDING;

    /** ID of the approver who approved or rejected the refund. */
    @Column(name = "approver_id")
    private String approverId;

    /** Rejection reason, populated when status = REJECTED. */
    @Column(name = "rejection_reason", length = 256)
    private String rejectionReason;

    /** On-chain refund transaction hash, set once executed. */
    @Column(name = "chain_tx_hash", length = 128)
    private String chainTxHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Timestamp when the refund was approved or rejected. */
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /** Timestamp when the refund was executed on-chain. */
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /** Optimistic lock version for concurrent safety. */
    @Version
    @Column(name = "version")
    private Long version;

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

    // --- Enumerations ---

    public enum RefundStatus {
        PENDING, APPROVED, REJECTED, EXECUTED, FAILED
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public RefundStatus getStatus() { return status; }
    public void setStatus(RefundStatus status) { this.status = status; }

    public String getApproverId() { return approverId; }
    public void setApproverId(String approverId) { this.approverId = approverId; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public String getChainTxHash() { return chainTxHash; }
    public void setChainTxHash(String chainTxHash) { this.chainTxHash = chainTxHash; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }

    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}