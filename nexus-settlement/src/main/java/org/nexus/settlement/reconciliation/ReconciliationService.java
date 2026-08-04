package org.nexus.settlement.reconciliation;

/**
 * 对账服务接口。
 * <p>
 * 负责与链上数据、银行渠道数据进行对账并产出差错报告。
 * </p>
 */
public interface ReconciliationService {

    /**
     * 与链上数据对账。
     *
     * @return 对账报告
     */
    ReconciliationReport reconcileWithChain();

    /**
     * 与银行渠道对账。
     *
     * @return 对账报告
     */
    ReconciliationReport reconcileWithBank();

    /**
     * 上报差错明细。
     *
     * @param report 对账报告
     * @return 含差错处理结果的对账报告
     */
    ReconciliationReport reportDiscrepancy(ReconciliationReport report);
}