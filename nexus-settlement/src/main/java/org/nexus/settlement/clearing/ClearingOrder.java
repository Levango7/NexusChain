package org.nexus.settlement.clearing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 清算订单实体。
 * <p>
 * 描述单笔待结算的支付订单及其结算周期、状态等元数据。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClearingOrder {

    /** 订单 ID */
    @JsonProperty("orderId")
    private String orderId;

    /** 商户 ID */
    @JsonProperty("merchantId")
    private String merchantId;

    /** 金额 */
    @JsonProperty("amount")
    private BigDecimal amount;

    /** 币种 */
    @JsonProperty("currency")
    private String currency;

    /** 结算周期 */
    @JsonProperty("settlementCycle")
    private String settlementCycle;

    /** 订单状态 */
    @JsonProperty("status")
    private OrderStatus status;

    /** 创建时间 */
    @JsonProperty("createdAt")
    private Instant createdAt;

    /** 订单状态枚举 */
    public enum OrderStatus {
        PENDING,
        SETTLED,
        FAILED
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getSettlementCycle() { return settlementCycle; }
    public void setSettlementCycle(String settlementCycle) { this.settlementCycle = settlementCycle; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}