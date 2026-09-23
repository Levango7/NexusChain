package org.nexus.gateway.export;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * 数据导出异步任务执行器。
 *
 * <p>定时扫描 PENDING 状态的导出请求，使用信号量限制并发导出数量，
 * 避免资源耗尽。默认最大并发数为 3。</p>
 */
@Component
public class DataExportTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(DataExportTaskRunner.class);

    private final DataExportService dataExportService;
    private final DataExportRequestRepository exportRequestRepository;
    private final Semaphore exportSemaphore;

    @Autowired
    public DataExportTaskRunner(DataExportService dataExportService,
                                 DataExportRequestRepository exportRequestRepository,
                                 @Value("${nexus.data-export.max-concurrent-exports:3}") int maxConcurrentExports) {
        this.dataExportService = dataExportService;
        this.exportRequestRepository = exportRequestRepository;
        this.exportSemaphore = new Semaphore(maxConcurrentExports);
    }

    /**
     * 定时扫描 PENDING 状态的导出请求并异步执行。
     *
     * <p>每 30 秒扫描一次，使用信号量限制并发导出数量。
     * 当并发数达到上限时，跳过本次扫描，等待下一轮。</p>
     */
    @Scheduled(fixedDelay = 30000)
    public void scanPendingExports() {
        List<DataExportRequest> pendingRequests = exportRequestRepository
                .findByStatus(DataExportRequest.Status.PENDING);

        if (pendingRequests.isEmpty()) {
            return;
        }

        log.info("扫描到 {} 个待处理的导出请求", pendingRequests.size());

        for (DataExportRequest request : pendingRequests) {
            if (exportSemaphore.tryAcquire()) {
                processExportAsync(request.getId());
            } else {
                log.info("并发导出数已达上限，跳过请求: id={}", request.getId());
                break;
            }
        }
    }

    /**
     * 异步处理单个导出请求，处理完成后释放信号量。
     *
     * @param requestId 导出请求 ID
     */
    @Async
    public void processExportAsync(Long requestId) {
        try {
            dataExportService.processExport(requestId);
        } catch (Exception e) {
            log.error("异步处理导出请求失败: id={}, error={}", requestId, e.getMessage(), e);
        } finally {
            exportSemaphore.release();
        }
    }
}