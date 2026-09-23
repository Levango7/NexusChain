package org.nexus.gateway.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.model.Refund;
import org.nexus.gateway.orchestration.webhook.WebhookDeliveryRecord;
import org.nexus.gateway.orchestration.webhook.WebhookDeliveryRepository;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.gateway.repository.RefundRepository;
import org.nexus.gateway.risk.RiskEvent;
import org.nexus.gateway.risk.RiskEventRepository;
import org.nexus.gateway.split.SplitOrder;
import org.nexus.gateway.split.SplitOrderRepository;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * DataExportService 单元测试 — 使用 Mockito mock repositories。
 *
 * <p>覆盖创建导出请求、处理导出（CSV/JSON）、查询/列表、下载、删除、
 * 过期清理等核心场景。</p>
 */
class DataExportServiceTest {

    private DataExportRequestRepository exportRequestRepository;
    private PaymentOrderRepository paymentOrderRepository;
    private RefundRepository refundRepository;
    private SplitOrderRepository splitOrderRepository;
    private RiskEventRepository riskEventRepository;
    private WebhookDeliveryRepository webhookDeliveryRepository;
    private ObjectMapper objectMapper;
    private DataExportService dataExportService;

    @TempDir
    Path tempDir;

    private static final Long MERCHANT_ID = 500L;

    @BeforeEach
    void setUp() {
        exportRequestRepository = mock(DataExportRequestRepository.class);
        paymentOrderRepository = mock(PaymentOrderRepository.class);
        refundRepository = mock(RefundRepository.class);
        splitOrderRepository = mock(SplitOrderRepository.class);
        riskEventRepository = mock(RiskEventRepository.class);
        webhookDeliveryRepository = mock(WebhookDeliveryRepository.class);
        objectMapper = new ObjectMapper();

        dataExportService = new DataExportService(
                exportRequestRepository,
                paymentOrderRepository,
                refundRepository,
                splitOrderRepository,
                riskEventRepository,
                webhookDeliveryRepository,
                objectMapper);

        // 使用反射注入 @Value 字段
        setField(dataExportService, "storagePath", tempDir.toString());
        setField(dataExportService, "fileRetentionDays", 7);
        setField(dataExportService, "maxRecordsPerExport", 100000);
    }

    // ==================== createExportRequest ====================

    @Test
    @DisplayName("createExportRequest — 创建 PENDING 状态的导出请求")
    void createExportRequestCreatesPendingRequest() {
        LocalDateTime dateFrom = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime dateTo = LocalDateTime.of(2026, 9, 30, 23, 59);

        when(exportRequestRepository.save(any(DataExportRequest.class)))
                .thenAnswer(inv -> {
                    DataExportRequest r = inv.getArgument(0);
                    r.setId(1L);
                    return r;
                });

        DataExportRequest request = dataExportService.createExportRequest(
                MERCHANT_ID, DataExportType.TRANSACTIONS, DataExportFormat.CSV,
                dateFrom, dateTo, null);

        assertNotNull(request);
        assertEquals(1L, request.getId());
        assertEquals(MERCHANT_ID, request.getMerchantId());
        assertEquals(DataExportType.TRANSACTIONS, request.getExportType());
        assertEquals(DataExportFormat.CSV, request.getFormat());
        assertEquals(DataExportRequest.Status.PENDING, request.getStatus());
        assertEquals(dateFrom, request.getDateFrom());
        assertEquals(dateTo, request.getDateTo());
        assertNull(request.getFilters());
    }

    @Test
    @DisplayName("createExportRequest — 带 filters 参数创建导出请求")
    void createExportRequestWithFilters() {
        LocalDateTime dateFrom = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime dateTo = LocalDateTime.of(2026, 9, 30, 23, 59);
        String filters = "{\"status\":\"PAID\"}";

        when(exportRequestRepository.save(any(DataExportRequest.class)))
                .thenAnswer(inv -> {
                    DataExportRequest r = inv.getArgument(0);
                    r.setId(1L);
                    return r;
                });

        DataExportRequest request = dataExportService.createExportRequest(
                MERCHANT_ID, DataExportType.TRANSACTIONS, DataExportFormat.JSON,
                dateFrom, dateTo, filters);

        assertEquals(filters, request.getFilters());
        assertEquals(DataExportFormat.JSON, request.getFormat());
    }

    // ==================== processExport — TRANSACTIONS CSV ====================

