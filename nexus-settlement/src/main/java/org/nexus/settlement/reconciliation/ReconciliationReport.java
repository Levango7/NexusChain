package org.nexus.settlement.reconciliation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

/**
 * 对账报告实体。
 * <p>
 * 描述一次对账的统计结果与差错明细。
 * </p>
 *
 * <p>结构化维度（Path C）：{@code source}（对账数据源）、双边总量与差错金额汇总、
 * {@code details}（结构化差错明细）——供报表/监控按维度聚合；
 * 既有字段（reconcileDate/matchedCount/discrepancies）保持不变，旧消费方零破坏。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReconciliationReport {

    /** 对账日期 */
    @JsonProperty("reconcileDate")
    private LocalDate reconcileDate;

    /** 匹配数 */
    @JsonProperty("matchedCount")
    private long matchedCount;

    /** 差错数 */
    @JsonProperty("discrepancyCount")
    private long discrepancyCount;

    /** 差错明细 */
    @JsonProperty("discrepancies")
    private List<String> discrepancies;

    /** 对账数据源（CHAIN / BANK） */
    @JsonProperty("source")
    private String source;

    /** 本地侧参与比对的记录数 */
    @JsonProperty("totalLocal")
    private long totalLocal;

    /** 外部侧参与比对的记录数 */
    @JsonProperty("totalExternal")
    private long totalExternal;

    /** 差错涉及金额汇总（本地+外部单边金额绝对值与双边差额绝对值之和） */
    @JsonProperty("totalDiscrepancyAmount")
    private BigDecimal totalDiscrepancyAmount;

    /** 结构化差错明细（与 discrepancies String 列表双轨并存） */
    @JsonProperty("details")
    private List<DiscrepancyDetail> details;

    /** 对账执行时间戳 */
    @JsonProperty("reconciledAt")
    private Instant reconciledAt;

    public LocalDate getReconcileDate() { return reconcileDate; }
    public void setReconcileDate(LocalDate reconcileDate) { this.reconcileDate = reconcileDate; }

    public long getMatchedCount() { return matchedCount; }
    public void setMatchedCount(long matchedCount) { this.matchedCount = matchedCount; }

    public long getDiscrepancyCount() { return discrepancyCount; }
    public void setDiscrepancyCount(long discrepancyCount) { this.discrepancyCount = discrepancyCount; }

    public List<String> getDiscrepancies() { return discrepancies; }
    public void setDiscrepancies(List<String> discrepancies) { this.discrepancies = discrepancies; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public long getTotalLocal() { return totalLocal; }
    public void setTotalLocal(long totalLocal) { this.totalLocal = totalLocal; }

    public long getTotalExternal() { return totalExternal; }
    public void setTotalExternal(long totalExternal) { this.totalExternal = totalExternal; }

    public BigDecimal getTotalDiscrepancyAmount() { return totalDiscrepancyAmount; }
    public void setTotalDiscrepancyAmount(BigDecimal totalDiscrepancyAmount) {
        this.totalDiscrepancyAmount = totalDiscrepancyAmount;
    }

    public List<DiscrepancyDetail> getDetails() { return details; }
    public void setDetails(List<DiscrepancyDetail> details) { this.details = details; }

    public Instant getReconciledAt() { return reconciledAt; }
    public void setReconciledAt(Instant reconciledAt) { this.reconciledAt = reconciledAt; }
}