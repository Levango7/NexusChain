package org.nexus.gateway.limit;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 限额调整记录实体。
 *
 * <p>记录每次限额调整的历史，包括调整前后的值、调整原因、触发的规则等。</p>
 */
@Entity
@Table(name = "limit_adjustment_records")
public class LimitAdjustmentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 触发的调整规则 ID */
    @Column(name = "rule_id")
    private Long ruleId;

    /** 调整规则类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 32)
    private LimitAdjustmentRule.RuleType ruleType;

    /** 被调整的限额维度 */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_limit_type", nullable = false, length = 32)
    private LimitAdjustmentRule.TargetLimitType targetLimitType;

    /** 调整前的限额值 */
    @Column(name = "old_value", precision = 36, scale = 8)
    private BigDecimal oldValue;

    /** 调整后的限额值 */
    @Column(name = "new_value", precision = 36, scale = 8)
    private BigDecimal newValue;

    /** 调整比例（百分比） */
    @Column(name = "adjustment_percentage", precision = 10, scale = 4)
    private BigDecimal adjustmentPercentage;

    /** 调整方向 */
    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_direction", nullable = false, length = 16)
    private LimitAdjustmentRule.AdjustmentDirection adjustmentDirection;

    /** 调整原因描述 */
    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }

    public LimitAdjustmentRule.RuleType getRuleType() { return ruleType; }
    public void setRuleType(LimitAdjustmentRule.RuleType ruleType) { this.ruleType = ruleType; }

    public LimitAdjustmentRule.TargetLimitType getTargetLimitType() { return targetLimitType; }
    public void setTargetLimitType(LimitAdjustmentRule.TargetLimitType targetLimitType) {
        this.targetLimitType = targetLimitType;
    }

    public BigDecimal getOldValue() { return oldValue; }
    public void setOldValue(BigDecimal oldValue) { this.oldValue = oldValue; }

    public BigDecimal getNewValue() { return newValue; }
    public void setNewValue(BigDecimal newValue) { this.newValue = newValue; }

    public BigDecimal getAdjustmentPercentage() { return adjustmentPercentage; }
    public void setAdjustmentPercentage(BigDecimal adjustmentPercentage) {
        this.adjustmentPercentage = adjustmentPercentage;
    }

    public LimitAdjustmentRule.AdjustmentDirection getAdjustmentDirection() { return adjustmentDirection; }
    public void setAdjustmentDirection(LimitAdjustmentRule.AdjustmentDirection adjustmentDirection) {
        this.adjustmentDirection = adjustmentDirection;
    }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}