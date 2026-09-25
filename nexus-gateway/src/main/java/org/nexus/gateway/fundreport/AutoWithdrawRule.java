package org.nexus.gateway.fundreport;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 自动提现规则实体 — JPA 映射 auto_withdraw_rules 表。
 *
 * <p>商户配置自动提现规则后，{@link AutoWithdrawScheduler} 会按指定频率
 * ({@link WithdrawFrequency}) 定时检查余额，当余额达到阈值时自动触发提现。</p>
 *
 * <p>规则字段说明：</p>
 * <ul>
 *   <li>{@code threshold} — 余额达到此值时触发提现</li>
 *   <li>{@code targetAmount} — 每次提现的目标金额（null 表示提现全部可用余额）</li>
 *   <li>{@code minRetain} — 提现后账户最低保留余额</li>
 *   <li>{@code lastExecutedAt} — 上次执行时间，用于幂等控制防止重复执行</li>
 * </ul>
 */
@Entity
@Table(name = "auto_withdraw_rules")
public class AutoWithdrawRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 提现频率 */
    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 16)
    private WithdrawFrequency frequency;

    /** 触发阈值，余额 >= 此值时自动提现 */
    @Column(name = "threshold", nullable = false, precision = 36, scale = 0)
    private BigDecimal threshold;

    /** 目标提现金额（null 表示提现全部余额） */
    @Column(name = "target_amount", precision = 36, scale = 0)
    private BigDecimal targetAmount;

    /** 最低保留余额 */
    @Column(name = "min_retain", nullable = false, precision = 36, scale = 0)
    private BigDecimal minRetain = BigDecimal.ZERO;

    /** 是否启用 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** 上次执行时间（用于幂等控制） */
    @Column(name = "last_executed_at")
    private LocalDateTime lastExecutedAt;

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
        if (this.enabled == null) {
            this.enabled = true;
        }
        if (this.minRetain == null) {
            this.minRetain = BigDecimal.ZERO;
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

    public WithdrawFrequency getFrequency() { return frequency; }
    public void setFrequency(WithdrawFrequency frequency) { this.frequency = frequency; }

    public BigDecimal getThreshold() { return threshold; }
    public void setThreshold(BigDecimal threshold) { this.threshold = threshold; }

    public BigDecimal getTargetAmount() { return targetAmount; }
    public void setTargetAmount(BigDecimal targetAmount) { this.targetAmount = targetAmount; }

    public BigDecimal getMinRetain() { return minRetain; }
    public void setMinRetain(BigDecimal minRetain) { this.minRetain = minRetain; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public LocalDateTime getLastExecutedAt() { return lastExecutedAt; }
    public void setLastExecutedAt(LocalDateTime lastExecutedAt) { this.lastExecutedAt = lastExecutedAt; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}