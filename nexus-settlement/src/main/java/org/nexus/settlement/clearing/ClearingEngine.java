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

    /**
     * 大额签名审批通过后收口一笔仍处于 {@code AWAITING} 的结算
     * （由审批执行腿的推进器调用；<b>不重跑 {@link #settle}</b>，因此不会二次落账）。
     *
     * @param orderId           清算订单 ID（同时是审批链接的幂等键后缀）
     * @param settlementTxHash  结算转账的交易哈希
     * @return true = 已把该单从 AWAITING 落为 SETTLED
     */
    boolean completeAfterSigningApproval(String orderId, String settlementTxHash);

    /**
     * 审批被拒/过期（确定未动账）：把 {@code AWAITING} 的单记为 FAILED。
     *
     * @param reason 拒绝原因（供日志与差错追溯）
     * @return true = 已改写
     */
    boolean markSigningApprovalRejected(String orderId, String reason);

    /**
     * 审批腿执行结果不可核对：把 {@code AWAITING} 的单转 {@code UNVERIFIED}
     * （禁止自动重试，等人工核对）。
     *
     * @return true = 已改写
     */
    boolean markSigningApprovalUnverified(String orderId, String reason);
}