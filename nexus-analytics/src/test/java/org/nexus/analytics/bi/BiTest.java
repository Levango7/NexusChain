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
 * {@link DefaultStatisticsService} 与 {@link DefaultUserSegmentation} 单元测试。
 */
class BiTest {

    private InMemoryTransactionDataSource dataSource;
    private DefaultStatisticsService statisticsService;
    private DefaultUserSegmentation segmentation;
    private ReportRegistry reportRegistry;

    @BeforeEach
    void setUp() {
        dataSource = new InMemoryTransactionDataSource();
        reportRegistry = new ReportRegistry();
        statisticsService = new DefaultStatisticsService(dataSource, reportRegistry);
        segmentation = new DefaultUserSegmentation(dataSource);
    }

    private OnChainTransaction tx(String from, String to, long amount,
                                  OnChainTransaction.Status status, Long latency, String merchant) {
        return OnChainTransaction.builder()
                .txHash("h-" + from + to + amount)
                .fromAddress(from).toAddress(to)
                .amount(BigInteger.valueOf(amount))
                .timestamp(Instant.now())
                .status(status)
                .confirmationLatencyMs(latency)
                .merchantId(merchant)
                .build();
    }

    @Test
    void dailyVolume_shouldAggregateCountAndVolume() {
        dataSource.feed(List.of(
                tx("A", "B", 100, OnChainTransaction.Status.SUCCESS, 100L, "M1"),
                tx("A", "C", 200, OnChainTransaction.Status.SUCCESS, 200L, "M1")));

        Map<String, Object> result = statisticsService.dailyVolume(LocalDate.now());

        assertEquals(2L, result.get("count"));
        assertEquals(new BigDecimal("300"), result.get("volume"));
    }

    @Test
    void topMerchants_shouldRankByVolume() {
        dataSource.feed(List.of(
                tx("A", "B", 100, OnChainTransaction.Status.SUCCESS, null, "M1"),
                tx("A", "C", 500, OnChainTransaction.Status.SUCCESS, null, "M2"),
                tx("A", "D", 300, OnChainTransaction.Status.SUCCESS, null, "M3")));

        List<Map.Entry<String, BigDecimal>> top = statisticsService.topMerchants(2);

        assertEquals(2, top.size());
        assertEquals("M2", top.get(0).getKey());
    }

    @Test
    void failureRate_shouldComputeRatio() {
        dataSource.feed(List.of(
                tx("A", "B", 100, OnChainTransaction.Status.SUCCESS, null, null),
                tx("A", "C", 100, OnChainTransaction.Status.FAILED, null, null),
                tx("A", "D", 100, OnChainTransaction.Status.SUCCESS, null, null),
                tx("A", "E", 100, OnChainTransaction.Status.SUCCESS, null, null)));

        assertEquals(0.25d, statisticsService.failureRate(), 0.001);
    }

    @Test
    void avgLatency_shouldComputeMean() {
        dataSource.feed(List.of(
                tx("A", "B", 100, OnChainTransaction.Status.SUCCESS, 100L, null),
                tx("A", "C", 100, OnChainTransaction.Status.SUCCESS, 300L, null)));

        assertEquals(200L, statisticsService.avgLatency());
    }

    @Test
    void generateReport_shouldRegisterReport() {
        dataSource.feed(List.of(tx("A", "B", 100, OnChainTransaction.Status.SUCCESS, 100L, "M1")));

        StatisticsReport report = statisticsService.generateReport("DAILY");

        assertTrue(report.getReportId().startsWith("RPT-"));
        assertEquals(report, reportRegistry.get(report.getReportId()));
        assertTrue(report.getSummary().contains("DAILY"));
    }

    @Test
    void segment_highValue_shouldBeHighValue() {
        dataSource.feed(List.of(tx("A", "B", 2_000_000, OnChainTransaction.Status.SUCCESS, null, null)));

        assertEquals(DefaultUserSegmentation.SEGMENT_HIGH_VALUE, segmentation.segment("A"));
    }

    @Test
    void segment_mostlyReceiving_shouldBeMerchant() {
        // B 作为收款方占比 100%
        dataSource.feed(List.of(
                tx("A", "B", 100, OnChainTransaction.Status.SUCCESS, null, null),
                tx("C", "B", 100, OnChainTransaction.Status.SUCCESS, null, null)));

        assertEquals(DefaultUserSegmentation.SEGMENT_MERCHANT, segmentation.segment("B"));
    }

    @Test
    void segment_fewTxs_shouldBeDormant() {
        dataSource.feed(List.of(tx("A", "B", 10, OnChainTransaction.Status.SUCCESS, null, null)));

        assertEquals(DefaultUserSegmentation.SEGMENT_DORMANT, segmentation.segment("A"));
    }

    @Test
    void listSegments_shouldReturnAll() {
        assertEquals(4, segmentation.listSegments().size());
    }
}
