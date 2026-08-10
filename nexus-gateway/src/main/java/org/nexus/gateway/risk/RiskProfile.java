package org.nexus.gateway.risk;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Risk profile entity capturing the per-merchant risk configuration and limits.
 *
 * <p>The risk profile is consulted by the payment risk service to enforce
 * per-transaction, daily, and monthly limits, and to honor blacklist status.</p>
 */
@Entity
@Table(name = "risk_profiles")
public class RiskProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Merchant ID this profile belongs to. */
    @Column(name = "merchant_id", unique = true, nullable = false)
    private Long merchantId;

    /** Risk level (0 = lowest, 100 = highest). */
    @Column(name = "risk_level", nullable = false)
    private Integer riskLevel = 0;

    /** Maximum amount allowed per single transaction. */
    @Column(name = "per_tx_limit", precision = 36, scale = 0)
    private BigDecimal perTxLimit;

    /** Maximum aggregate amount allowed per day. */
    @Column(name = "daily_limit", precision = 36, scale = 0)
    private BigDecimal dailyLimit;

    /** Maximum aggregate amount allowed per month. */
    @Column(name = "monthly_limit", precision = 36, scale = 0)
    private BigDecimal monthlyLimit;

    /** Whether the merchant is blacklisted. */
    @Column(name = "blacklisted", nullable = false)
    private Boolean blacklisted = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public Integer getRiskLevel() { return riskLevel; }
    public void setRiskLevel(Integer riskLevel) { this.riskLevel = riskLevel; }

    public BigDecimal getPerTxLimit() { return perTxLimit; }
    public void setPerTxLimit(BigDecimal perTxLimit) { this.perTxLimit = perTxLimit; }

    public BigDecimal getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(BigDecimal dailyLimit) { this.dailyLimit = dailyLimit; }

    public BigDecimal getMonthlyLimit() { return monthlyLimit; }
    public void setMonthlyLimit(BigDecimal monthlyLimit) { this.monthlyLimit = monthlyLimit; }

    public Boolean getBlacklisted() { return blacklisted; }
    public void setBlacklisted(Boolean blacklisted) { this.blacklisted = blacklisted; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}