package org.nexus.settlement.reconciliation;

import java.util.List;

/**
 * 链上记录数据源端口。
 * <p>
 * 供对账服务拉取链上结算流水。生产实现应通过 nexus-core RPC 或
 * 事件订阅获取已确认的结算交易；当前默认实现返回本地账本视图。
 * </p>
 */
public interface ChainRecordSource {

    /**
     * 拉取当前全部链上结算记录。
     *
     * @return 链上结算记录列表
     */
    List<SettlementRecord> fetchChainRecords();
}
