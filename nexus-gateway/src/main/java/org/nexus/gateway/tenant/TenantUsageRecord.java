package org.nexus.gateway.tenant;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 租户使用量记录（P4-T6 多租户改造）。
 *
 * <p>按计费周期（如 2026-08）记录每个租户的交易笔数、总金额和总手续费，
 * 用于生成计费账单和运营报表。{@link TenantBillingService#recordUsage}
 * 在支付确认时累加本表记录。</p>
 *
 * <p>唯一键：(tenant_id, period)，确保每个租户每个周期只有一条记录，
 * 多次累加通过 {@code transactionCount}/{@code totalAmount}/{@code totalFee} 累加实现。</p>
 */
@Entity
@Table(name = "tenant_usage_records",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tenant_usage_tenant_period",
                        columnNames = {"tenant_id", "period"})
        },
        indexes = {
                @Index(name = "idx_tenant_usage_period", columnList = "period")
        })
public class TenantUsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务租户 ID。 */
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** 计费周期（格式 yyyy-MM，按月聚合）。 */
    @Column(name = "period", nullable = false, length = 16)
    private String period;

    /** 周期内交易笔数。 */
    @Column(name = "transaction_count", nullable = false)
    private long transactionCount = 0L;

    /** 周期内交易总金额（最小单位）。 */
    @Column(name = "total_amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** 周期内总手续费（最小单位）。 */
    @Column(name = "total_fee", nullable = false, precision = 36, scale = 0)
    private BigDecimal totalFee = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }

    public long getTransactionCount() { return transactionCount; }
    public void setTransactionCount(long transactionCount) { this.transactionCount = transactionCount; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public BigDecimal getTotalFee() { return totalFee; }
    public void setTotalFee(BigDecimal totalFee) { this.totalFee = totalFee; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}