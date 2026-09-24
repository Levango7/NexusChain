package org.nexus.gateway.limit;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 限额调整规则实体。
 *
 * <p>定义基于交易行为自动调整限额的规则。规则类型包括：</p>
 * <ul>
 *   <li><b>CONSECUTIVE_SUCCESS</b>：连续成功交易提升限额</li>
 *   <li><b>HIGH_REFUND_RATE</b>：高频退款降低限额</li>
 *   <li><b>RISK_EVENT</b>：风险事件降低限额</li>
 * </ul>
 */
@Entity
@Table(name = "limit_adjustment_rules")
public class LimitAdjustmentRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商户 ID，null 表示全局规则 */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** 规则类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 32)
    private RuleType ruleType;

    /**
     * 触发阈值：
     * <ul>
     *   <li>CONSECUTIVE_SUCCESS：连续成功交易次数</li>
     *   <li>HIGH_REFUND_RATE：退款率阈值（百分比，如 20 表示 20%）</li>
     *   <li>RISK_EVENT：风险事件次数</li>
     * </ul>
     */
    @Column(name = "trigger_threshold", nullable = false)
    private Integer triggerThreshold;

    /**
     * 调整方向：INCREASE（提升限额）或 DECREASE（降低限额）
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_direction", nullable = false, length = 16)
    private AdjustmentDirection adjustmentDirection;

    /**
     * 调整比例（百分比），如 10 表示提升/降低 10%。
     * INCREASE 时为正数，DECREASE 时为正数（表示降低的比例）。
     */
    @Column(name = "adjustment_percentage", nullable = false, precision = 10, scale = 4)
    private BigDecimal adjustmentPercentage;

    /**
     * 调整上限/下限：
     * <ul>
     *   <li>INCREASE：调整后的限额不能超过此值</li>
     *   <li>DECREASE：调整后的限额不能低于此值</li>
     * </ul>
     */
    @Column(name = "adjustment_cap", precision = 36, scale = 8)
    private BigDecimal adjustmentCap;

    /** 被调整的限额维度 */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_limit_type", nullable = false, length = 32)
    private TargetLimitType targetLimitType;

    /** 是否活跃 */
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

    public enum RuleType {
        /** 连续成功交易提升限额 */
        CONSECUTIVE_SUCCESS,
        /** 高频退款降低限额 */
        HIGH_REFUND_RATE,
        /** 风险事件降低限额 */
        RISK_EVENT
    }

    public enum AdjustmentDirection {
        /** 提升限额 */
        INCREASE,
        /** 降低限额 */
        DECREASE
    }

    public enum TargetLimitType {
        /** 单笔最大金额 */
        SINGLE_MAX,
        /** 日累计最大金额 */
        DAILY_MAX,
        /** 月累计最大金额 */
        MONTHLY_MAX,
        /** 年累计最大金额 */
        ANNUAL_MAX
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public RuleType getRuleType() { return ruleType; }
    public void setRuleType(RuleType ruleType) { this.ruleType = ruleType; }

    public Integer getTriggerThreshold() { return triggerThreshold; }
    public void setTriggerThreshold(Integer triggerThreshold) { this.triggerThreshold = triggerThreshold; }

    public AdjustmentDirection getAdjustmentDirection() { return adjustmentDirection; }
    public void setAdjustmentDirection(AdjustmentDirection adjustmentDirection) {
        this.adjustmentDirection = adjustmentDirection;
    }

    public BigDecimal getAdjustmentPercentage() { return adjustmentPercentage; }
    public void setAdjustmentPercentage(BigDecimal adjustmentPercentage) {
        this.adjustmentPercentage = adjustmentPercentage;
    }

    public BigDecimal getAdjustmentCap() { return adjustmentCap; }
    public void setAdjustmentCap(BigDecimal adjustmentCap) { this.adjustmentCap = adjustmentCap; }

    public TargetLimitType getTargetLimitType() { return targetLimitType; }
    public void setTargetLimitType(TargetLimitType targetLimitType) { this.targetLimitType = targetLimitType; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}