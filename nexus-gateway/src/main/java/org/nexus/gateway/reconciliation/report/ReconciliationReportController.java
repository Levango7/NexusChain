package org.nexus.gateway.reconciliation.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 对账报表 REST API。
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>{@code GET /api/reconciliation/reports/{id}} — 查询报表记录</li>
 *   <li>{@code GET /api/reconciliation/reports/merchant/{merchantId}} — 查询商户报表列表</li>
 *   <li>{@code POST /api/reconciliation/reports/generate} — 生成对账报表</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/reconciliation/reports")
public class ReconciliationReportController {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationReportController.class);

    private final ReconciliationReportService reportService;

    public ReconciliationReportController(ReconciliationReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * 查询报表记录。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ReconciliationReportRecord> getReport(@PathVariable Long id) {
        return reportService.getReport(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 查询商户的报表列表（按生成时间倒序）。
     */
    @GetMapping("/merchant/{merchantId}")
    public ResponseEntity<List<ReconciliationReportRecord>> getReportsByMerchant(
            @PathVariable Long merchantId) {
        List<ReconciliationReportRecord> reports = reportService.getReportsByMerchant(merchantId);
        return ResponseEntity.ok(reports);
    }

    /**
     * 生成对账报表。
     *
     * <p>请求体示例：
     * <pre>
     * {
     *   "merchantId": 1001,
     *   "reportDate": "2026-09-27",
     *   "channelType": "WECHAT"
     * }
     * </pre>
     * </p>
     *
     * @param request 生成请求
     * @return 生成的报表记录
     */
    @PostMapping("/generate")
    public ResponseEntity<ReconciliationReportRecord> generateReport(
            @RequestBody GenerateReportRequest request) {
        log.info("[ReportController] 生成报表请求: merchantId={}, reportDate={}, channelType={}",
                request.getMerchantId(), request.getReportDate(), request.getChannelType());

        // 如果未指定 reportDate，默认使用前一天
        LocalDate reportDate = request.getReportDate();
        if (reportDate == null) {
            reportDate = LocalDate.now().minusDays(1);
            log.info("[ReportController] 未指定 reportDate，使用前一天: {}", reportDate);
        }

        ReconciliationReportRecord record = reportService.generateReport(
                request.getMerchantId(), reportDate, request.getChannelType());

        return ResponseEntity.ok(record);
    }

    /**
     * 生成报表请求 DTO。
     */
    public static class GenerateReportRequest {
        private Long merchantId;
        private LocalDate reportDate;
        private String channelType;

        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

        public LocalDate getReportDate() { return reportDate; }
        public void setReportDate(LocalDate reportDate) { this.reportDate = reportDate; }

        public String getChannelType() { return channelType; }
        public void setChannelType(String channelType) { this.channelType = channelType; }
    }
}