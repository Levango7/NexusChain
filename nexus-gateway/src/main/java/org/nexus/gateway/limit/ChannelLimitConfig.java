package org.nexus.gateway.limit;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 渠道限额配置实体。
 *
 * <p>为每个支付渠道配置独立的限额策略，包括单笔限额、日累计限额、月累计限额。
 * 同一商户可为不同渠道配置不同的限额规则。</p>
 *
 * <p>渠道类型包括：ALIPAY、WECHAT、BANK_CARD、NEX_CHAIN 等。</p>
 */
@Entity
@Table(name = "channel_limit_configs",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_channel_limit_merchant_channel",
                columnNames = {"merchant_id", "channel_type"}))
public class ChannelLimitConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 渠道类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 32)
    private ChannelType channelType;

    /** 单笔最大金额，{@code null} 表示无限制 */
    @Column(name = "single_limit", precision = 36, scale = 8)
    private BigDecimal singleLimit;

    /** 日累计最大金额，{@code null} 表示无限制 */
    @Column(name = "daily_cumulative_limit", precision = 36, scale = 8)
    private BigDecimal dailyCumulativeLimit;

    /** 月累计最大金额，{@code null} 表示无限制 */
    @Column(name = "monthly_cumulative_limit", precision = 36, scale = 8)
    private BigDecimal monthlyCumulativeLimit;

    /** 是否启用，默认 true */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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

    /**
     * 支付渠道类型枚举。
     */
    public enum ChannelType {
        /** 支付宝 */
        ALIPAY,
        /** 微信支付 */
        WECHAT,
        /** 银行卡 */
        BANK_CARD,
        /** NEX 链上支付 */
        NEX_CHAIN,
        /** 其他渠道 */
        OTHER
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public ChannelType getChannelType() { return channelType; }
    public void setChannelType(ChannelType channelType) { this.channelType = channelType; }

    public BigDecimal getSingleLimit() { return singleLimit; }
    public void setSingleLimit(BigDecimal singleLimit) { this.singleLimit = singleLimit; }

    public BigDecimal getDailyCumulativeLimit() { return dailyCumulativeLimit; }
    public void setDailyCumulativeLimit(BigDecimal dailyCumulativeLimit) {
        this.dailyCumulativeLimit = dailyCumulativeLimit;
    }

    public BigDecimal getMonthlyCumulativeLimit() { return monthlyCumulativeLimit; }
    public void setMonthlyCumulativeLimit(BigDecimal monthlyCumulativeLimit) {
        this.monthlyCumulativeLimit = monthlyCumulativeLimit;
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