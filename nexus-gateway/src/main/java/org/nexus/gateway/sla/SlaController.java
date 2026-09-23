package org.nexus.gateway.sla;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SLA 管理 REST API。
 *
 * <p>提供 SLA 目标的 CRUD 操作、测量记录查询、报告生成和仪表盘数据接口。</p>
 */
@RestController
@RequestMapping("/api/v1/sla")
public class SlaController {

    private static final Logger log = LoggerFactory.getLogger(SlaController.class);

    private final SlaTargetRepository targetRepository;
    private final SlaMeasurementRepository measurementRepository;
    private final SlaReportService reportService;

    public SlaController(SlaTargetRepository targetRepository,
                         SlaMeasurementRepository measurementRepository,
                         SlaReportService reportService) {
        this.targetRepository = targetRepository;
        this.measurementRepository = measurementRepository;
        this.reportService = reportService;
    }

    // === SLA 目标 CRUD ===

    /** 列出所有 SLA 目标 */
    @GetMapping("/targets")
    public ResponseEntity<List<SlaTarget>> listTargets() {
        List<SlaTarget> targets = targetRepository.findAll();
        return ResponseEntity.ok(targets);
    }

    /** 创建 SLA 目标 */
    @PostMapping("/targets")
    public ResponseEntity<SlaTarget> createTarget(@RequestBody SlaTarget target) {
        if (target.getName() == null || target.getMetricName() == null
                || target.getTargetType() == null) {
            return ResponseEntity.badRequest().build();
        }
        SlaTarget saved = targetRepository.save(target);
        log.info("创建 SLA 目标: id={}, name={}", saved.getId(), saved.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /** 更新 SLA 目标 */
    @PutMapping("/targets/{id}")
    public ResponseEntity<SlaTarget> updateTarget(@PathVariable Long id,
                                                   @RequestBody SlaTarget target) {
        return targetRepository.findById(id)
                .map(existing -> {
                    updateExistingTarget(existing, target);
                    SlaTarget saved = targetRepository.save(existing);
                    log.info("更新 SLA 目标: id={}", id);
                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** 删除 SLA 目标 */
    @DeleteMapping("/targets/{id}")
    public ResponseEntity<Void> deleteTarget(@PathVariable Long id) {
        return targetRepository.findById(id)
                .map(existing -> {
                    targetRepository.delete(existing);
                    log.info("删除 SLA 目标: id={}", id);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // === 测量记录查询 ===

    /** 查询测量记录（支持时间范围过滤和目标 ID 过滤） */
    @GetMapping("/measurements")
    public ResponseEntity<List<SlaMeasurement>> queryMeasurements(
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        LocalDateTime fromTime = from != null ? LocalDate.parse(from).atStartOfDay() : null;
        LocalDateTime toTime = to != null ? LocalDate.parse(to).plusDays(1).atStartOfDay() : null;

        List<SlaMeasurement> measurements;
        if (targetId != null && fromTime != null && toTime != null) {
            measurements = measurementRepository
                    .findByTargetIdAndMeasuredAtBetweenOrderByMeasuredAtDesc(targetId, fromTime, toTime);
        } else if (targetId != null) {
            measurements = measurementRepository.findByTargetIdOrderByMeasuredAtDesc(targetId);
        } else if (fromTime != null && toTime != null) {
            measurements = measurementRepository.findByMeasuredAtBetweenOrderByMeasuredAtDesc(fromTime, toTime);
        } else {
            measurements = measurementRepository.findAll();
        }

        return ResponseEntity.ok(measurements);
    }

    // === SLA 报告 ===

    /** 生成 SLA 报告（参数：period=daily/weekly/monthly, from, to） */
    @GetMapping("/report")
    public ResponseEntity<SlaReportService.SlaReport> generateReport(
            @RequestParam(defaultValue = "daily") String period,
            @RequestParam String from,
            @RequestParam String to) {

        LocalDateTime fromTime = LocalDate.parse(from).atStartOfDay();
        LocalDateTime toTime = LocalDate.parse(to).plusDays(1).atStartOfDay();

        SlaReportService.SlaReport report = reportService.generateReport(period, fromTime, toTime);
        return ResponseEntity.ok(report);
    }

    // === SLA 仪表盘 ===

    /** SLA 仪表盘数据（当前各目标达标状态） */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard() {
        List<SlaTarget> targets = targetRepository.findByEnabledTrue();
        Map<String, Object> dashboardData = new HashMap<>();

        List<Map<String, Object>> targetStatuses = targets.stream()
                .map(target -> {
                    Map<String, Object> status = new HashMap<>();
                    status.put("targetId", target.getId());
                    status.put("targetName", target.getName());
                    status.put("targetType", target.getTargetType().name());
                    status.put("targetValue", target.getTargetValue());

                    // 获取最近一次测量记录
                    List<SlaMeasurement> recent = measurementRepository
                            .findByTargetIdOrderByMeasuredAtDesc(target.getId());
                    if (!recent.isEmpty()) {
                        SlaMeasurement latest = recent.get(0);
                        status.put("latestValue", latest.getMeasuredValue());
                        status.put("isMet", latest.isMet());
                        status.put("measuredAt", latest.getMeasuredAt());
                    } else {
                        status.put("latestValue", null);
                        status.put("isMet", null);
                        status.put("measuredAt", null);
                    }

                    return status;
                })
                .toList();

        dashboardData.put("targets", targetStatuses);
        long totalTargets = targets.size();
        long metTargets = targetStatuses.stream()
                .filter(s -> Boolean.TRUE.equals(s.get("isMet")))
                .count();
        dashboardData.put("totalTargets", totalTargets);
        dashboardData.put("metTargets", metTargets);
        dashboardData.put("breachedTargets", totalTargets - metTargets);

        return ResponseEntity.ok(dashboardData);
    }

    // === 私有方法 ===

    private void updateExistingTarget(SlaTarget existing, SlaTarget update) {
        if (update.getName() != null) {
            existing.setName(update.getName());
        }
        if (update.getMetricName() != null) {
            existing.setMetricName(update.getMetricName());
        }
        existing.setTargetValue(update.getTargetValue());
        if (update.getTargetType() != null) {
            existing.setTargetType(update.getTargetType());
        }
        existing.setWindowMinutes(update.getWindowMinutes());
        existing.setEnabled(update.isEnabled());
        if (update.getDescription() != null) {
            existing.setDescription(update.getDescription());
        }
    }
}