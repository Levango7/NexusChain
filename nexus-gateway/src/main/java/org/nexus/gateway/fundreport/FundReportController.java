package org.nexus.gateway.fundreport;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 资金报表 REST API — 商户资金流水报表生成与查询。
 *
 * <p>提供日报/周报/月报生成、报表列表查询、报表详情查询等功能。
 * 路径前缀：{@code /api/v1/fund-reports}</p>
 *
 * <h3>鉴权（2026-10-25 修复跨商户越权）</h3>
 *
 * <p><b>修复前</b>：三个端点只要求 {@code hasRole('MERCHANT') or hasRole('ADMIN')}，
 * 路径 {@code merchantId} 直接交给 Service；而 {@code /detail/{reportNo}} <b>连路径
 * merchantId 都没有</b>，只按报表号查出来就整份返回（响应体里还带着
 * {@code report.getMerchantId()}）—— 任何商户换一个报表号即可读他人报表。</p>
 *
 * <p><b>两类修法不同</b>：</p>
 * <ul>
 *   <li><b>路径带 merchantId</b>（{@code generate} / 列表）：比对
 *       {@code ApiKeyInterceptor} 注入的 {@code nexus.merchantId}；</li>
 *   <li><b>按资源号查</b>（{@code /detail/{reportNo}}）：先加载资源，再比对
 *       {@code report.getMerchantId()} —— 路径参数守卫管不到这种形态。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/fund-reports")
@Tag(name = "FundReport", description = "资金报表管理：日报/周报/月报生成与查询")
public class FundReportController {

    private static final Logger log = LoggerFactory.getLogger(FundReportController.class);

    private final FundReportService fundReportService;
    private final MerchantOwnershipGuard ownershipGuard;

    public FundReportController(FundReportService fundReportService,
                                MerchantOwnershipGuard ownershipGuard) {
        this.fundReportService = fundReportService;
        this.ownershipGuard = ownershipGuard;
    }

