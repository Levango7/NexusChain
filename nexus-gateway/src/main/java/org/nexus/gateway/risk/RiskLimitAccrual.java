package org.nexus.gateway.risk;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 商户限额滚动计数 JPA 实体（A3，V92）。
 *
 * <p>每 (merchant, period_type, period_key) 一行，维护该窗口内
 * 「PAID+PAYING 状态订单金额」的精确累计——由
 * {@link OrderStateMachine} 咽喉钩子在状态进出成员集时原子增减，
 * 限额评估读单行 O(1)（替代原 O(订单量) 的 SUM 聚合）。</p>
 *
 * <p>窗口语义：自然日 / 自然月（行业惯例口径；与原滚动窗口的差异
 * 在 CHANGELOG 明示）。成员集与原 SUM 严格一致：仅 PAID + PAYING。</p>
 */
@Entity
@Table(name = "risk_limit_accruals",
        uniqueConstraints = @UniqueConstraint(name = "uk_rla_merchant_period",
                columnNames = {"merchant_id", "period_type", "period_key"}))
public class RiskLimitAccrual {

    /** 窗口类型。 */
    public enum PeriodType { DAILY, MONTHLY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", length = 8, nullable = false)
    private PeriodType periodType;

    @Column(name = "period_key", nullable = false)
    private LocalDate periodKey;

    /** 窗口内累计（进入 +amount / 离开 -amount，可为负——与 SUM 口径等价的可逆累计）。 */
    @Column(name = "accrued_amount", precision = 36, scale = 2, nullable = false)
    private BigDecimal accruedAmount = BigDecimal.ZERO;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // === Getters & Setters ===

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public PeriodType getPeriodType() { return periodType; }
    public void setPeriodType(PeriodType periodType) { this.periodType = periodType; }

    public LocalDate getPeriodKey() { return periodKey; }
    public void setPeriodKey(LocalDate periodKey) { this.periodKey = periodKey; }

    public BigDecimal getAccruedAmount() { return accruedAmount; }
    public void setAccruedAmount(BigDecimal accruedAmount) { this.accruedAmount = accruedAmount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
