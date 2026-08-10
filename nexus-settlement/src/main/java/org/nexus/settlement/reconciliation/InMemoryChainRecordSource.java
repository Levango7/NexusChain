package org.nexus.settlement.reconciliation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于内存的链上记录数据源默认实现。
 * <p>
 * 生产环境应替换为经 nexus-core RPC / 事件订阅拉取已确认结算交易的实现；
 * 当前实现允许外部通过 {@link #feed} 注入链上记录，用于对账与测试。
 * </p>
 */
@Component
public class InMemoryChainRecordSource implements ChainRecordSource {

    private final List<SettlementRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public List<SettlementRecord> fetchChainRecords() {
        return List.copyOf(records);
    }

    /**
     * 注入链上记录（测试或事件回放用）。
     *
     * @param newRecords 待注入记录
     */
    public void feed(List<SettlementRecord> newRecords) {
        if (newRecords != null) {
            records.addAll(newRecords);
        }
    }

    /** 清空记录。 */
    public void clear() {
        records.clear();
    }
}