    /** 归属校验（路径 merchantId 形态）。 */
    private void requireOwnMerchant(HttpServletRequest request, Long merchantId) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(request);
        ownershipGuard.requireOwned(callerMerchantId, merchantId, "fund-report", merchantId);
    }

    /**
     * 归属校验（资源号形态）：先加载报表，再比对它属于谁。
     *
     * @return 已确认属于调用方的报表
     */
    private FundReport requireOwnReport(HttpServletRequest request, String reportNo) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(request);
        FundReport report = fundReportService.getReportByNo(reportNo).orElse(null);
        if (report == null) {
            return null;
        }
        ownershipGuard.requireOwned(callerMerchantId, report.getMerchantId(), "fund-report", 0L);
        return report;
    }

    /**
     * 生成资金报表。
     *
     * @param merchantId 商户 ID（必须属于调用方）
     * @param body 请求体（reportType + reportFormat）
     * @param request HTTP 请求（承载 ApiKeyInterceptor 注入的商户上下文）
     * @return 200 + 生成的报表信息
     */
    @Operation(summary = "生成资金报表（日报/周报/月报）")
    @PostMapping("/{merchantId}/generate")
    public ResponseEntity<Map<String, Object>> generateReport(
            @PathVariable Long merchantId,
            @RequestBody GenerateReportRequest body,
            HttpServletRequest request) {

        requireOwnMerchant(request, merchantId);

        // 输入验证：reportType 和 reportFormat 不能为空
        if (body.getReportType() == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", "INVALID_PARAMETER");
            error.put("message", "报表类型不能为空");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
        if (body.getReportFormat() == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", "INVALID_PARAMETER");
            error.put("message", "报表格式不能为空");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }

        ReportType reportType = ReportType.valueOf(body.getReportType());
        ReportFormat reportFormat = ReportFormat.valueOf(body.getReportFormat());

        FundReport report = fundReportService.generateReport(merchantId, reportType, reportFormat);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportNo", report.getReportNo());
        result.put("merchantId", report.getMerchantId());
        result.put("reportType", report.getReportType().name());
        result.put("reportFormat", report.getReportFormat().name());
        result.put("periodStart", report.getPeriodStart());
        result.put("periodEnd", report.getPeriodEnd());
        result.put("status", report.getStatus());
        result.put("createdAt", report.getCreatedAt());

        return ResponseEntity.ok(result);
    }

    /**
     * 查询商户报表列表（分页）。
     *
     * @param merchantId 商户 ID
     * @param page 页码（从 0 开始，默认 0）
     * @param size 每页条数（默认 20）
     * @return 200 + 报表分页结果
     */
    @Operation(summary = "查询商户报表列表")
    @GetMapping("/{merchantId}")
    public ResponseEntity<Map<String, Object>> getReports(
            @PathVariable Long merchantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {

        requireOwnMerchant(request, merchantId);

        // 分页参数上限验证
        size = Math.min(size, 100);

        Page<FundReport> reportPage = fundReportService.getReports(merchantId, page, size);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merchantId", merchantId);
        result.put("page", page);
        result.put("size", size);
        result.put("total", reportPage.getTotalElements());
        result.put("totalPages", reportPage.getTotalPages());

        result.put("reports", reportPage.getContent().stream()
                .map(this::toReportSummary)
                .toList());

        return ResponseEntity.ok(result);
    }

    /**
     * 查询报表详情（含完整内容）。
     *
     * <p><b>资源号形态</b>：{@code reportNo} 不是商户标识，必须加载报表后比对
     * {@code report.getMerchantId()}。修复前此处只按报表号取出来就整份返回，
     * 任何商户换一个报表号即可读他人报表（响应体还带着对方的 merchantId）。</p>
     *
     * @param reportNo 报表编号
     * @param request  HTTP 请求（承载 ApiKeyInterceptor 注入的商户上下文）
     * @return 200 + 报表详情
     */
    @Operation(summary = "查询报表详情")
    @GetMapping("/detail/{reportNo}")
    public ResponseEntity<Map<String, Object>> getReportDetail(@PathVariable String reportNo,
                                                               HttpServletRequest request) {
        // 资源号形态：先加载再比对归属（路径参数守卫管不到这里）
        FundReport report = requireOwnReport(request, reportNo);

        if (report == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", "REPORT_NOT_FOUND");
            error.put("message", "报表不存在: " + reportNo);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportNo", report.getReportNo());
        result.put("merchantId", report.getMerchantId());
        result.put("reportType", report.getReportType().name());
        result.put("reportFormat", report.getReportFormat().name());
        result.put("periodStart", report.getPeriodStart());
        result.put("periodEnd", report.getPeriodEnd());
        result.put("content", report.getContent());
        result.put("status", report.getStatus());
        result.put("createdAt", report.getCreatedAt());

        return ResponseEntity.ok(result);
    }

    // === 异常处理 ===

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "INVALID_PARAMETER");
        error.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // === 内部方法 ===

    private Map<String, Object> toReportSummary(FundReport report) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reportNo", report.getReportNo());
        map.put("reportType", report.getReportType().name());
        map.put("reportFormat", report.getReportFormat().name());
        map.put("periodStart", report.getPeriodStart());
        map.put("periodEnd", report.getPeriodEnd());
        map.put("status", report.getStatus());
        map.put("createdAt", report.getCreatedAt());
        return map;
    }

    // === DTO ===

    public static class GenerateReportRequest {
        /** 报表类型：DAILY/WEEKLY/MONTHLY */
        private String reportType;
        /** 报表格式：CSV/JSON */
        private String reportFormat;

        public String getReportType() { return reportType; }
        public void setReportType(String reportType) { this.reportType = reportType; }

        public String getReportFormat() { return reportFormat; }
        public void setReportFormat(String reportFormat) { this.reportFormat = reportFormat; }
    }
}