package org.nexus.gateway.clearing;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Settlement batch entity representing a collection of captured transactions
 * to be settled to a merchant for a given period.
 *
 * <p>A batch transitions through the lifecycle:
 * {@code OPEN -> EXECUTING -> COMPLETED} or {@code OPEN -> FAILED}.</p>
 */
@Entity
@Table(name = "settlement_batches")
public class SettlementBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique batch number shown to the merchant. */
    @Column(name = "batch_no", unique = true, nullable = false, length = 64)
    private String batchNo;

    /** Owning merchant ID. */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** Settlement period for this batch. */
    @Enumerated(EnumType.STRING)
    @Column(name = "period", nullable = false, length = 16)
    private SettlementPeriod period;

    /** Total gross amount of all transactions in the batch. */
    @Column(name = "total_amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** Total fee deducted from the gross amount. */
    @Column(name = "fee_amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal feeAmount = BigDecimal.ZERO;

    /** Net amount settled to the merchant (total - fee). */
    @Column(name = "net_amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal netAmount = BigDecimal.ZERO;

    /** Current batch status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private BatchStatus status = BatchStatus.OPEN;

    /** On-chain settlement transaction hash, set once executed. */
    @Column(name = "chain_tx_hash", length = 128)
    private String chainTxHash;

    /** Settlement window start (inclusive). */
    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    /** Settlement window end (exclusive). */
    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    /** IDs of the transactions included in this batch (stored as a comma-separated string). */
    @Column(name = "transaction_ids", length = 4096)
    private String transactionIdsCsv;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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

    public enum BatchStatus {
        OPEN, EXECUTING, COMPLETED, FAILED
    }

    // --- Helpers ---

    /**
     * Parse the comma-separated transaction IDs into a list.
     *
     * @return list of transaction IDs; empty if none stored
     */
    public List<Long> getTransactionList() {
        List<Long> result = new ArrayList<>();
        if (transactionIdsCsv == null || transactionIdsCsv.isBlank()) {
            return result;
        }
        for (String token : transactionIdsCsv.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                result.add(Long.parseLong(trimmed));
            }
        }
        return result;
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public SettlementPeriod getPeriod() { return period; }
    public void setPeriod(SettlementPeriod period) { this.period = period; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public BigDecimal getFeeAmount() { return feeAmount; }
    public void setFeeAmount(BigDecimal feeAmount) { this.feeAmount = feeAmount; }

    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }

    public BatchStatus getStatus() { return status; }
    public void setStatus(BatchStatus status) { this.status = status; }

    public String getChainTxHash() { return chainTxHash; }
    public void setChainTxHash(String chainTxHash) { this.chainTxHash = chainTxHash; }

    public LocalDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalDateTime windowStart) { this.windowStart = windowStart; }

    public LocalDateTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalDateTime windowEnd) { this.windowEnd = windowEnd; }

    public String getTransactionIdsCsv() { return transactionIdsCsv; }
    public void setTransactionIdsCsv(String transactionIdsCsv) { this.transactionIdsCsv = transactionIdsCsv; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}