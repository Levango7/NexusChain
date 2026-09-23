package org.nexus.gateway.settlement;

import jakarta.persistence.*;
import org.nexus.gateway.clearing.SettlementPeriod;

import java.time.LocalDateTime;

/**
 * 商户结算周期配置实体。
 *
 * <p>每个商户可配置独立的结算周期（T0/T1/T2/T3/WEEKLY/MONTHLY/CUSTOM），
 * 无配置时默认 T1（次日结算）。CUSTOM 模式需配合 {@link #customDays} 使用，
 * 支持 1-90 天的自定义结算周期。</p>
 *
 * <p>使用 {@link GenerationType#IDENTITY} 主键策略，确保 H2 与 PostgreSQL
 * 行为一致（来源：H2-PostgreSQL 双库 JPA ID 兼容经验）。</p>
 */
@Entity
@Table(name = "merchant_settlement_configs")
public class MerchantSettlementConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商户 ID（关联 merchants 表，唯一） */
    @Column(name = "merchant_id", nullable = false, unique = true)
    private Long merchantId;

    /** 结算周期枚举，默认 T1 */
    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_period", nullable = false, length = 16)
    private SettlementPeriod settlementPeriod = SettlementPeriod.T1;

    /** 自定义结算天数（仅 CUSTOM 模式使用，1-90） */
    @Column(name = "custom_days")
    private Integer customDays;

    /** 是否自动触发结算，默认 true */
    @Column(name = "auto_settle_enabled", nullable = false)
    private boolean autoSettleEnabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 乐观锁版本号 */
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

    public SettlementPeriod getSettlementPeriod() { return settlementPeriod; }
    public void setSettlementPeriod(SettlementPeriod settlementPeriod) { this.settlementPeriod = settlementPeriod; }

    public Integer getCustomDays() { return customDays; }
    public void setCustomDays(Integer customDays) { this.customDays = customDays; }

    public boolean isAutoSettleEnabled() { return autoSettleEnabled; }
    public void setAutoSettleEnabled(boolean autoSettleEnabled) { this.autoSettleEnabled = autoSettleEnabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}