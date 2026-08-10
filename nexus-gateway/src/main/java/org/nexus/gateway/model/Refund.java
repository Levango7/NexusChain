package org.nexus.gateway.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Refund entity representing a refund operation against a paid order.
 *
 * <p>Refunds are executed as on-chain transfers back to the original payer,
 * constructed via the nexus-exchange-wallet signing pipeline.</p>
 */
@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique refund number. */
    @Column(name = "refund_no", unique = true, nullable = false, length = 64)
    private String refundNo;

    /** Original payment order ID. */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** Owning merchant ID. */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** Refund amount (must not exceed original order amount). */
    @Column(name = "amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal amount;

    /** Token symbol (always NEX). */
    @Column(name = "token_symbol", nullable = false, length = 16)
    private String tokenSymbol = "NEX";

    /** Payer wallet address receiving the refund. */
    @Column(name = "receiver_address", nullable = false, length = 66)
    private String receiverAddress;

    /** Merchant wallet address sending the refund. */
    @Column(name = "sender_address", nullable = false, length = 66)
    private String senderAddress;

    /** On-chain refund transaction hash. */
    @Column(name = "chain_tx_hash", length = 128)
    private String chainTxHash;

    /** Refund status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RefundStatus status = RefundStatus.PENDING;

    /** Optional refund reason. */
    @Column(name = "reason", length = 256)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

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
        PENDING, PROCESSING, COMPLETED, FAILED
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

    public String getTokenSymbol() { return tokenSymbol; }
    public void setTokenSymbol(String tokenSymbol) { this.tokenSymbol = tokenSymbol; }

    public String getReceiverAddress() { return receiverAddress; }
    public void setReceiverAddress(String receiverAddress) { this.receiverAddress = receiverAddress; }

    public String getSenderAddress() { return senderAddress; }
    public void setSenderAddress(String senderAddress) { this.senderAddress = senderAddress; }

    public String getChainTxHash() { return chainTxHash; }
    public void setChainTxHash(String chainTxHash) { this.chainTxHash = chainTxHash; }

    public RefundStatus getStatus() { return status; }
    public void setStatus(RefundStatus status) { this.status = status; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
