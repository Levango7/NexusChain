package org.nexus.gateway.reconciliation.compensation;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 自动补偿记录 JPA 实体 — 记录补偿操作的执行过程和结果。
 */
@Entity
@Table(name = "compensation_records")
public class CompensationRecord {

    /** 补偿类型 */
    public enum CompensationType {
        /** 渠道退款 */
        REFUND,
        /** 内部账户调账 */
        INTERNAL_ADJUST
    }

    /** 补偿状态 */
    public enum CompensationStatus {
        /** 待执行 */
        PENDING,
        /** 执行中 */
        EXECUTING,
        /** 成功（**真实执行**，资金已动） */
        SUCCESS,
        /**
         * 模拟执行成功（sandbox 模式，**资金未动**）。
         *
         * <p>2026-10-12 新增：此前 sandbox 执行也记 {@link #SUCCESS}，与真实执行在数据上
         * 无法区分——运营看到 SUCCESS 会以为商户已补偿，实际资金未动。单列一态让数据不再
         * 撒谎：补偿统计（{@code ReconciliationReportService} 只计 SUCCESS）与差错闭环
         * （{@code AutoCompensationService#updateDiscrepancyStatus} 只认 SUCCESS）
         * 都自然排除模拟态。</p>
         */
        SIMULATED,
        /** 失败 */
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "discrepancy_id", unique = true, nullable = false)
    private Long discrepancyId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "compensation_type", length = 32, nullable = false)
    private CompensationType compensationType;

    @Column(name = "amount", precision = 36, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private CompensationStatus status = CompensationStatus.PENDING;

    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "account_transaction_ref", length = 128)
    private String accountTransactionRef;

    @Column(name = "channel_refund_ref", length = 128)
    private String channelRefundRef;

    @Column(name = "failure_reason", length = 1024)
    private String failureReason;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    // === Getters & Setters ===

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDiscrepancyId() { return discrepancyId; }
    public void setDiscrepancyId(Long discrepancyId) { this.discrepancyId = discrepancyId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public CompensationType getCompensationType() { return compensationType; }
    public void setCompensationType(CompensationType compensationType) { this.compensationType = compensationType; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public CompensationStatus getStatus() { return status; }
    public void setStatus(CompensationStatus status) { this.status = status; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getAccountTransactionRef() { return accountTransactionRef; }
    public void setAccountTransactionRef(String accountTransactionRef) { this.accountTransactionRef = accountTransactionRef; }

    public String getChannelRefundRef() { return channelRefundRef; }
    public void setChannelRefundRef(String channelRefundRef) { this.channelRefundRef = channelRefundRef; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }
}