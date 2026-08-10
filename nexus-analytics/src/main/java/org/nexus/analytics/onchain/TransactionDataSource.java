package org.nexus.analytics.onchain;

import java.time.Instant;
import java.util.List;

/**
 * 链上交易数据源端口。
 *
 * <p>供交易图谱、统计与导出服务拉取链上交易。生产实现应通过
 * nexus-core RPC / 事件订阅获取；当前默认实现为可注入的内存数据源。
 */
public interface TransactionDataSource {

    /**
     * 拉取全部交易记录。
     *
     * @return 交易记录列表
     */
    List<OnChainTransaction> fetchAll();

    /**
     * 拉取指定时间区间的交易记录。
     *
     * @param start 起始时间（含）
     * @param end   结束时间（不含）
     * @return 区间内交易记录列表
     */
    List<OnChainTransaction> fetchBetween(Instant start, Instant end);

    /**
     * 拉取与指定地址相关（作为付款方或收款方）的交易记录。
     *
     * @param address 地址
     * @return 相关交易记录列表
     */
    List<OnChainTransaction> fetchByAddress(String address);
}
