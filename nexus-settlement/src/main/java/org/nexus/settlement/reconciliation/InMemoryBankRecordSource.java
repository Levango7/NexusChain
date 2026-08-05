package org.nexus.settlement.reconciliation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于内存的银行渠道记录数据源默认实现。
 * <p>
 * 生产环境应替换为解析银行对账文件（CSV/ISO20022）或调用渠道 API 的实现；
 * 当前实现允许外部通过 {@link #feed} 注入银行记录，用于对账与测试。
 * </p>
 */
@Component
public class InMemoryBankRecordSource implements BankRecordSource {

    private final List<SettlementRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public List<SettlementRecord> fetchBankRecords() {
        return List.copyOf(records);
    }

    /**
     * 注入银行记录（测试或对账文件解析回放用）。
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
