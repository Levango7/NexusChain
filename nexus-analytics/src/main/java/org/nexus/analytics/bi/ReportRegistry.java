package org.nexus.analytics.bi;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统计报告注册表。
 *
 * <p>进程内保存已生成的 {@link StatisticsReport}，供导出服务按 reportId 取用。
 * 后续替换为持久化存储时仅需替换本实现。
 */
@Component
public class ReportRegistry {

    private final Map<String, StatisticsReport> reports = new ConcurrentHashMap<>();

    /**
     * 保存报告。
     *
     * @param report 报告（需含 reportId）
     */
    public void save(StatisticsReport report) {
        if (report != null && report.getReportId() != null) {
            reports.put(report.getReportId(), report);
        }
    }

    /**
     * 按 ID 查询报告。
     *
     * @param reportId 报告 ID
     * @return 报告，不存在时返回 null
     */
    public StatisticsReport get(String reportId) {
        return reportId == null ? null : reports.get(reportId);
    }

    /**
     * 列出全部报告。
     *
     * @return 报告列表
     */
    public List<StatisticsReport> list() {
        return List.copyOf(reports.values());
    }
}
