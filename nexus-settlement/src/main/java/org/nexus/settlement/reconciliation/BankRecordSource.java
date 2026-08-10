package org.nexus.settlement.reconciliation;

import java.util.List;

/**
 * 银行渠道记录数据源端口。
 * <p>
 * 供对账服务拉取银行渠道结算流水。生产实现应解析银行对账文件
 * （CSV/ISO20022）或调用渠道 API；当前默认实现返回本地账本视图。
 * </p>
 */
public interface BankRecordSource {

    /**
     * 拉取当前全部银行渠道结算记录。
     *
     * @return 银行渠道结算记录列表
     */
    List<SettlementRecord> fetchBankRecords();
}
