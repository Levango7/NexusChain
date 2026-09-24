package org.nexus.gateway.limit;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商户业务级限额配置实体。
 *
 * <p>每个商户对应一条限额配置记录，支持单笔金额限制、日/月累计金额限制、
 * 日/月交易笔数限制。所有限额字段为 {@code null} 时表示该维度不限制。</p>
 *
 * <p>与 {@link org.nexus.gateway.tenant.TenantConfig#maxPaymentAmount}（租户级单笔上限）
 * 不同，本实体提供商户级别的精细化限额策略。</p>
 */
@Entity
@Table(name = "merchant_limit_configs")
public class MerchantLimitConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联 merchants 表的商户 ID，唯一约束。 */
    @Column(name = "merchant_id", nullable = false, unique = true)
    private Long merchantId;

    /** 单笔最小金额，{@code null} 表示无限制。 */
    @Column(name = "single_transaction_min_amount", precision = 36, scale = 8)
    private BigDecimal singleTransactionMinAmount;

    /** 单笔最大金额，{@code null} 表示无限制。 */
    @Column(name = "single_transaction_max_amount", precision = 36, scale = 8)
    private BigDecimal singleTransactionMaxAmount;

    /** 日累计最大金额，{@code null} 表示无限制。 */
    @Column(name = "daily_accumulated_max_amount", precision = 36, scale = 8)
    private BigDecimal dailyAccumulatedMaxAmount;

    /** 月累计最大金额，{@code null} 表示无限制。 */
    @Column(name = "monthly_accumulated_max_amount", precision = 36, scale = 8)
    private BigDecimal monthlyAccumulatedMaxAmount;

    /** 年单笔最大金额，{@code null} 表示无限制。 */
    @Column(name = "annual_single_limit", precision = 36, scale = 8)
    private BigDecimal annualSingleLimit;

    /** 年累计最大金额，{@code null} 表示无限制。 */
    @Column(name = "annual_cumulative_limit", precision = 36, scale = 8)
    private BigDecimal annualCumulativeLimit;

    /** 日最大交易笔数，{@code null} 表示无限制。 */
    @Column(name = "daily_max_transaction_count")
    private Integer dailyMaxTransactionCount;

    /** 月最大交易笔数，{@code null} 表示无限制。 */
    @Column(name = "monthly_max_transaction_count")
    private Integer monthlyMaxTransactionCount;

    /** 是否启用，默认 true。 */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 乐观锁版本号。 */
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

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public BigDecimal getSingleTransactionMinAmount() { return singleTransactionMinAmount; }
    public void setSingleTransactionMinAmount(BigDecimal singleTransactionMinAmount) {
        this.singleTransactionMinAmount = singleTransactionMinAmount;
    }

    public BigDecimal getSingleTransactionMaxAmount() { return singleTransactionMaxAmount; }
    public void setSingleTransactionMaxAmount(BigDecimal singleTransactionMaxAmount) {
        this.singleTransactionMaxAmount = singleTransactionMaxAmount;
    }

    public BigDecimal getDailyAccumulatedMaxAmount() { return dailyAccumulatedMaxAmount; }
    public void setDailyAccumulatedMaxAmount(BigDecimal dailyAccumulatedMaxAmount) {
        this.dailyAccumulatedMaxAmount = dailyAccumulatedMaxAmount;
    }

    public BigDecimal getMonthlyAccumulatedMaxAmount() { return monthlyAccumulatedMaxAmount; }
    public void setMonthlyAccumulatedMaxAmount(BigDecimal monthlyAccumulatedMaxAmount) {
        this.monthlyAccumulatedMaxAmount = monthlyAccumulatedMaxAmount;
    }

    public BigDecimal getAnnualSingleLimit() { return annualSingleLimit; }
    public void setAnnualSingleLimit(BigDecimal annualSingleLimit) {
        this.annualSingleLimit = annualSingleLimit;
    }

    public BigDecimal getAnnualCumulativeLimit() { return annualCumulativeLimit; }
    public void setAnnualCumulativeLimit(BigDecimal annualCumulativeLimit) {
        this.annualCumulativeLimit = annualCumulativeLimit;
    }

    public Integer getDailyMaxTransactionCount() { return dailyMaxTransactionCount; }
    public void setDailyMaxTransactionCount(Integer dailyMaxTransactionCount) {
        this.dailyMaxTransactionCount = dailyMaxTransactionCount;
    }

    public Integer getMonthlyMaxTransactionCount() { return monthlyMaxTransactionCount; }
    public void setMonthlyMaxTransactionCount(Integer monthlyMaxTransactionCount) {
        this.monthlyMaxTransactionCount = monthlyMaxTransactionCount;
    }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}