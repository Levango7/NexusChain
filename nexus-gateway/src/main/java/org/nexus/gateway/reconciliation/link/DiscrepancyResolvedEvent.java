package org.nexus.gateway.reconciliation.link;

import org.nexus.gateway.reconciliation.ReconciliationDiscrepancy;

/**
 * 差错已解决事件 — 差错处理完成后发布，触发联动处理。
 *
 * <p>当 {@code DiscrepancyResolutionService} 解决差错后发布此事件，
 * {@link ReconciliationAdjustmentListener} 监听并执行联动操作
 * （如自动调整资金、核销挂账等）。</p>
 */
public class DiscrepancyResolvedEvent {

    /** 已解决的差错记录 */
    private final ReconciliationDiscrepancy discrepancy;

    /** 解决备注 */
    private final String resolutionNote;

    public DiscrepancyResolvedEvent(ReconciliationDiscrepancy discrepancy, String resolutionNote) {
        this.discrepancy = discrepancy;
        this.resolutionNote = resolutionNote;
    }

    public ReconciliationDiscrepancy getDiscrepancy() { return discrepancy; }

    public String getResolutionNote() { return resolutionNote; }
}