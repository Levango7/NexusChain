package org.nexus.gateway.orchestration.settlement;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 链上结算确认记录实体（Wave 9-C1-1）。
 *
 * <p>持久化每一笔链上结算交易的确认追踪状态，支持确认状态查询 API、
 * 超时自动重试、告警以及最终性状态更新。</p>
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code id}：自增主键</li>
 *   <li>{@code paymentId}：关联的支付 ID（逻辑外键 → orchestrated_payments.id）</li>
 *   <li>{@code txHash}：链上交易哈希</li>
 *   <li>{@code confirmations}：当前链上确认数</li>
 *   <li>{@code requiredConfirmations}：所需确认数阈值（从 FinalityService 获取）</li>
 *   <li>{@code status}：确认状态（PENDING/CONFIRMED/TIMED_OUT/FAILED）</li>
 *   <li>{@code confirmedAt}：确认完成时间（仅 CONFIRMED 状态有值）</li>
 *   <li>{@code createdAt}：记录创建时间</li>
 *   <li>{@code lastCheckedAt}：最后一次查询链上状态的时间</li>
 *   <li>{@code retryCount}：已重试次数</li>
 *   <li>{@code errorMessage}：错误信息（超时或失败时记录）</li>
 * </ul>
 *
 * @since Wave 9-C1-1 链上结算确认
 */
@Entity
@Table(name = "settlement_confirmation_records", indexes = {
        @Index(name = "idx_scr_payment_id", columnList = "paymentId"),
        @Index(name = "idx_scr_tx_hash", columnList = "txHash"),
        @Index(name = "idx_scr_status", columnList = "status"),
        @Index(name = "idx_scr_last_checked", columnList = "lastCheckedAt")
})
public class SettlementConfirmationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String paymentId;

    @Column(nullable = false, length = 128)
    private String txHash;

    @Column(nullable = false)
    private long confirmations;

    @Column(nullable = false)
    private long requiredConfirmations;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private SettlementConfirmationStatus status;

    private Instant confirmedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant lastCheckedAt;

    @Column(nullable = false)
    private int retryCount;

    @Column(length = 1024)
    private String errorMessage;

    @Version
    private Long version;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = SettlementConfirmationStatus.PENDING;
        if (lastCheckedAt == null) lastCheckedAt = createdAt;
    }

    // === Getters and Setters ===

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public String getTxHash() { return txHash; }
    public void setTxHash(String txHash) { this.txHash = txHash; }

    public long getConfirmations() { return confirmations; }
    public void setConfirmations(long confirmations) { this.confirmations = confirmations; }

    public long getRequiredConfirmations() { return requiredConfirmations; }
    public void setRequiredConfirmations(long requiredConfirmations) { this.requiredConfirmations = requiredConfirmations; }

    public SettlementConfirmationStatus getStatus() { return status; }
    public void setStatus(SettlementConfirmationStatus status) { this.status = status; }

    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}