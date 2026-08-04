package org.nexus.analytics.bi;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link TransactionStatisticsService} 默认骨架实现。
 *
 * <p>当前为占位实现，所有统计返回空结果。后续接入数据仓库
 * （ClickHouse / Druid / 物化视图）后填充聚合查询逻辑。
 */
@Slf4j
@Service
public class DefaultStatisticsService implements TransactionStatisticsService {

    @Override
    public Map<String, Object> dailyVolume(LocalDate date) {
        // TODO: 聚合当日交易笔数与金额
        log.debug("dailyVolume skeleton invoked: date={}", date);
        return Collections.emptyMap();
    }

    @Override
    public List<Map.Entry<String, BigDecimal>> topMerchants(int topN) {
        // TODO: 按金额降序取 Top N 商户
        log.debug("topMerchants skeleton invoked: topN={}", topN);
        return Collections.emptyList();
    }

    @Override
    public double failureRate() {
        // TODO: 计算失败交易占比
        log.debug("failureRate skeleton invoked");
        return 0.0d;
    }

    @Override
    public long avgLatency() {
        // TODO: 计算平均确认时延
        log.debug("avgLatency skeleton invoked");
        return 0L;
    }

    @Override
    public StatisticsReport generateReport(String reportType) {
        // TODO: 拉取数据点并组装报告
        log.debug("generateReport skeleton invoked: reportType={}", reportType);
        return StatisticsReport.builder()
                .reportType(reportType)
                .dataPoints(Collections.emptyList())
                .build();
    }
}