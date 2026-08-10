package org.nexus.settlement.clearing;

/**
 * 清结算引擎接口。
 * <p>
 * 负责批量化清结算、单笔结算以及对账触发等核心动作。
 * </p>
 */
public interface ClearingEngine {

    /**
     * 批量清结算：将一个结算批次中的所有交易进行净额结算并落账。
     *
     * @param batch 结算批次
     * @return 处理后的批次（含最终状态与差错信息）
     */
    SettlementBatch batchClear(SettlementBatch batch);

    /**
     * 单笔结算：对一笔清算订单执行结算动作。
     *
     * @param order 清算订单
     * @return 结算后的订单
     */
    ClearingOrder settle(ClearingOrder order);

    /**
     * 触发对账：基于清算结果生成对账报告。
     *
     * @param report 对账报告输入
     * @return 更新后的对账报告
     */
    org.nexus.settlement.reconciliation.ReconciliationReport reconcile(
            org.nexus.settlement.reconciliation.ReconciliationReport report);
}