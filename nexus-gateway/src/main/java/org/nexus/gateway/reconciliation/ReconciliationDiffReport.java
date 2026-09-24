package org.nexus.gateway.reconciliation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账差异报告。
 *
 * <p>由 {@link ReconciliationEngine} 在比对渠道对账文件与内部交易记录后生成，
 * 包含匹配数、不匹配数、缺失数（内部有渠道无）、多余数（渠道有内部无）以及
 * 所有差异明细列表。</p>
 */
public class ReconciliationDiffReport {

    /** 匹配的交易数（双方都有且金额/状态一致） */
    private long matchedCount;

    /** 不匹配的交易数（双方都有但金额/状态/信息不一致） */
    private long mismatchedCount;

    /** 缺失的交易数（内部有记录但渠道对账文件中无对应记录 → 短款） */
    private long missingCount;

    /** 多余的交易数（渠道对账文件中有记录但内部无对应记录 → 长款） */
    private long extraCount;

    /** 差异明细列表 */
    private List<ReconciliationDiscrepancy> discrepancies;

    /** 对账执行时间 */
    private LocalDateTime reconciledAt;

    /** 内部侧参与比对的总记录数 */
    private long totalInternal;

    /** 渠道侧参与比对的总记录数 */
    private long totalChannel;

    /** 差异涉及金额汇总 */
    private BigDecimal totalDiscrepancyAmount;

    public ReconciliationDiffReport() {}

    public long getMatchedCount() { return matchedCount; }
    public void setMatchedCount(long matchedCount) { this.matchedCount = matchedCount; }

    public long getMismatchedCount() { return mismatchedCount; }
    public void setMismatchedCount(long mismatchedCount) { this.mismatchedCount = mismatchedCount; }

    public long getMissingCount() { return missingCount; }
    public void setMissingCount(long missingCount) { this.missingCount = missingCount; }

    public long getExtraCount() { return extraCount; }
    public void setExtraCount(long extraCount) { this.extraCount = extraCount; }

    public List<ReconciliationDiscrepancy> getDiscrepancies() { return discrepancies; }
    public void setDiscrepancies(List<ReconciliationDiscrepancy> discrepancies) {
        this.discrepancies = discrepancies;
    }

    public LocalDateTime getReconciledAt() { return reconciledAt; }
    public void setReconciledAt(LocalDateTime reconciledAt) { this.reconciledAt = reconciledAt; }

    public long getTotalInternal() { return totalInternal; }
    public void setTotalInternal(long totalInternal) { this.totalInternal = totalInternal; }

    public long getTotalChannel() { return totalChannel; }
    public void setTotalChannel(long totalChannel) { this.totalChannel = totalChannel; }

    public BigDecimal getTotalDiscrepancyAmount() { return totalDiscrepancyAmount; }
    public void setTotalDiscrepancyAmount(BigDecimal totalDiscrepancyAmount) {
        this.totalDiscrepancyAmount = totalDiscrepancyAmount;
    }

    @Override
    public String toString() {
        return "ReconciliationDiffReport{matchedCount=" + matchedCount
                + ", mismatchedCount=" + mismatchedCount
                + ", missingCount=" + missingCount
                + ", extraCount=" + extraCount
                + ", totalInternal=" + totalInternal
                + ", totalChannel=" + totalChannel
                + ", discrepancyCount=" + (discrepancies != null ? discrepancies.size() : 0)
                + '}';
    }
}