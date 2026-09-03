package org.nexus.settlement.reconciliation;

/**
 * 链上记录回填端口（可选能力接口）。
 *
 * <p>结算引擎在链上结算转账成功后，可将 txHash 作为链上记录回填给数据源，
 * 使下一轮 {@code reconcileWithChain()} 能用真实链上凭证比对，
 * 消除「结算成功但对账报告全量虚假差错」的断链问题。</p>
 *
 * <p>设计为可选接口（而非并入 {@link ChainRecordSource}）的原因：
 * 生产环境的真实实现通过 RPC/事件订阅从链上拉取记录，天然不需要回填；
 * 强制其实现回填方法会污染端口契约。内存/测试实现通过实现本接口
 * 获得回填能力，结算引擎用 {@code instanceof} 探测。</p>
 */
public interface ChainRecordFeedable {

    /**
     * 回填一笔已上链的结算记录。
     *
     * @param record 结算成功后的链上记录（reference=清算订单 ID，amount=结算金额）
     */
    void feedSettlementRecord(SettlementRecord record);
}
