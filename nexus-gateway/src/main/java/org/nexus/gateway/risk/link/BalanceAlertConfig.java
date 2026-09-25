package org.nexus.gateway.risk.link;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 余额预警配置实体 — 为商户配置余额预警阈值。
 *
 * <p>每个商户一条配置记录（merchantId 唯一约束）。三个阈值必须满足：
 * {@code warningThreshold > criticalThreshold > emergencyThreshold > 0}。</p>
 *
 * <p>当商户余额低于对应阈值时，触发相应级别的预警：</p>
 * <ul>
 *   <li>余额 < warningThreshold → WARNING 级别</li>
 *   <li>余额 < criticalThreshold → CRITICAL 级别</li>
 *   <li>余额 < emergencyThreshold → EMERGENCY 级别</li>
 * </ul>
 */
@Entity
@Table(name = "balance_alert_configs",
        uniqueConstraints = @UniqueConstraint(name = "idx_bac_merchant_id",
                columnNames = {"merchant_id"}))
public class BalanceAlertConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 预警阈值（余额低于此值触发 WARNING） */
    @Column(name = "warning_threshold", nullable = false, precision = 36, scale = 8)
    private java.math.BigDecimal warningThreshold;

    /** 严重阈值（余额低于此值触发 CRITICAL） */
    @Column(name = "critical_threshold", nullable = false, precision = 36, scale = 8)
    private java.math.BigDecimal criticalThreshold;

    /** 紧急阈值（余额低于此值触发 EMERGENCY） */
    @Column(name = "emergency_threshold", nullable = false, precision = 36, scale = 8)
    private java.math.BigDecimal emergencyThreshold;

    /** 是否启用预警 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** 通知渠道（逗号分隔：WEBHOOK,EMAIL,SMS） */
    @Column(name = "notify_channels", length = 256)
    private String notifyChannels = "WEBHOOK";

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

    public java.math.BigDecimal getWarningThreshold() { return warningThreshold; }
    public void setWarningThreshold(java.math.BigDecimal warningThreshold) { this.warningThreshold = warningThreshold; }

    public java.math.BigDecimal getCriticalThreshold() { return criticalThreshold; }
    public void setCriticalThreshold(java.math.BigDecimal criticalThreshold) { this.criticalThreshold = criticalThreshold; }

    public java.math.BigDecimal getEmergencyThreshold() { return emergencyThreshold; }
    public void setEmergencyThreshold(java.math.BigDecimal emergencyThreshold) { this.emergencyThreshold = emergencyThreshold; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getNotifyChannels() { return notifyChannels; }
    public void setNotifyChannels(String notifyChannels) { this.notifyChannels = notifyChannels; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}