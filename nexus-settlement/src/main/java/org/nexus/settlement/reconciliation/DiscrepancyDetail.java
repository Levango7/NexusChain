package org.nexus.settlement.reconciliation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * 对账差错明细（结构化）。
 *
 * <p>与 {@link ReconciliationReport#getDiscrepancies()} 的 String 列表双轨并存：
 * String 形态保留给旧消费方与日志，本结构化形态供报表/监控按维度聚合
 * （按差错类型统计、按金额汇总、按 reference 追溯）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DiscrepancyDetail {

    /** 差错类型 */
    public enum Type {
        /** 本地有、外部无（疑似未上链 / 渠道未清算） */
        LOCAL_ONLY,
        /** 外部有、本地无（疑似漏记账） */
        EXTERNAL_ONLY,
        /** 双方存在但金额不一致 */
        AMOUNT_MISMATCH
    }

    /** 差错类型 */
    @JsonProperty("type")
    private Type type;

    /** 对账键（清算订单 ID / 链上交易哈希 / 银行流水号） */
    @JsonProperty("reference")
    private String reference;

    /** 本地金额（外部仅有记录时为 null） */
    @JsonProperty("localAmount")
    private BigDecimal localAmount;

    /** 外部金额（本地仅有记录时为 null） */
    @JsonProperty("externalAmount")
    private BigDecimal externalAmount;

    public DiscrepancyDetail() {}

    public DiscrepancyDetail(Type type, String reference, BigDecimal localAmount, BigDecimal externalAmount) {
        this.type = type;
        this.reference = reference;
        this.localAmount = localAmount;
        this.externalAmount = externalAmount;
    }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public BigDecimal getLocalAmount() { return localAmount; }
    public void setLocalAmount(BigDecimal localAmount) { this.localAmount = localAmount; }

    public BigDecimal getExternalAmount() { return externalAmount; }
    public void setExternalAmount(BigDecimal externalAmount) { this.externalAmount = externalAmount; }

    @Override
    public String toString() {
        return "DiscrepancyDetail{type=" + type
                + ", reference='" + reference + '\''
                + ", localAmount=" + localAmount
                + ", externalAmount=" + externalAmount + '}';
    }
}
