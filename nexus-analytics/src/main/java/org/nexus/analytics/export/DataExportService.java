package org.nexus.analytics.export;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * 数据导出服务。
 *
 * <p>支持将链上原始数据或预生成报告以多种格式异步导出，
 * 返回导出任务句柄供调用方轮询或回调。
 */
public interface DataExportService {

    /**
     * 导出指定时间区间的链上数据。
     *
     * @param start  起始时间
     * @param end    结束时间
     * @param format 目标格式
     * @return 异步任务，完成后产出导出文件 URI
     */
    CompletableFuture<String> exportChainData(Instant start, Instant end, ExportFormat format);

    /**
     * 导出已生成的统计报告。
     *
     * @param reportId 报告 ID
     * @return 异步任务，完成后产出导出文件 URI
     */
    CompletableFuture<String> exportReport(String reportId);

    /**
     * 取消进行中的导出任务。
     *
     * @param taskId 任务 ID
     * @return 是否成功取消
     */
    boolean cancel(String taskId);
}