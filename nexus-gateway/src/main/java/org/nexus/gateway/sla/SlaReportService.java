package org.nexus.gateway.sla;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SLA 报告服务。
 *
 * <p>生成日报/周报/月报，按时间段聚合 SlaMeasurement 数据，
 * 计算达标率（达标次数 / 总测量次数），生成报告摘要。</p>
 */
@Service
public class SlaReportService {

    private static final Logger log = LoggerFactory.getLogger(SlaReportService.class);

    private final SlaTargetRepository targetRepository;
    private final SlaMeasurementRepository measurementRepository;

    public SlaReportService(SlaTargetRepository targetRepository,
                            SlaMeasurementRepository measurementRepository) {
        this.targetRepository = targetRepository;
        this.measurementRepository = measurementRepository;
    }

    /**
     * 生成 SLA 报告。
     *
     * @param period 报告周期：daily / weekly / monthly
     * @param from   起始时间
     * @param to     结束时间
     * @return 报告数据
     */
    public SlaReport generateReport(String period, LocalDateTime from, LocalDateTime to) {
        log.info("生成 SLA 报告: period={}, from={}, to={}", period, from, to);

        List<SlaTarget> targets = targetRepository.findAll();
        List<SlaReportItem> items = new ArrayList<>();

        for (SlaTarget target : targets) {
            SlaReportItem item = generateReportItem(target, from, to);
            items.add(item);
        }

        // 计算整体达标率
        long totalMet = items.stream().mapToLong(SlaReportItem::getMetCount).sum();
        long totalMeasurements = items.stream().mapToLong(SlaReportItem::getTotalCount).sum();
        double overallComplianceRate = totalMeasurements > 0
                ? (double) totalMet / totalMeasurements * 100.0
                : 0.0;

        SlaReport report = new SlaReport();
        report.setPeriod(period);
        report.setFrom(from);
        report.setTo(to);
        report.setItems(items);
        report.setOverallComplianceRate(overallComplianceRate);
        report.setSummary(generateSummary(period, items, overallComplianceRate));

        return report;
    }

    /**
     * 生成单个 SLA 目标的报告项。
     */
    private SlaReportItem generateReportItem(SlaTarget target, LocalDateTime from, LocalDateTime to) {
        long metCount = measurementRepository.countMetInWindow(target.getId(), from, to);
        long totalCount = measurementRepository.countTotalInWindow(target.getId(), from, to);
        double complianceRate = totalCount > 0
                ? (double) metCount / totalCount * 100.0
                : 0.0;

        // 获取最近一次测量值作为当前值
        List<SlaMeasurement> recentMeasurements = measurementRepository
                .findByTargetIdOrderByMeasuredAtDesc(target.getId());
        double latestValue = recentMeasurements.isEmpty() ? 0.0
                : recentMeasurements.get(0).getMeasuredValue();
        boolean currentlyMet = !recentMeasurements.isEmpty() && recentMeasurements.get(0).isMet();

        SlaReportItem item = new SlaReportItem();
        item.setTargetId(target.getId());
        item.setTargetName(target.getName());
        item.setTargetType(target.getTargetType().name());
        item.setTargetValue(target.getTargetValue());
        item.setLatestValue(latestValue);
        item.setCurrentlyMet(currentlyMet);
        item.setMetCount(metCount);
        item.setTotalCount(totalCount);
        item.setComplianceRate(complianceRate);

        return item;
    }

    /**
     * 生成报告摘要文本。
     */
    private String generateSummary(String period, List<SlaReportItem> items, double overallRate) {
        long breachedTargets = items.stream().filter(i -> !i.isCurrentlyMet()).count();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s SLA 报告: 整体达标率 %.2f%%, ", period, overallRate));
        sb.append(String.format("共 %d 个 SLA 目标, ", items.size()));
        sb.append(String.format("其中 %d 个当前未达标.", breachedTargets));
        return sb.toString();
    }

    /**
     * SLA 报告数据模型。
     */
    public static class SlaReport {
        private String period;
        private LocalDateTime from;
        private LocalDateTime to;
        private List<SlaReportItem> items;
        private double overallComplianceRate;
        private String summary;

        public String getPeriod() { return period; }
        public void setPeriod(String period) { this.period = period; }

        public LocalDateTime getFrom() { return from; }
        public void setFrom(LocalDateTime from) { this.from = from; }

        public LocalDateTime getTo() { return to; }
        public void setTo(LocalDateTime to) { this.to = to; }

        public List<SlaReportItem> getItems() { return items; }
        public void setItems(List<SlaReportItem> items) { this.items = items; }

        public double getOverallComplianceRate() { return overallComplianceRate; }
        public void setOverallComplianceRate(double overallComplianceRate) {
            this.overallComplianceRate = overallComplianceRate;
        }

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
    }

    /**
     * SLA 报告单项数据模型。
     */
    public static class SlaReportItem {
        private Long targetId;
        private String targetName;
        private String targetType;
        private double targetValue;
        private double latestValue;
        private boolean currentlyMet;
        private long metCount;
        private long totalCount;
        private double complianceRate;

        public Long getTargetId() { return targetId; }
        public void setTargetId(Long targetId) { this.targetId = targetId; }

        public String getTargetName() { return targetName; }
        public void setTargetName(String targetName) { this.targetName = targetName; }

        public String getTargetType() { return targetType; }
        public void setTargetType(String targetType) { this.targetType = targetType; }

        public double getTargetValue() { return targetValue; }
        public void setTargetValue(double targetValue) { this.targetValue = targetValue; }

        public double getLatestValue() { return latestValue; }
        public void setLatestValue(double latestValue) { this.latestValue = latestValue; }

        public boolean isCurrentlyMet() { return currentlyMet; }
        public void setCurrentlyMet(boolean currentlyMet) { this.currentlyMet = currentlyMet; }

        public long getMetCount() { return metCount; }
        public void setMetCount(long metCount) { this.metCount = metCount; }

        public long getTotalCount() { return totalCount; }
        public void setTotalCount(long totalCount) { this.totalCount = totalCount; }

        public double getComplianceRate() { return complianceRate; }
        public void setComplianceRate(double complianceRate) { this.complianceRate = complianceRate; }
    }
}