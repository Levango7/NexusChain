package org.nexus.gateway.subscription;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 订阅计划实体（P4-T8 订阅与循环计费引擎）。
 *
 * <p>由商户预先配置的可复用计费模板，包含周期、试用期、金额、币种、
 * 功能特性等。一个计划可被多个 {@link SubscriptionEntity} 引用。</p>
 *
 * <p>表名 {@code subscription_plans}，与现有 {@code subscriptions} 表分离，
 * 避免与 P1 简单订阅模型冲突。</p>
 */
@Entity
@Table(name = "subscription_plans")
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 计划业务编号（全局唯一，商户可读）。 */
    @Column(name = "plan_id", unique = true, nullable = false, length = 64)
    private String planId;

    /** 计划名称。 */
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** 计费周期。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false, length = 16)
    private BillingPeriod billingPeriod;

    /** 试用期天数，0 表示无试用期。 */
    @Column(name = "trial_period_days", nullable = false)
    private int trialPeriodDays = 0;

    /** 每周期扣款金额（最小单位）。 */
    @Column(name = "amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal amount;

    /** 币种（默认 NEX）。 */
    @Column(name = "currency", nullable = false, length = 16)
    private String currency = "NEX";

    /** 计划包含的功能特性（逗号分隔或 JSON 片段，由商户自定义）。 */
    @Column(name = "features", length = 1024)
    private String features;

    /** 是否启用。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

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

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BillingPeriod getBillingPeriod() { return billingPeriod; }
    public void setBillingPeriod(BillingPeriod billingPeriod) { this.billingPeriod = billingPeriod; }

    public int getTrialPeriodDays() { return trialPeriodDays; }
    public void setTrialPeriodDays(int trialPeriodDays) { this.trialPeriodDays = trialPeriodDays; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getFeatures() { return features; }
    public void setFeatures(String features) { this.features = features; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}