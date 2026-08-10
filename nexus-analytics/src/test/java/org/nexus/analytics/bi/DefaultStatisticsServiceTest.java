package org.nexus.analytics.bi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.analytics.onchain.InMemoryTransactionDataSource;
import org.nexus.analytics.onchain.OnChainTransaction;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultStatisticsService} 补充测试。
 *
 * <p>覆盖 null date、topN<=0、空数据源等边界。
 */
class DefaultStatisticsServiceTest {

    private InMemoryTransactionDataSource dataSource;
    private ReportRegistry reportRegistry;
    private DefaultStatisticsService service;

    @BeforeEach
    void setUp() {
        dataSource = new InMemoryTransactionDataSource();
        reportRegistry = new ReportRegistry();
        service = new DefaultStatisticsService(dataSource, reportRegistry);
    }

    private OnChainTransaction tx(String from, String to, long amount,
                                  OnChainTransaction.Status status, Long latency, String merchant, Instant ts) {
        return OnChainTransaction.builder()
                .txHash("h-" + System.nanoTime())
                .fromAddress(from).toAddress(to)
                .amount(BigInteger.valueOf(amount))
                .timestamp(ts)
                .status(status)
                .confirmationLatencyMs(latency)
                .merchantId(merchant)
                .build();
    }

    @Test
    void dailyVolume_nullDate_shouldReturnZeroCountAndVolume() {
        Map<String, Object> result = service.dailyVolume(null);

        assertEquals(0L, result.get("count"));
        assertEquals(BigDecimal.ZERO, result.get("volume"));
    }

    @Test
    void dailyVolume_noTransactions_shouldReturnZero() {
        Map<String, Object> result = service.dailyVolume(LocalDate.now());

        assertEquals(0L, result.get("count"));
        assertEquals(BigDecimal.ZERO, result.get("volume"));
    }

    @Test
    void dailyVolume_shouldFilterByDateRange() {
        Instant today = Instant.now();
        Instant yesterday = today.minusSeconds(86400);
        dataSource.feed(List.of(
                tx("A", "B", 100, OnChainTransaction.Status.SUCCESS, null, null, today),
                tx("C", "D", 200, OnChainTransaction.Status.SUCCESS, null, null, yesterday)));

        Map<String, Object> result = service.dailyVolume(LocalDate.now());

        assertEquals(1L, result.get("count"));
    }

    @Test
    void topMerchants_zeroOrNegative_shouldReturnEmpty() {
        dataSource.feed(List.of(
                tx("A", "B", 100, OnChainTransaction.Status.SUCCESS, null, "M1", Instant.now())));

        assertTrue(service.topMerchants(0).isEmpty());
        assertTrue(service.topMerchants(-1).isEmpty());
    }

    @Test
    void topMerchants_shouldSkipNullMerchantOrAmount() {
        dataSource.feed(List.of(
                tx("A", "B", 100, OnChainTransaction.Status.SUCCESS, null, null, Instant.now()),
                OnChainTransaction.builder()
                        .txHash("h-null-amt").fromAddress("A").toAddress("B")
                        .amount(null).timestamp(Instant.now())
                        .status(OnChainTransaction.Status.SUCCESS).merchantId("M2").build()));

        assertTrue(service.topMerchants(5).isEmpty());
    }

    @Test
    void topMerchants_largerThanAvailable_shouldReturnAll() {
        dataSource.feed(List.of(
                tx("A", "B", 100, OnChainTransaction.Status.SUCCESS, null, "M1", Instant.now())));

        assertEquals(1, service.topMerchants(10).size());
    }

    @Test
    void failureRate_emptyDataSource_shouldReturnZero() {
        assertEquals(0.0d, service.failureRate(), 0.0001);
    }

    @Test
    void failureRate_allFailed_shouldReturnOne() {
        dataSource.feed(List.of(
                tx("A", "B", 100, OnChainTransaction.Status.FAILED, null, null, Instant.now()),
                tx("A", "C", 100, OnChainTransaction.Status.FAILED, null, null, Instant.now())));

        assertEquals(1.0d, service.failureRate(), 0.0001);
    }

    @Test
    void avgLatency_noLatencyData_shouldReturnZero() {
        dataSource.feed(List.of(
                tx("A", "B", 100, OnChainTransaction.Status.SUCCESS, null, null, Instant.now())));

        assertEquals(0L, service.avgLatency());
    }

    @Test
    void avgLatency_emptyDataSource_shouldReturnZero() {
        assertEquals(0L, service.avgLatency());
    }

    @Test
    void generateReport_shouldSaveToRegistry() {
        StatisticsReport report = service.generateReport("WEEKLY");

        assertEquals("WEEKLY", report.getReportType());
        assertEquals(report, reportRegistry.get(report.getReportId()));
        assertTrue(report.getSummary().contains("WEEKLY"));
        assertTrue(report.getDataPoints().size() >= 4);
    }
}