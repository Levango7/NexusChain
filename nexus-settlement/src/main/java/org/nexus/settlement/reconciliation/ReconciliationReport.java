package org.nexus.settlement.reconciliation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

/**
 * 对账报告实体。
 * <p>
 * 描述一次对账的统计结果与差错明细。
 * </p>
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

    public LocalDate getReconcileDate() { return reconcileDate; }
    public void setReconcileDate(LocalDate reconcileDate) { this.reconcileDate = reconcileDate; }

    public long getMatchedCount() { return matchedCount; }
    public void setMatchedCount(long matchedCount) { this.matchedCount = matchedCount; }

    public long getDiscrepancyCount() { return discrepancyCount; }
    public void setDiscrepancyCount(long discrepancyCount) { this.discrepancyCount = discrepancyCount; }

    public List<String> getDiscrepancies() { return discrepancies; }
    public void setDiscrepancies(List<String> discrepancies) { this.discrepancies = discrepancies; }
}