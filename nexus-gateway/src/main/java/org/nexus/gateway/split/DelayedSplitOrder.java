package org.nexus.gateway.split;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 延迟分账订单实体。
 *
 * <p>在 {@link SplitOrder} 基础上增加延迟分账相关字段，支持 T+N 延迟分账模式。
 * 延迟分账状态流转：SCHEDULED → READY → EXECUTING → COMPLETED/FAILED</p>
 *
 * <ul>
 *   <li><b>SCHEDULED</b>：已创建延迟分账计划，等待到达预定执行时间</li>
 *   <li><b>READY</b>：已到达预定执行时间，等待执行</li>
 *   <li><b>EXECUTING</b>：正在执行分账</li>
 *   <li><b>COMPLETED</b>：分账完成</li>
 *   <li><b>FAILED</b>：分账失败</li>
 * </ul>
 */
@Entity
@Table(name = "delayed_split_orders")
public class DelayedSplitOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联 PaymentOrder.orderNo */
    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    /** 关联 PaymentOrder.id */
    @Column(name = "payment_id")
    private Long paymentId;

    /** 商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 接收分账金额的钱包地址 */
    @Column(name = "receiver_address", nullable = false, length = 66)
    private String receiverAddress;

    /** 计算出的分账金额 */
    @Column(name = "amount", nullable = false, precision = 36, scale = 8)
    private BigDecimal amount;

    /** 分账类型（从 SplitRule 快照） */
    @Enumerated(EnumType.STRING)
    @Column(name = "split_type", nullable = false, length = 16)
    private SplitRule.SplitType splitType;

    /** 分账值（从 SplitRule 快照） */
    @Column(name = "split_value", nullable = false, precision = 36, scale = 8)
    private BigDecimal splitValue;

    /** 关联 SplitRule.id */
    @Column(name = "split_rule_id")
    private Long splitRuleId;

    /** 规则描述（从 SplitRule 快照） */
    @Column(name = "description", length = 256)
    private String description;

    /**
     * 延迟分账状态。
     * 流转：SCHEDULED → READY → EXECUTING → COMPLETED/FAILED
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "delay_status", nullable = false, length = 16)
    private DelayStatus delayStatus = DelayStatus.SCHEDULED;

    /**
     * 预定执行时间（T+N 的具体时间点）。
     * 到达此时间后，状态从 SCHEDULED 变为 READY。
     */
    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    /** 实际执行时间（状态变为 EXECUTING 时设置） */
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /** 延迟天数（T+N 中的 N） */
    @Column(name = "delay_days")
    private Integer delayDays;

    /** 失败原因（状态变为 FAILED 时设置） */
    @Column(name = "failure_reason", length = 512)
    private String failureReason;

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

    // --- Enumerations ---

    /**
     * 延迟分账状态枚举。
     */
    public enum DelayStatus {
        /** 已创建延迟分账计划，等待到达预定执行时间 */
        SCHEDULED,
        /** 已到达预定执行时间，等待执行 */
        READY,
        /** 正在执行分账 */
        EXECUTING,
        /** 分账完成 */
        COMPLETED,
        /** 分账失败 */
        FAILED
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getReceiverAddress() { return receiverAddress; }
    public void setReceiverAddress(String receiverAddress) { this.receiverAddress = receiverAddress; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public SplitRule.SplitType getSplitType() { return splitType; }
    public void setSplitType(SplitRule.SplitType splitType) { this.splitType = splitType; }

    public BigDecimal getSplitValue() { return splitValue; }
    public void setSplitValue(BigDecimal splitValue) { this.splitValue = splitValue; }

    public Long getSplitRuleId() { return splitRuleId; }
    public void setSplitRuleId(Long splitRuleId) { this.splitRuleId = splitRuleId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public DelayStatus getDelayStatus() { return delayStatus; }
    public void setDelayStatus(DelayStatus delayStatus) { this.delayStatus = delayStatus; }

    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; }

    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }

    public Integer getDelayDays() { return delayDays; }
    public void setDelayDays(Integer delayDays) { this.delayDays = delayDays; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}