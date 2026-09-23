package org.nexus.gateway.export;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * DataExportController 单元测试 — 使用 Mockito mock DataExportService。
 *
 * <p>覆盖创建导出请求、列出导出请求、查看导出状态、下载导出文件、
 * 删除导出请求等 API 端点场景。</p>
 *
 * <p>P0-1 修复后：所有端点需要 MerchantOwnershipGuard 校验，
 * 测试中 mock guard 始终返回 MERCHANT_ID 以通过校验。</p>
 */
class DataExportControllerTest {

    private DataExportService dataExportService;
    private MerchantOwnershipGuard ownershipGuard;
    private DataExportController dataExportController;
    private HttpServletRequest httpRequest;

    private static final Long MERCHANT_ID = 500L;

    @BeforeEach
    void setUp() {
        dataExportService = mock(DataExportService.class);
        ownershipGuard = mock(MerchantOwnershipGuard.class);
        httpRequest = mock(HttpServletRequest.class);
        // P0-1：mock guard 始终返回 MERCHANT_ID，校验通过
        when(ownershipGuard.requireMerchantId(httpRequest)).thenReturn(MERCHANT_ID);
        dataExportController = new DataExportController(dataExportService, ownershipGuard);
    }

    // ==================== POST /api/v1/data-exports ====================

    @Test
    @DisplayName("createExport — 创建 CSV 导出请求并返回 200")
    void createExportCsvReturns200() {
        Map<String, Object> body = new HashMap<>();
        body.put("merchantId", MERCHANT_ID);
        body.put("exportType", "TRANSACTIONS");
        body.put("format", "CSV");
        body.put("dateFrom", "2026-09-01T00:00:00");
        body.put("dateTo", "2026-09-30T23:59:59");

        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.TRANSACTIONS, DataExportFormat.CSV);

        when(dataExportService.createExportRequest(any(), any(), any(), any(), any(), any()))
                .thenReturn(request);

