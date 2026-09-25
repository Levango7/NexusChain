package org.nexus.gateway.reconciliation.link;

import org.nexus.gateway.reconciliation.ReconciliationDiffReport;

/**
 * 对账差异报告事件 — 对账完成后发布，触发差异调整处理。
 *
 * <p>由对账流程在生成 {@link ReconciliationDiffReport} 后发布，
 * {@link ReconciliationAdjustmentListener} 监听此事件并异步处理差异调整。</p>
 */
public class ReconciliationDiffReportEvent {

    /** 商户 ID */
    private final Long merchantId;

    /** 对账差异报告 */
    private final ReconciliationDiffReport diffReport;

    /** 关联的对账文件记录 ID */
    private final Long reconciliationFileId;

    public ReconciliationDiffReportEvent(Long merchantId, ReconciliationDiffReport diffReport,
                                          Long reconciliationFileId) {
        this.merchantId = merchantId;
        this.diffReport = diffReport;
        this.reconciliationFileId = reconciliationFileId;
    }

    public Long getMerchantId() { return merchantId; }

    public ReconciliationDiffReport getDiffReport() { return diffReport; }

    public Long getReconciliationFileId() { return reconciliationFileId; }
}