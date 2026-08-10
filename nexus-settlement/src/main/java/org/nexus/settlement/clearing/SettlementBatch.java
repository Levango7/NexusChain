package org.nexus.settlement.clearing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 结算批次实体。
 * <p>
 * 描述一次批量化清结算的输入与结果状态。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SettlementBatch {

    /** 批次号 */
    @JsonProperty("batchNo")
    private String batchNo;

    /** 交易列表（清算订单） */
    @JsonProperty("orders")
    private List<ClearingOrder> orders;

    /** 结算金额（净额） */
    @JsonProperty("settlementAmount")
    private BigDecimal settlementAmount;

    /** 币种 */
    @JsonProperty("currency")
    private String currency;

    /** 批次状态 */
    @JsonProperty("status")
    private BatchStatus status;

    /** 创建时间 */
    @JsonProperty("createdAt")
    private Instant createdAt;

    /** 批次状态枚举 */
    public enum BatchStatus {
        PENDING,
        SETTLED,
        FAILED
    }

    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }

    public List<ClearingOrder> getOrders() { return orders; }
    public void setOrders(List<ClearingOrder> orders) { this.orders = orders; }

    public BigDecimal getSettlementAmount() { return settlementAmount; }
    public void setSettlementAmount(BigDecimal settlementAmount) { this.settlementAmount = settlementAmount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BatchStatus getStatus() { return status; }
    public void setStatus(BatchStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}