package org.nexus.gateway.reserve;

import jakarta.persistence.*;
import org.nexus.gateway.account.AccountType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 备付金配置实体 — JPA 映射 reserve_configs 表。
 *
 * <p>配置商户备付金的阈值、自动补充规则和监控参数。核心配置项：</p>
 * <ul>
 *   <li>最低/最高备付金金额 — 备付金合理区间</li>
 *   <li>自动补充 — 当备付金低于阈值时从指定账户自动补充</li>
 *   <li>预警阈值 — 备付金低于预警阈值时设置预警级别</li>
 *   <li>监控开关 — 是否启用备付金监控</li>
 * </ul>
 *
 * <p>并发安全：使用 {@code @Version} 乐观锁。</p>
 */
@Entity
@Table(name = "reserve_configs")
public class ReserveConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商户ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 配置编号，唯一标识 */
    @Column(name = "config_code", unique = true, nullable = false, length = 64)
    private String configCode;

    /** 最低备付金金额 */
    @Column(name = "min_reserve_amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal minReserveAmount = BigDecimal.ZERO;

    /** 最高备付金金额 */
    @Column(name = "max_reserve_amount", precision = 36, scale = 0)
    private BigDecimal maxReserveAmount;

    /** 是否自动补充 */
    @Column(name = "auto_replenish_enabled", nullable = false)
    private Boolean autoReplenishEnabled = false;

    /** 自动补充触发阈值 */
    @Column(name = "replenish_threshold", precision = 36, scale = 0)
    private BigDecimal replenishThreshold;

    /** 每次补充金额 */
    @Column(name = "replenish_amount", precision = 36, scale = 0)
    private BigDecimal replenishAmount;

    /** 补充资金来源账户类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "replenish_source_account_type", nullable = false, length = 32)
    private AccountType replenishSourceAccountType = AccountType.BALANCE;

    /** 预警阈值 */
    @Column(name = "alert_threshold", precision = 36, scale = 0)
    private BigDecimal alertThreshold;

    /** 预警级别: WARNING/CRITICAL/EMERGENCY */
    @Column(name = "alert_flag", length = 32)
    private String alertFlag;

    /** 是否启用监控 */
    @Column(name = "monitoring_enabled", nullable = false)
    private Boolean monitoringEnabled = true;

    /** 配置描述 */
    @Column(name = "description", length = 512)
    private String description;

    /** 多租户隔离键 */
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    /** 乐观锁版本号 */
    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.minReserveAmount == null) {
            this.minReserveAmount = BigDecimal.ZERO;
        }
        if (this.autoReplenishEnabled == null) {
            this.autoReplenishEnabled = false;
        }
        if (this.replenishSourceAccountType == null) {
            this.replenishSourceAccountType = AccountType.BALANCE;
        }
        if (this.monitoringEnabled == null) {
            this.monitoringEnabled = true;
        }
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

    public String getConfigCode() { return configCode; }
    public void setConfigCode(String configCode) { this.configCode = configCode; }

    public BigDecimal getMinReserveAmount() { return minReserveAmount; }
    public void setMinReserveAmount(BigDecimal minReserveAmount) { this.minReserveAmount = minReserveAmount; }

    public BigDecimal getMaxReserveAmount() { return maxReserveAmount; }
    public void setMaxReserveAmount(BigDecimal maxReserveAmount) { this.maxReserveAmount = maxReserveAmount; }

    public Boolean getAutoReplenishEnabled() { return autoReplenishEnabled; }
    public void setAutoReplenishEnabled(Boolean autoReplenishEnabled) { this.autoReplenishEnabled = autoReplenishEnabled; }

    public BigDecimal getReplenishThreshold() { return replenishThreshold; }
    public void setReplenishThreshold(BigDecimal replenishThreshold) { this.replenishThreshold = replenishThreshold; }

    public BigDecimal getReplenishAmount() { return replenishAmount; }
    public void setReplenishAmount(BigDecimal replenishAmount) { this.replenishAmount = replenishAmount; }

    public AccountType getReplenishSourceAccountType() { return replenishSourceAccountType; }
    public void setReplenishSourceAccountType(AccountType replenishSourceAccountType) { this.replenishSourceAccountType = replenishSourceAccountType; }

    public BigDecimal getAlertThreshold() { return alertThreshold; }
    public void setAlertThreshold(BigDecimal alertThreshold) { this.alertThreshold = alertThreshold; }

    public String getAlertFlag() { return alertFlag; }
    public void setAlertFlag(String alertFlag) { this.alertFlag = alertFlag; }

    public Boolean getMonitoringEnabled() { return monitoringEnabled; }
    public void setMonitoringEnabled(Boolean monitoringEnabled) { this.monitoringEnabled = monitoringEnabled; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}