package org.nexus.analytics.bi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 交易统计服务。
 *
 * <p>提供面向运营 / 商户的多维度统计能力，结果以聚合数值或时序点形式返回。
 */
public interface TransactionStatisticsService {

    /**
     * 日交易量（笔数 + 金额）。
     *
     * @param date 日期
     * @return 含 count / volume 等键的聚合结果
     */
    Map<String, Object> dailyVolume(LocalDate date);

    /**
     * Top N 商户排行（按交易金额）。
     *
     * @param topN 排行榜长度
     * @return 商户 ID → 金额 的有序列表
     */
    List<Map.Entry<String, BigDecimal>> topMerchants(int topN);

    /**
     * 交易失败率（0.0 ~ 1.0）。
     *
     * @return 失败率
     */
    double failureRate();

    /**
     * 平均交易确认时延（毫秒）。
     *
     * @return 平均时延
     */
    long avgLatency();

    /**
     * 生成综合统计报告。
     *
     * @param reportType 报告类型（如 DAILY / WEEKLY / MONTHLY）
     * @return 报告对象
     */
    StatisticsReport generateReport(String reportType);
}