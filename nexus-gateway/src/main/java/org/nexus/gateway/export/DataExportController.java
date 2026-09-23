package org.nexus.gateway.export;

import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据导出 REST API。
 *
 * <p>提供创建导出请求、查询导出列表/状态、下载导出文件、删除导出请求等端点。
 * 所有端点要求 MERCHANT 或 ADMIN 角色权限。</p>
 *
 * <p>P0-1 修复：所有端点添加商户归属校验，从认证上下文获取 callerMerchantId，
 * 与请求中的 merchantId 比对，不一致则拒绝访问（fail-closed）。</p>
 */
@RestController
@RequestMapping("/api/v1/data-exports")
@PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
public class DataExportController {

    private final DataExportService dataExportService;
    private final MerchantOwnershipGuard ownershipGuard;

    public DataExportController(DataExportService dataExportService,
                                MerchantOwnershipGuard ownershipGuard) {
        this.dataExportService = dataExportService;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * 创建数据导出请求。
     *
     * <p>P0-1：请求体中的 merchantId 必须与认证上下文中的 tenantId 一致。</p>
     *
     * @param body        请求参数
     * @param httpRequest HTTP 请求（用于获取认证上下文）
     * @return 创建的导出请求（包含 requestId）
     */
    @PostMapping
    public ResponseEntity<DataExportRequest> createExport(@RequestBody Map<String, Object> body,
                                                          HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        Long merchantId = Long.valueOf(body.get("merchantId").toString());
        ownershipGuard.requireOwned(callerMerchantId, merchantId, "data-export", merchantId);

        DataExportType exportType = DataExportType.valueOf(body.get("exportType").toString());
        DataExportFormat format = DataExportFormat.valueOf(body.get("format").toString());
        LocalDateTime dateFrom = LocalDateTime.parse(body.get("dateFrom").toString());
        LocalDateTime dateTo = LocalDateTime.parse(body.get("dateTo").toString());
        String filters = body.containsKey("filters") && body.get("filters") != null
                ? body.get("filters").toString() : null;

        DataExportRequest request = dataExportService.createExportRequest(
                merchantId, exportType, format, dateFrom, dateTo, filters);

        // 异步触发导出处理
        dataExportService.processExport(request.getId());

        return ResponseEntity.ok(request);
    }

    /**
     * 列出当前租户的导出请求。
     *
     * <p>P0-1：请求参数 merchantId 必须与认证上下文一致。</p>
     *
     * @param merchantId  商户 ID
     * @param httpRequest HTTP 请求
     * @return 导出请求列表
     */
    @GetMapping
    public ResponseEntity<List<DataExportRequest>> listExports(@RequestParam Long merchantId,
                                                               HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        ownershipGuard.requireOwned(callerMerchantId, merchantId, "data-export", merchantId);

        List<DataExportRequest> requests = dataExportService.listExportRequests(merchantId);
        return ResponseEntity.ok(requests);
    }

    /**
     * 查看导出请求状态。
     *
     * <p>P0-1：导出请求必须属于当前认证商户。</p>
     *
     * @param id          导出请求 ID
     * @param httpRequest HTTP 请求
     * @return 导出请求详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<DataExportRequest> getExport(@PathVariable Long id,
                                                       HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        Optional<DataExportRequest> request = dataExportService.getExportRequest(id);
        if (request.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ownershipGuard.requireOwned(callerMerchantId, request.get().getMerchantId(), "data-export", id);
        return ResponseEntity.ok(request.get());
    }

    /**
     * 下载导出文件。
     *
     * <p>P0-1：导出请求必须属于当前认证商户。</p>
     *
     * @param id          导出请求 ID
     * @param httpRequest HTTP 请求
     * @return 文件内容流
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> downloadExport(@PathVariable Long id,
                                                 HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        Optional<DataExportRequest> requestOpt = dataExportService.getExportRequest(id);
        if (requestOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        DataExportRequest request = requestOpt.get();
        ownershipGuard.requireOwned(callerMerchantId, request.getMerchantId(), "data-export", id);

        if (request.getStatus() != DataExportRequest.Status.COMPLETED) {
            return ResponseEntity.badRequest().build();
        }

        Optional<byte[]> content = dataExportService.downloadExport(id);
        if (content.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        String filename = "export_" + request.getExportType().name().toLowerCase()
                + "_" + request.getId();

        if (request.getFormat() == DataExportFormat.JSON) {
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + ".json\"");
        } else {
            headers.setContentType(MediaType.parseMediaType("text/csv"));
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + ".csv\"");
        }

        return ResponseEntity.ok().headers(headers).body(content.get());
    }

    /**
     * 删除导出请求和文件。
     *
     * <p>P0-1：导出请求必须属于当前认证商户。</p>
     *
     * @param id          导出请求 ID
     * @param httpRequest HTTP 请求
     * @return 204 No Content 如果删除成功，404 如果不存在
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteExport(@PathVariable Long id,
                                             HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        Optional<DataExportRequest> requestOpt = dataExportService.getExportRequest(id);
        if (requestOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ownershipGuard.requireOwned(callerMerchantId, requestOpt.get().getMerchantId(), "data-export", id);

        boolean deleted = dataExportService.deleteExport(id);
        if (deleted) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
