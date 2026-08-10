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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultDataExportService} 单元测试。
 */
class DefaultDataExportServiceTest {

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

    private void feedTx() {
        dataSource.feed(List.of(OnChainTransaction.builder()
                .txHash("h1").fromAddress("A").toAddress("B")
                .amount(BigInteger.valueOf(100))
                .timestamp(Instant.now())
                .status(OnChainTransaction.Status.SUCCESS)
                .merchantId("M1")
                .build()));
    }

    @Test
    void exportChainData_csv_shouldWriteFile() throws Exception {
        feedTx();

        CompletableFuture<String> future = service.exportChainData(
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), ExportFormat.CSV);
        String uri = future.get();

        assertTrue(uri.endsWith(".csv"));
        Path file = Path.of(new java.net.URI(uri));
        assertTrue(Files.exists(file));
        String content = Files.readString(file);
        assertTrue(content.contains("txHash"));
        assertTrue(content.contains("h1"));
    }

    @Test
    void exportChainData_json_shouldWriteFile() throws Exception {
        feedTx();

        CompletableFuture<String> future = service.exportChainData(
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), ExportFormat.JSON);
        String uri = future.get();

        assertTrue(uri.endsWith(".json"));
        Path file = Path.of(new java.net.URI(uri));
        assertTrue(Files.readString(file).contains("h1"));
    }

    @Test
    void exportReport_existing_shouldWriteJson() throws Exception {
        StatisticsReport report = StatisticsReport.builder()
                .reportId("RPT-1").reportType("DAILY").build();
        reportRegistry.save(report);

        String uri = service.exportReport("RPT-1").get();

        assertTrue(uri.endsWith(".json"));
        assertTrue(Files.readString(Path.of(new java.net.URI(uri))).contains("RPT-1"));
    }

    @Test
    void exportReport_notFound_shouldFail() {
        CompletableFuture<String> future = service.exportReport("NO-SUCH");

        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    void exportChainData_invalidArgs_shouldFail() {
        CompletableFuture<String> future = service.exportChainData(null, null, ExportFormat.CSV);
        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    void cancel_unknownTask_shouldReturnFalse() {
        assertEquals(false, service.cancel("NO-SUCH-TASK"));
    }
}
