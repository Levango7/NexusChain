package org.nexus.analytics.export;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * {@link DataExportService} 默认骨架实现。
 *
 * <p>当前为占位实现，导出任务立即以异常完成。后续接入对象存储
 * （OSS / S3）+ 流式写入器后填充业务逻辑。
 */
@Slf4j
@Service
public class DefaultDataExportService implements DataExportService {

    @Override
    public CompletableFuture<String> exportChainData(Instant start, Instant end, ExportFormat format) {
        // TODO: 拉取 [start, end] 区间链上数据并按 format 流式写入对象存储
        log.debug("exportChainData skeleton invoked: start={}, end={}, format={}", start, end, format);
        return CompletableFuture.failedFuture(new UnsupportedOperationException("export skeleton"));
    }

    @Override
    public CompletableFuture<String> exportReport(String reportId) {
        // TODO: 加载报告并序列化导出
        log.debug("exportReport skeleton invoked: reportId={}", reportId);
        return CompletableFuture.failedFuture(new UnsupportedOperationException("export skeleton"));
    }

    @Override
    public boolean cancel(String taskId) {
        // TODO: 取消进行中的导出任务
        log.debug("cancel skeleton invoked: taskId={}", taskId);
        return false;
    }
}