    @Test
    @DisplayName("processExport — TRANSACTIONS 类型 CSV 导出成功")
    void processExportTransactionsCsvSuccess() {
        LocalDateTime dateFrom = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime dateTo = LocalDateTime.of(2026, 9, 30, 23, 59);

        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.TRANSACTIONS, DataExportFormat.CSV,
                dateFrom, dateTo);

        PaymentOrder order = createPaymentOrder("ORD-001", new BigDecimal("100"),
                PaymentOrder.OrderStatus.PAID, dateFrom.plusDays(5));

        when(exportRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(exportRequestRepository.save(any(DataExportRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(order));

        dataExportService.processExport(1L);

        assertEquals(DataExportRequest.Status.COMPLETED, request.getStatus());
        assertNotNull(request.getFilePath());
        assertNotNull(request.getFileSizeBytes());
        assertEquals(1, request.getRecordCount());
        assertNotNull(request.getCompletedAt());
    }

    @Test
    @DisplayName("processExport — TRANSACTIONS 类型 JSON 导出成功")
    void processExportTransactionsJsonSuccess() {
        LocalDateTime dateFrom = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime dateTo = LocalDateTime.of(2026, 9, 30, 23, 59);

        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.TRANSACTIONS, DataExportFormat.JSON,
                dateFrom, dateTo);

        PaymentOrder order = createPaymentOrder("ORD-001", new BigDecimal("100"),
                PaymentOrder.OrderStatus.PAID, dateFrom.plusDays(5));

        when(exportRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(exportRequestRepository.save(any(DataExportRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(order));

        dataExportService.processExport(1L);

        assertEquals(DataExportRequest.Status.COMPLETED, request.getStatus());
        assertNotNull(request.getFilePath());
        assertTrue(request.getFilePath().endsWith(".json"));
    }

    // ==================== processExport — REFUNDS ====================

    @Test
    @DisplayName("processExport — REFUNDS 类型 CSV 导出成功")
    void processExportRefundsCsvSuccess() {
        LocalDateTime dateFrom = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime dateTo = LocalDateTime.of(2026, 9, 30, 23, 59);

        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.REFUNDS, DataExportFormat.CSV,
                dateFrom, dateTo);

        Refund refund = createRefund("RFD-001", 100L, new BigDecimal("50"),
                Refund.RefundStatus.COMPLETED, dateFrom.plusDays(5));

        when(exportRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(exportRequestRepository.save(any(DataExportRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(refundRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(refund));

        dataExportService.processExport(1L);

        assertEquals(DataExportRequest.Status.COMPLETED, request.getStatus());
        assertEquals(1, request.getRecordCount());
    }

    // ==================== processExport — SPLITS ====================

    @Test
    @DisplayName("processExport — SPLITS 类型 CSV 导出成功")
    void processExportSplitsCsvSuccess() {
        LocalDateTime dateFrom = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime dateTo = LocalDateTime.of(2026, 9, 30, 23, 59);

        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.SPLITS, DataExportFormat.CSV,
                dateFrom, dateTo);

        SplitOrder split = createSplitOrder("ORD-001", 100L, new BigDecimal("10"),
                SplitOrder.SplitStatus.SETTLED, dateFrom.plusDays(5));

        when(exportRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(exportRequestRepository.save(any(DataExportRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(splitOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(split));

        dataExportService.processExport(1L);

        assertEquals(DataExportRequest.Status.COMPLETED, request.getStatus());
        assertEquals(1, request.getRecordCount());
    }

    // ==================== processExport — RISK_EVENTS ====================

    @Test
    @DisplayName("processExport — RISK_EVENTS 类型 CSV 导出成功")
    void processExportRiskEventsCsvSuccess() {
        LocalDateTime dateFrom = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime dateTo = LocalDateTime.of(2026, 9, 30, 23, 59);

        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.RISK_EVENTS, DataExportFormat.CSV,
                dateFrom, dateTo);

        RiskEvent event = createRiskEvent("EVT-001", RiskEvent.EventType.PAYMENT_EVALUATION,
                MERCHANT_ID, "APPROVED", 10, dateFrom.plusDays(5));

        when(exportRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(exportRequestRepository.save(any(DataExportRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(riskEventRepository.findByMerchantIdOrderByOccurredAtDesc(MERCHANT_ID))
                .thenReturn(List.of(event));

        dataExportService.processExport(1L);

        assertEquals(DataExportRequest.Status.COMPLETED, request.getStatus());
        assertEquals(1, request.getRecordCount());
    }

    // ==================== processExport — WEBHOOK_DELIVERIES ====================

    @Test
    @DisplayName("processExport — WEBHOOK_DELIVERIES 类型 CSV 导出成功")
    void processExportWebhookDeliveriesCsvSuccess() {
        LocalDateTime dateFrom = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime dateTo = LocalDateTime.of(2026, 9, 30, 23, 59);

        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.WEBHOOK_DELIVERIES, DataExportFormat.CSV,
                dateFrom, dateTo);

        WebhookDeliveryRecord delivery = createWebhookDelivery("DEL-001", "PAY-001",
                MERCHANT_ID, "http://example.com/webhook");

        when(exportRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(exportRequestRepository.save(any(DataExportRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(webhookDeliveryRepository.findByMerchantId(eq(MERCHANT_ID), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(delivery)));

        dataExportService.processExport(1L);

        assertEquals(DataExportRequest.Status.COMPLETED, request.getStatus());
        assertEquals(1, request.getRecordCount());
    }

    // ==================== processExport — SETTLEMENTS ====================

    @Test
    @DisplayName("processExport — SETTLEMENTS 类型 CSV 导出成功")
    void processExportSettlementsCsvSuccess() {
        LocalDateTime dateFrom = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime dateTo = LocalDateTime.of(2026, 9, 30, 23, 59);

        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.SETTLEMENTS, DataExportFormat.CSV,
                dateFrom, dateTo);

        PaymentOrder order = createPaymentOrder("ORD-001", new BigDecimal("100"),
                PaymentOrder.OrderStatus.PAID, dateFrom.plusDays(5));
        order.setPaidAt(dateFrom.plusDays(5));

        when(exportRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(exportRequestRepository.save(any(DataExportRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentOrderRepository.findByMerchantIdAndStatus(MERCHANT_ID, PaymentOrder.OrderStatus.PAID))
                .thenReturn(List.of(order));

        dataExportService.processExport(1L);

        assertEquals(DataExportRequest.Status.COMPLETED, request.getStatus());
        assertEquals(1, request.getRecordCount());
    }

    // ==================== processExport — 失败场景 ====================

    @Test
    @DisplayName("processExport — 请求不存在时跳过处理")
    void processExportRequestNotFoundSkips() {
        when(exportRequestRepository.findById(999L)).thenReturn(Optional.empty());

        dataExportService.processExport(999L);

        verify(exportRequestRepository, never()).save(any(DataExportRequest.class));
    }

    @Test
    @DisplayName("processExport — 数据查询异常时标记 FAILED")
    void processExportFailureMarksFailed() {
        LocalDateTime dateFrom = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime dateTo = LocalDateTime.of(2026, 9, 30, 23, 59);

        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.TRANSACTIONS, DataExportFormat.CSV,
                dateFrom, dateTo);

        when(exportRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(exportRequestRepository.save(any(DataExportRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenThrow(new RuntimeException("数据库连接失败"));

        dataExportService.processExport(1L);

        assertEquals(DataExportRequest.Status.FAILED, request.getStatus());
        assertNotNull(request.getErrorMessage());
        assertTrue(request.getErrorMessage().contains("数据库连接失败"));
        assertNotNull(request.getCompletedAt());
    }

    // ==================== getExportRequest ====================

    @Test
    @DisplayName("getExportRequest — 按 ID 查询导出请求")
    void getExportRequestFindsById() {
        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.TRANSACTIONS, DataExportFormat.CSV,
                LocalDateTime.now(), LocalDateTime.now());

        when(exportRequestRepository.findById(1L)).thenReturn(Optional.of(request));

        Optional<DataExportRequest> found = dataExportService.getExportRequest(1L);

        assertTrue(found.isPresent());
        assertEquals(1L, found.get().getId());
    }

    @Test
    @DisplayName("getExportRequest — ID 不存在时返回空")
    void getExportRequestNotFoundReturnsEmpty() {
        when(exportRequestRepository.findById(999L)).thenReturn(Optional.empty());

        Optional<DataExportRequest> found = dataExportService.getExportRequest(999L);

        assertTrue(found.isEmpty());
    }

    // ==================== listExportRequests ====================

    @Test
    @DisplayName("listExportRequests — 返回商户的导出请求列表")
    void listExportRequestsReturnsMerchantRequests() {
        DataExportRequest r1 = createRequest(1L, MERCHANT_ID,
                DataExportType.TRANSACTIONS, DataExportFormat.CSV,
                LocalDateTime.now(), LocalDateTime.now());
        DataExportRequest r2 = createRequest(2L, MERCHANT_ID,
                DataExportType.REFUNDS, DataExportFormat.JSON,
                LocalDateTime.now(), LocalDateTime.now());

        when(exportRequestRepository.findByMerchantIdOrderByCreatedAtDesc(MERCHANT_ID))
                .thenReturn(List.of(r1, r2));

        List<DataExportRequest> requests = dataExportService.listExportRequests(MERCHANT_ID);

        assertEquals(2, requests.size());
        assertEquals(1L, requests.get(0).getId());
        assertEquals(2L, requests.get(1).getId());
    }

    // ==================== downloadExport ====================

    @Test
    @DisplayName("downloadExport — 成功下载已完成的导出文件")
    void downloadExportSuccess() {
        LocalDateTime dateFrom = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime dateTo = LocalDateTime.of(2026, 9, 30, 23, 59);

        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.TRANSACTIONS, DataExportFormat.CSV,
                dateFrom, dateTo);
        request.setStatus(DataExportRequest.Status.COMPLETED);

        PaymentOrder order = createPaymentOrder("ORD-001", new BigDecimal("100"),
                PaymentOrder.OrderStatus.PAID, dateFrom.plusDays(5));

        when(exportRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(exportRequestRepository.save(any(DataExportRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(order));

        // 先执行导出生成文件
        dataExportService.processExport(1L);

        // 然后下载
        Optional<byte[]> content = dataExportService.downloadExport(1L);

        assertTrue(content.isPresent());
        assertTrue(content.get().length > 0);
    }

    @Test
    @DisplayName("downloadExport — 请求不存在时返回空")
    void downloadExportNotFoundReturnsEmpty() {
        when(exportRequestRepository.findById(999L)).thenReturn(Optional.empty());

        Optional<byte[]> content = dataExportService.downloadExport(999L);

        assertTrue(content.isEmpty());
    }

    @Test
    @DisplayName("downloadExport — 未完成的请求返回空")
    void downloadExportNotCompletedReturnsEmpty() {
        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.TRANSACTIONS, DataExportFormat.CSV,
                LocalDateTime.now(), LocalDateTime.now());
        request.setStatus(DataExportRequest.Status.PENDING);

        when(exportRequestRepository.findById(1L)).thenReturn(Optional.of(request));

        Optional<byte[]> content = dataExportService.downloadExport(1L);

        assertTrue(content.isEmpty());
    }

    // ==================== deleteExport ====================

    @Test
    @DisplayName("deleteExport — 删除导出请求和文件")
    void deleteExportRemovesRequestAndFile() {
        LocalDateTime dateFrom = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime dateTo = LocalDateTime.of(2026, 9, 30, 23, 59);

        DataExportRequest request = createRequest(1L, MERCHANT_ID,
                DataExportType.TRANSACTIONS, DataExportFormat.CSV,
                dateFrom, dateTo);

        PaymentOrder order = createPaymentOrder("ORD-001", new BigDecimal("100"),
                PaymentOrder.OrderStatus.PAID, dateFrom.plusDays(5));

        when(exportRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(exportRequestRepository.save(any(DataExportRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(order));

        // 先执行导出生成文件
        dataExportService.processExport(1L);

        // 然后删除
        when(exportRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        boolean deleted = dataExportService.deleteExport(1L);

        assertTrue(deleted);
        verify(exportRequestRepository).delete(request);
    }

    @Test
    @DisplayName("deleteExport — 请求不存在时返回 false")
    void deleteExportNotFoundReturnsFalse() {
        when(exportRequestRepository.findById(999L)).thenReturn(Optional.empty());

        boolean deleted = dataExportService.deleteExport(999L);

        assertFalse(deleted);
    }

    // ==================== cleanupExpiredExports ====================

    @Test
    @DisplayName("cleanupExpiredExports — 清理过期文件并标记 EXPIRED")
    void cleanupExpiredExportsMarksExpired() {
        LocalDateTime oldCompletedAt = LocalDateTime.now().minusDays(10);

        DataExportRequest expiredRequest = createRequest(1L, MERCHANT_ID,
                DataExportType.TRANSACTIONS, DataExportFormat.CSV,
                LocalDateTime.now().minusDays(20), LocalDateTime.now().minusDays(10));
        expiredRequest.setStatus(DataExportRequest.Status.COMPLETED);
        expiredRequest.setCompletedAt(oldCompletedAt);

        when(exportRequestRepository.findByStatusAndCompletedAtBefore(
                eq(DataExportRequest.Status.COMPLETED), any(LocalDateTime.class)))
                .thenReturn(List.of(expiredRequest));
        when(exportRequestRepository.save(any(DataExportRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        dataExportService.cleanupExpiredExports();

        assertEquals(DataExportRequest.Status.EXPIRED, expiredRequest.getStatus());
        assertNotNull(expiredRequest.getExpiredAt());
    }

    @Test
    @DisplayName("cleanupExpiredExports — 无过期文件时不做任何操作")
    void cleanupExpiredExportsNoExpiredFiles() {
        when(exportRequestRepository.findByStatusAndCompletedAtBefore(
                eq(DataExportRequest.Status.COMPLETED), any(LocalDateTime.class)))
                .thenReturn(List.of());

        dataExportService.cleanupExpiredExports();

        verify(exportRequestRepository, never()).save(any(DataExportRequest.class));
    }

    // ==================== 辅助方法 ====================

    private DataExportRequest createRequest(Long id, Long merchantId,
                                             DataExportType exportType,
                                             DataExportFormat format,
                                             LocalDateTime dateFrom,
                                             LocalDateTime dateTo) {
        DataExportRequest request = new DataExportRequest();
        request.setId(id);
        request.setMerchantId(merchantId);
        request.setExportType(exportType);
        request.setFormat(format);
        request.setDateFrom(dateFrom);
        request.setDateTo(dateTo);
        request.setStatus(DataExportRequest.Status.PENDING);
        return request;
    }

    private PaymentOrder createPaymentOrder(String orderNo, BigDecimal amount,
                                             PaymentOrder.OrderStatus status,
                                             LocalDateTime createdAt) {
        PaymentOrder order = new PaymentOrder();
        order.setOrderNo(orderNo);
        order.setMerchantId(MERCHANT_ID);
        order.setAmount(amount);
        order.setStatus(status);
        order.setCreatedAt(createdAt);
        order.setTokenSymbol("NEX");
        return order;
    }

    private Refund createRefund(String refundNo, Long orderId, BigDecimal amount,
                                 Refund.RefundStatus status, LocalDateTime createdAt) {
        Refund refund = new Refund();
        refund.setRefundNo(refundNo);
        refund.setOrderId(orderId);
        refund.setMerchantId(MERCHANT_ID);
        refund.setAmount(amount);
        refund.setStatus(status);
        refund.setCreatedAt(createdAt);
        refund.setTokenSymbol("NEX");
        return refund;
    }

    private SplitOrder createSplitOrder(String orderId, Long paymentId, BigDecimal amount,
                                         SplitOrder.SplitStatus status,
                                         LocalDateTime createdAt) {
        SplitOrder split = new SplitOrder();
        split.setOrderId(orderId);
        split.setPaymentId(paymentId);
        split.setMerchantId(MERCHANT_ID);
        split.setAmount(amount);
        split.setStatus(status);
        split.setCreatedAt(createdAt);
        split.setReceiverAddress("0xReceiver123");
        return split;
    }

    private RiskEvent createRiskEvent(String eventId, RiskEvent.EventType eventType,
                                       Long merchantId, String riskDecision,
                                       Integer riskScore, LocalDateTime occurredAt) {
        RiskEvent event = new RiskEvent();
        event.setEventId(eventId);
        event.setEventType(eventType);
        event.setMerchantId(merchantId);
        event.setRiskDecision(riskDecision);
        event.setRiskScore(riskScore);
        event.setOccurredAt(occurredAt);
        return event;
    }

    private WebhookDeliveryRecord createWebhookDelivery(String deliveryId, String paymentId,
                                                         Long merchantId, String notifyUrl) {
        WebhookDeliveryRecord delivery = new WebhookDeliveryRecord();
        delivery.setDeliveryId(deliveryId);
        delivery.setPaymentId(paymentId);
        delivery.setMerchantId(merchantId);
        delivery.setNotifyUrl(notifyUrl);
        delivery.setPayload("{}");
        delivery.setAttemptCount(1);
        delivery.setCreatedAt(java.time.Instant.now());
        return delivery;
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("设置字段失败: " + fieldName, e);
        }
    }
}