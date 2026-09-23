package org.nexus.gateway.split;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分账订单实体。
 *
 * <p>记录每笔支付订单的分账明细。在支付完成后由 {@link SplitService} 根据商户的
 * {@link SplitRule} 计算并持久化。SplitOrder 中的 splitType/splitValue/description
 * 是从 SplitRule 快照而来，即使后续规则被修改或停用，历史分账记录不受影响。</p>
 */
@Entity
@Table(name = "split_orders")
public class SplitOrder {

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

    /** 分账状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SplitStatus status = SplitStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 结算时间（状态变为 SETTLED 时设置） */
    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    /** 乐观锁版本号 */
    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // --- Enumerations ---

    /**
     * 分账订单状态枚举。
     */
    public enum SplitStatus {
        /** 待结算 */
        PENDING,
        /** 已结算 */
        SETTLED,
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

    public SplitStatus getStatus() { return status; }
    public void setStatus(SplitStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getSettledAt() { return settledAt; }
    public void setSettledAt(LocalDateTime settledAt) { this.settledAt = settledAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}