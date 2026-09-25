package org.nexus.gateway.fundtransfer;

import jakarta.persistence.*;
import org.nexus.gateway.account.AccountType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资金调拨规则实体 — JPA 映射 transfer_rules 表。
 *
 * <p>定义自动或手动资金调拨的规则配置。每条规则指定：</p>
 * <ul>
 *   <li>转出/转入账户类型（如 BALANCE → RESERVE）</li>
 *   <li>触发方式（余额阈值/定时/手动）</li>
 *   <li>调拨金额计算方式（固定/百分比/全部）</li>
 * </ul>
 *
 * <p>并发安全：使用 {@code @Version} 乐观锁，规则配置变更时自动检查版本号。</p>
 */
@Entity
@Table(name = "transfer_rules")
public class TransferRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则名称 */
    @Column(name = "rule_name", nullable = false, length = 128)
    private String ruleName;

    /** 规则编号，唯一标识 */
    @Column(name = "rule_code", unique = true, nullable = false, length = 64)
    private String ruleCode;

    /** 转出账户类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_account_type", nullable = false, length = 32)
    private AccountType fromAccountType;

    /** 转入账户类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "to_account_type", nullable = false, length = 32)
    private AccountType toAccountType;

    /** 触发类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 32)
    private TriggerType triggerType;

    /** 阈值金额（BALANCE_THRESHOLD 类型使用） */
    @Column(name = "threshold_amount", precision = 36, scale = 0)
    private BigDecimal thresholdAmount;

    /** 阈值方向：ABOVE(超过阈值)/BELOW(低于阈值) */
    @Column(name = "threshold_direction", length = 16)
    private String thresholdDirection;

    /** 调拨金额类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_amount_type", nullable = false, length = 32)
    private TransferAmountType transferAmountType;

    /** 固定金额（FIXED 类型使用） */
    @Column(name = "transfer_amount", precision = 36, scale = 0)
    private BigDecimal transferAmount;

    /** 百分比（PERCENTAGE 类型使用，0-100） */
    @Column(name = "transfer_percentage", precision = 8, scale = 4)
    private BigDecimal transferPercentage;

    /** 定时表达式（SCHEDULED 类型使用） */
    @Column(name = "cron_expression", length = 64)
    private String cronExpression;

    /** 是否启用 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** 规则描述 */
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
        if (this.enabled == null) {
            this.enabled = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }

    public AccountType getFromAccountType() { return fromAccountType; }
    public void setFromAccountType(AccountType fromAccountType) { this.fromAccountType = fromAccountType; }

    public AccountType getToAccountType() { return toAccountType; }
    public void setToAccountType(AccountType toAccountType) { this.toAccountType = toAccountType; }

    public TriggerType getTriggerType() { return triggerType; }
    public void setTriggerType(TriggerType triggerType) { this.triggerType = triggerType; }

    public BigDecimal getThresholdAmount() { return thresholdAmount; }
    public void setThresholdAmount(BigDecimal thresholdAmount) { this.thresholdAmount = thresholdAmount; }

    public String getThresholdDirection() { return thresholdDirection; }
    public void setThresholdDirection(String thresholdDirection) { this.thresholdDirection = thresholdDirection; }

    public TransferAmountType getTransferAmountType() { return transferAmountType; }
    public void setTransferAmountType(TransferAmountType transferAmountType) { this.transferAmountType = transferAmountType; }

    public BigDecimal getTransferAmount() { return transferAmount; }
    public void setTransferAmount(BigDecimal transferAmount) { this.transferAmount = transferAmount; }

    public BigDecimal getTransferPercentage() { return transferPercentage; }
    public void setTransferPercentage(BigDecimal transferPercentage) { this.transferPercentage = transferPercentage; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

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