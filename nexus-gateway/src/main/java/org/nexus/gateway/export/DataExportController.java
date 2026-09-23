package org.nexus.gateway.export;

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
 */
@RestController
@RequestMapping("/api/v1/data-exports")
@PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
public class DataExportController {

    private final DataExportService dataExportService;

    public DataExportController(DataExportService dataExportService) {
        this.dataExportService = dataExportService;
    }

    /**
     * 创建数据导出请求。
     *
     * <p>请求体示例：
     * <pre>{@code
     * {
     *   "merchantId": 500,
     *   "exportType": "TRANSACTIONS",
     *   "format": "CSV",
     *   "dateFrom": "2026-09-01T00:00:00",
     *   "dateTo": "2026-09-30T23:59:59",
     *   "filters": null
     * }
     * }</pre>
     *
     * @param body 请求参数
     * @return 创建的导出请求（包含 requestId）
     */
    @PostMapping
    public ResponseEntity<DataExportRequest> createExport(@RequestBody Map<String, Object> body) {
        Long merchantId = Long.valueOf(body.get("merchantId").toString());
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
     * @param merchantId 商户 ID
     * @return 导出请求列表
     */
    @GetMapping
    public ResponseEntity<List<DataExportRequest>> listExports(@RequestParam Long merchantId) {
        List<DataExportRequest> requests = dataExportService.listExportRequests(merchantId);
        return ResponseEntity.ok(requests);
    }

    /**
     * 查看导出请求状态。
     *
     * @param id 导出请求 ID
     * @return 导出请求详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<DataExportRequest> getExport(@PathVariable Long id) {
        Optional<DataExportRequest> request = dataExportService.getExportRequest(id);
        return request.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 下载导出文件。
     *
     * @param id 导出请求 ID
     * @return 文件内容流
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> downloadExport(@PathVariable Long id) {
        Optional<DataExportRequest> requestOpt = dataExportService.getExportRequest(id);
        if (requestOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        DataExportRequest request = requestOpt.get();
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
     * @param id 导出请求 ID
     * @return 204 No Content 如果删除成功，404 如果不存在
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteExport(@PathVariable Long id) {
        boolean deleted = dataExportService.deleteExport(id);
        if (deleted) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}