package org.nexus.settlement.reconciliation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 对账记录实体。
 * <p>
 * 对账的最小比对单元：一笔资金流水（本地清算记录、链上记录或银行记录
 * 统一映射为本结构），以 {@code reference} 为对账键、{@code amount} 为比对值。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SettlementRecord {

    /** 对账键（如清算订单 ID / 链上交易哈希 / 银行流水号） */
    @JsonProperty("reference")
    private String reference;

    /** 金额（正数） */
    @JsonProperty("amount")
    private BigDecimal amount;

    /** 币种 */
    @JsonProperty("currency")
    private String currency;

    /** 记录时间 */
    @JsonProperty("recordedAt")
    private Instant recordedAt;

    public SettlementRecord() {}

    public SettlementRecord(String reference, BigDecimal amount, String currency, Instant recordedAt) {
        this.reference = reference;
        this.amount = amount;
        this.currency = currency;
        this.recordedAt = recordedAt;
    }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
}
