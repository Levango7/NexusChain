package org.nexus.analytics.bi;

import lombok.extern.slf4j.Slf4j;
import org.nexus.analytics.onchain.OnChainTransaction;
import org.nexus.analytics.onchain.TransactionDataSource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link TransactionStatisticsService} 默认实现。
 *
 * <p>基于 {@link TransactionDataSource} 对链上交易做多维聚合：
 * 日交易量（笔数 + 金额）、商户 TopN 排行、失败率、平均确认时延与综合报告。
 * 当前为进程内即时聚合，适用于中小规模数据集；大规模场景应替换为
 * 数据仓库（ClickHouse / Druid / 物化视图）。
 */
@Slf4j
@Service
public class DefaultStatisticsService implements TransactionStatisticsService {

    private final TransactionDataSource dataSource;
    private final ReportRegistry reportRegistry;

    public DefaultStatisticsService(TransactionDataSource dataSource, ReportRegistry reportRegistry) {
        this.dataSource = dataSource;
        this.reportRegistry = reportRegistry;
    }

    @Override
    public Map<String, Object> dailyVolume(LocalDate date) {
        if (date == null) {
            return Map.of("count", 0L, "volume", BigDecimal.ZERO);
        }
        Instant start = date.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        List<OnChainTransaction> txs = dataSource.fetchBetween(start, end);

        long count = txs.size();
        BigInteger volume = txs.stream()
                .map(OnChainTransaction::getAmount)
                .filter(a -> a != null)
                .reduce(BigInteger.ZERO, BigInteger::add);

        Map<String, Object> result = new HashMap<>();
        result.put("date", date.toString());
        result.put("count", count);
        result.put("volume", new BigDecimal(volume));
        return result;
    }

    @Override
    public List<Map.Entry<String, BigDecimal>> topMerchants(int topN) {
        if (topN <= 0) {
            return List.of();
        }
        Map<String, BigInteger> volumeByMerchant = new HashMap<>();
        for (OnChainTransaction tx : dataSource.fetchAll()) {
            if (tx.getMerchantId() == null || tx.getMerchantId().isBlank() || tx.getAmount() == null) {
                continue;
            }
            volumeByMerchant.merge(tx.getMerchantId(), tx.getAmount(), BigInteger::add);
        }
        List<Map.Entry<String, BigDecimal>> ranked = new ArrayList<>();
        volumeByMerchant.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(topN)
                .forEach(e -> ranked.add(Map.entry(e.getKey(), new BigDecimal(e.getValue()))));
        return ranked;
    }

    @Override
    public double failureRate() {
        List<OnChainTransaction> txs = dataSource.fetchAll();
        if (txs.isEmpty()) {
            return 0.0d;
        }
        long failed = txs.stream()
                .filter(tx -> tx.getStatus() == OnChainTransaction.Status.FAILED)
                .count();
        return BigDecimal.valueOf(failed)
                .divide(BigDecimal.valueOf(txs.size()), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    @Override
    public long avgLatency() {
        List<OnChainTransaction> txs = dataSource.fetchAll();
        long total = 0;
        long count = 0;
        for (OnChainTransaction tx : txs) {
            if (tx.getConfirmationLatencyMs() != null) {
                total += tx.getConfirmationLatencyMs();
                count++;
            }
        }
        return count == 0 ? 0L : total / count;
    }

    @Override
    public StatisticsReport generateReport(String reportType) {
        LocalDate today = LocalDate.now();
        Instant rangeStart = today.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant rangeEnd = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

        List<Map<String, Object>> dataPoints = new ArrayList<>();
        dataPoints.add(dailyVolume(today));
        dataPoints.add(Map.of("failureRate", failureRate()));
        dataPoints.add(Map.of("avgLatencyMs", avgLatency()));
        dataPoints.add(Map.of("topMerchants", topMerchants(5)));

        StatisticsReport report = StatisticsReport.builder()
                .reportId("RPT-" + UUID.randomUUID().toString().replace("-", ""))
                .reportType(reportType)
                .rangeStart(rangeStart)
                .rangeEnd(rangeEnd)
                .dataPoints(dataPoints)
                .generatedAt(Instant.now())
                .summary(String.format("%s report: txVolume=%s, failureRate=%.4f, avgLatency=%dms",
                        reportType, dailyVolume(today).get("volume"), failureRate(), avgLatency()))
                .build();
        reportRegistry.save(report);
        log.info("Statistics report generated: reportId={}, type={}", report.getReportId(), reportType);
        return report;
    }
}
