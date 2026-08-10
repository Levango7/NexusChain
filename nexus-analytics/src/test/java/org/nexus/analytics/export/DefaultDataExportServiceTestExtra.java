package org.nexus.analytics.export;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nexus.analytics.bi.ReportRegistry;
import org.nexus.analytics.bi.StatisticsReport;
import org.nexus.analytics.onchain.InMemoryTransactionDataSource;
import org.nexus.analytics.onchain.OnChainTransaction;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultDataExportService} 补充测试。
 *
 * <p>覆盖 null/blank 参数、cancel 边界、PARQUET 格式与空数据导出。
 */
class DefaultDataExportServiceTestExtra {

    @TempDir
    Path tempDir;

    private InMemoryTransactionDataSource dataSource;
    private ReportRegistry reportRegistry;
    private DefaultDataExportService service;

    @BeforeEach
    void setUp() {
        dataSource = new InMemoryTransactionDataSource();
        reportRegistry = new ReportRegistry();
        service = new DefaultDataExportService(dataSource, reportRegistry);
        service.setExportDir(tempDir);
    }

    @Test
    void exportChainData_nullStart_shouldFail() {
        CompletableFuture<String> future = service.exportChainData(null, Instant.now(), ExportFormat.CSV);
        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    void exportChainData_nullEnd_shouldFail() {
        CompletableFuture<String> future = service.exportChainData(Instant.now(), null, ExportFormat.CSV);
        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    void exportChainData_nullFormat_shouldFail() {
        CompletableFuture<String> future = service.exportChainData(Instant.now(), Instant.now(), null);
        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    void exportChainData_emptyData_shouldStillWriteFile() throws Exception {
        String uri = service.exportChainData(
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), ExportFormat.JSON).get();

        assertTrue(uri.endsWith(".json"));
        assertTrue(Files.exists(Path.of(new java.net.URI(uri))));
    }

    @Test
    void exportChainData_csvWithNullFields_shouldHandleGracefully() throws Exception {
        // 交易含 null amount / status / merchantId（timestamp 必须非空才能被 fetchBetween 选中）
        dataSource.feed(List.of(OnChainTransaction.builder()
                .txHash("h1").fromAddress("A").toAddress("B")
                .amount(null).timestamp(Instant.now()).status(null).merchantId(null).build()));

        String uri = service.exportChainData(
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), ExportFormat.CSV).get();

        String content = Files.readString(Path.of(new java.net.URI(uri)));
        assertTrue(content.contains("h1"));
        // null amount 应输出 0
        assertTrue(content.contains(",0,"));
    }

    @Test
    void exportChainData_parquet_shouldWriteJsonFile() throws Exception {
        // PARQUET 当前未实现专门序列化，走 JSON 分支
        dataSource.feed(List.of(OnChainTransaction.builder()
                .txHash("h1").fromAddress("A").toAddress("B")
                .amount(BigInteger.ONE).timestamp(Instant.now())
                .status(OnChainTransaction.Status.SUCCESS).build()));

        String uri = service.exportChainData(
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), ExportFormat.PARQUET).get();

        // PARQUET 走 else 分支，输出 .json 扩展名
        assertTrue(uri.endsWith(".json"));
    }

    @Test
    void exportReport_nullOrBlank_shouldFail() {
        assertThrows(ExecutionException.class, () -> service.exportReport(null).get());
        assertThrows(ExecutionException.class, () -> service.exportReport("").get());
        assertThrows(ExecutionException.class, () -> service.exportReport("   ").get());
    }

    @Test
    void exportReport_existing_shouldContainReportFields() throws Exception {
        StatisticsReport report = StatisticsReport.builder()
                .reportId("RPT-X").reportType("MONTHLY")
                .summary("monthly summary").build();
        reportRegistry.save(report);

        String uri = service.exportReport("RPT-X").get();
        String content = Files.readString(Path.of(new java.net.URI(uri)));

        assertTrue(content.contains("RPT-X"));
        assertTrue(content.contains("MONTHLY"));
        assertTrue(content.contains("monthly summary"));
    }

    @Test
    void cancel_nullOrBlank_shouldReturnFalse() {
        assertFalse(service.cancel(null));
        assertFalse(service.cancel(""));
        assertFalse(service.cancel("   "));
    }

    @Test
    void cancel_unknownTask_shouldReturnFalse() {
        assertFalse(service.cancel("NO-SUCH-TASK"));
    }
}