        ResponseEntity<DataExportRequest> response = dataExportController.createExport(body, httpRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1L, response.getBody().getId());
        assertEquals(DataExportRequest.Status.PENDING, response.getBody().getStatus());
        verify(dataExportService).processExport(1L);
    }

    @Test
    @DisplayName("createExport — 创建 JSON 导出请求并返回 200")
    void createExportJsonReturns200() {
        Map<String, Object> body = new HashMap<>();
        body.put("merchantId", MERCHANT_ID);
        body.put("exportType", "REFUNDS");
        body.put("format", "JSON");
        body.put("dateFrom", "2026-09-01T00:00:00");
        body.put("dateTo", "2026-09-30T23:59:59");
        body.put("filters", "{\"status\":\"COMPLETED\"}");

        DataExportRequest request = createRequest(2L, MERCHANT_ID,
                DataExportType.REFUNDS, DataExportFormat.JSON);

        when(dataExportService.createExportRequest(any(), any(), any(), any(), any(), any()))
                .thenReturn(request);

        ResponseEntity<DataExportRequest> response = dataExportController.createExport(body, httpRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(DataExportFormat.JSON, response.getBody().getFormat());
        verify(dataExportService).processExport(2L);
    }

    // ==================== GET /api/v1/data-exports ====================

    @Test
    @DisplayName("listExports — 返回商户的导出请求列表")
    void listExportsReturnsRequestList() {
        DataExportRequest r1 = createRequest(1L, MERCHANT_ID,
                DataExportType.TRANSACTIONS, DataExportFormat.CSV);
        DataExportRequest r2 = createRequest(2L, MERCHANT_ID,
                DataExportType.REFUNDS, DataExportFormat.JSON);

        when(dataExportService.listExportRequests(MERCHANT_ID))
                .thenReturn(List.of(r1, r2));

        ResponseEntity<List<DataExportRequest>> response =
                dataExportController.listExports(MERCHANT_ID, httpRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
    }

    @Test
    @DisplayName("listExports — 无导出请求时返回空列表")
    void listExportsEmptyReturnsEmptyList() {
        when(dataExportService.listExportRequests(MERCHANT_ID))
                .thenReturn(List.of());

        ResponseEntity<List<DataExportRequest>> response =
                dataExportController.listExports(MERCHANT_ID, httpRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty());
    }

    // ==================== GET /api/v1/data-exports/{id} ====================

    @Test
    @DisplayName("getExport — 查看导出请求状态返回 200")
    void getExportReturns200() {
        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.TRANSACTIONS, DataExportFormat.CSV);
        request.setStatus(DataExportRequest.Status.COMPLETED);

        when(dataExportService.getExportRequest(1L)).thenReturn(Optional.of(request));

        ResponseEntity<DataExportRequest> response = dataExportController.getExport(1L, httpRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(DataExportRequest.Status.COMPLETED, response.getBody().getStatus());
    }

    @Test
    @DisplayName("getExport — 请求不存在时返回 404")
    void getExportNotFoundReturns404() {
        when(dataExportService.getExportRequest(999L)).thenReturn(Optional.empty());

        ResponseEntity<DataExportRequest> response = dataExportController.getExport(999L, httpRequest);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ==================== GET /api/v1/data-exports/{id}/download ====================

    @Test
    @DisplayName("downloadExport — 成功下载 CSV 文件返回 200")
    void downloadExportCsvReturns200() {
        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.TRANSACTIONS, DataExportFormat.CSV);
        request.setStatus(DataExportRequest.Status.COMPLETED);

        when(dataExportService.getExportRequest(1L)).thenReturn(Optional.of(request));
        when(dataExportService.downloadExport(1L))
                .thenReturn(Optional.of("test,csv,data".getBytes()));

        ResponseEntity<byte[]> response = dataExportController.downloadExport(1L, httpRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 0);
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getFirst("Content-Disposition").contains(".csv"));
    }

    @Test
    @DisplayName("downloadExport — 成功下载 JSON 文件返回 200")
    void downloadExportJsonReturns200() {
        DataExportRequest request = createRequest(2L, MERCHANT_ID,
                DataExportType.REFUNDS, DataExportFormat.JSON);
        request.setStatus(DataExportRequest.Status.COMPLETED);

        when(dataExportService.getExportRequest(2L)).thenReturn(Optional.of(request));
        when(dataExportService.downloadExport(2L))
                .thenReturn(Optional.of("{\"data\":[]}".getBytes()));

        ResponseEntity<byte[]> response = dataExportController.downloadExport(2L, httpRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getHeaders().getFirst("Content-Disposition").contains(".json"));
    }

    @Test
    @DisplayName("downloadExport — 请求不存在时返回 404")
    void downloadExportNotFoundReturns404() {
        when(dataExportService.getExportRequest(999L)).thenReturn(Optional.empty());

        ResponseEntity<byte[]> response = dataExportController.downloadExport(999L, httpRequest);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("downloadExport — 未完成的请求返回 400")
    void downloadExportNotCompletedReturns400() {
        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.TRANSACTIONS, DataExportFormat.CSV);
        request.setStatus(DataExportRequest.Status.PROCESSING);

        when(dataExportService.getExportRequest(1L)).thenReturn(Optional.of(request));

        ResponseEntity<byte[]> response = dataExportController.downloadExport(1L, httpRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("downloadExport — 文件不存在时返回 404")
    void downloadExportFileNotFoundReturns404() {
        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.TRANSACTIONS, DataExportFormat.CSV);
        request.setStatus(DataExportRequest.Status.COMPLETED);

        when(dataExportService.getExportRequest(1L)).thenReturn(Optional.of(request));
        when(dataExportService.downloadExport(1L)).thenReturn(Optional.empty());

        ResponseEntity<byte[]> response = dataExportController.downloadExport(1L, httpRequest);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ==================== DELETE /api/v1/data-exports/{id} ====================

    @Test
    @DisplayName("deleteExport — 删除成功返回 204")
    void deleteExportSuccessReturns204() {
        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.TRANSACTIONS, DataExportFormat.CSV);

        when(dataExportService.getExportRequest(1L)).thenReturn(Optional.of(request));
        when(dataExportService.deleteExport(1L)).thenReturn(true);

        ResponseEntity<Void> response = dataExportController.deleteExport(1L, httpRequest);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    @DisplayName("deleteExport — 请求不存在时返回 404")
    void deleteExportNotFoundReturns404() {
        when(dataExportService.getExportRequest(999L)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = dataExportController.deleteExport(999L, httpRequest);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ==================== 辅助方法 ====================

    private DataExportRequest createRequest(Long id, Long merchantId,
                                             DataExportType exportType,
                                             DataExportFormat format) {
        DataExportRequest request = new DataExportRequest();
        request.setId(id);
        request.setMerchantId(merchantId);
        request.setExportType(exportType);
        request.setFormat(format);
        request.setDateFrom(LocalDateTime.of(2026, 9, 1, 0, 0));
        request.setDateTo(LocalDateTime.of(2026, 9, 30, 23, 59));
        request.setStatus(DataExportRequest.Status.PENDING);
        return request;
    }
}
