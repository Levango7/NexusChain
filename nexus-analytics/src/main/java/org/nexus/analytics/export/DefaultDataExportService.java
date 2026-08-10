package org.nexus.analytics.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.nexus.analytics.bi.ReportRegistry;
import org.nexus.analytics.bi.StatisticsReport;
import org.nexus.analytics.onchain.OnChainTransaction;
import org.nexus.analytics.onchain.TransactionDataSource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link DataExportService} 默认实现。
 *
 * <p>导出流程：拉取链上数据 / 统计报告 → 按 {@link ExportFormat} 序列化为
 * CSV 或 JSON → 写入临时目录并返回文件 URI。任务以异步方式执行，
 * 支持通过 {@link #cancel} 取消进行中的导出。
 *
 * <p>当前写入本地临时目录；生产环境应替换为对象存储（OSS / S3）上传。
 */
@Slf4j
@Service
public class DefaultDataExportService implements DataExportService {

    private final TransactionDataSource dataSource;
    private final ReportRegistry reportRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 进行中的导出任务（taskId → future），用于取消 */
    private final Map<String, CompletableFuture<String>> inFlightTasks = new ConcurrentHashMap<>();

    /** 导出输出目录（默认为系统临时目录下的 nexus-export） */
    private Path exportDir;

    public DefaultDataExportService(TransactionDataSource dataSource, ReportRegistry reportRegistry) {
        this.dataSource = dataSource;
        this.reportRegistry = reportRegistry;
    }

    /** 设置导出目录（测试 / 自定义路径用）。 */
    public void setExportDir(Path dir) {
        this.exportDir = dir;
    }

    @Override
    public CompletableFuture<String> exportChainData(Instant start, Instant end, ExportFormat format) {
        if (start == null || end == null || format == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("start, end and format are required"));
        }
        String taskId = "EXPORT-" + UUID.randomUUID().toString().replace("-", "");
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            List<OnChainTransaction> txs = dataSource.fetchBetween(start, end);
            String content = serialize(txs, format);
            return writeToFile(taskId, content, format);
        });
        inFlightTasks.put(taskId, future);
        future.whenComplete((r, ex) -> inFlightTasks.remove(taskId));
        log.info("Chain data export started: taskId={}, range=[{}, {}), format={}",
                taskId, start, end, format);
        return future;
    }

    @Override
    public CompletableFuture<String> exportReport(String reportId) {
        if (reportId == null || reportId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("reportId is required"));
        }
        String taskId = "EXPORT-" + UUID.randomUUID().toString().replace("-", "");
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            StatisticsReport report = reportRegistry.get(reportId);
            if (report == null) {
                throw new IllegalArgumentException("Report not found: " + reportId);
            }
            try {
                String content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
                return writeToFile(taskId, content, ExportFormat.JSON);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to serialize report: " + reportId, e);
            }
        });
        inFlightTasks.put(taskId, future);
        future.whenComplete((r, ex) -> inFlightTasks.remove(taskId));
        log.info("Report export started: taskId={}, reportId={}", taskId, reportId);
        return future;
    }

    @Override
    public boolean cancel(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        CompletableFuture<String> future = inFlightTasks.get(taskId);
        if (future == null) {
            return false;
        }
        boolean cancelled = future.cancel(true);
        if (cancelled) {
            inFlightTasks.remove(taskId);
            log.info("Export task cancelled: taskId={}", taskId);
        }
        return cancelled;
    }

    /** 将交易序列化为指定格式字符串。 */
    private String serialize(List<OnChainTransaction> txs, ExportFormat format) {
        try {
            if (format == ExportFormat.CSV) {
                StringBuilder sb = new StringBuilder("txHash,fromAddress,toAddress,amount,timestamp,status,merchantId\n");
                for (OnChainTransaction tx : txs) {
                    sb.append(nullSafe(tx.getTxHash())).append(',')
                            .append(nullSafe(tx.getFromAddress())).append(',')
                            .append(nullSafe(tx.getToAddress())).append(',')
                            .append(tx.getAmount() != null ? tx.getAmount() : BigInteger.ZERO).append(',')
                            .append(tx.getTimestamp() != null ? tx.getTimestamp() : "").append(',')
                            .append(tx.getStatus() != null ? tx.getStatus() : "").append(',')
                            .append(nullSafe(tx.getMerchantId())).append('\n');
                }
                return sb.toString();
            }
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(txs);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize transactions", e);
        }
    }

    /** 写入导出文件并返回 URI。 */
    private String writeToFile(String taskId, String content, ExportFormat format) {
        try {
            Path dir = exportDir != null ? exportDir
                    : Path.of(System.getProperty("java.io.tmpdir"), "nexus-export");
            Files.createDirectories(dir);
            String extension = format == ExportFormat.CSV ? ".csv" : ".json";
            Path file = dir.resolve(taskId + extension);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            log.info("Export written: taskId={}, file={}, bytes={}", taskId, file, content.length());
            return file.toUri().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write export file for task: " + taskId, e);
        }
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}
