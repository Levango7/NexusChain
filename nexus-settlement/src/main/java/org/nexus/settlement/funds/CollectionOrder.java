package org.nexus.settlement.funds;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 归集订单实体。
 * <p>
 * 描述一笔资金归集的源地址、目标地址、金额与状态。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CollectionOrder {

    /** 归集订单 ID */
    @JsonProperty("orderId")
    private String orderId;

    /** 源地址 */
    @JsonProperty("sourceAddress")
    private String sourceAddress;

    /** 目标地址 */
    @JsonProperty("targetAddress")
    private String targetAddress;

    /** 金额 */
    @JsonProperty("amount")
    private BigDecimal amount;

    /** 币种 */
    @JsonProperty("currency")
    private String currency;

    /** 状态 */
    @JsonProperty("status")
    private OrderStatus status;

    /** 创建时间 */
    @JsonProperty("createdAt")
    private Instant createdAt;

    /** 归集订单状态枚举 */
    public enum OrderStatus {
        PENDING,
        SWEEPING,
        SETTLED,
        FAILED
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getSourceAddress() { return sourceAddress; }
    public void setSourceAddress(String sourceAddress) { this.sourceAddress = sourceAddress; }

    public String getTargetAddress() { return targetAddress; }
    public void setTargetAddress(String targetAddress) { this.targetAddress = targetAddress; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}