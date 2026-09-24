package org.nexus.gateway.reconciliation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ReconciliationFileService 单元测试 — 使用 Mockito mock repositories。
 *
 * <p>覆盖日度/月度对账文件生成、CSV/JSON 内容构建、文件历史查询、
 * 文件内容重新生成等核心场景。</p>
 */
class ReconciliationFileServiceTest {

    private PaymentOrderRepository paymentOrderRepository;
    private ReconciliationFileRecordRepository fileRecordRepository;
    private ReconciliationEngine reconciliationEngine;
    private DiscrepancyResolutionService discrepancyResolutionService;
    private ReconciliationFileService reconciliationFileService;

    private static final Long MERCHANT_ID = 500L;

    @BeforeEach
    void setUp() {
        paymentOrderRepository = mock(PaymentOrderRepository.class);
        fileRecordRepository = mock(ReconciliationFileRecordRepository.class);
        reconciliationEngine = mock(ReconciliationEngine.class);
        discrepancyResolutionService = mock(DiscrepancyResolutionService.class);
        reconciliationFileService = new ReconciliationFileService(
                paymentOrderRepository, fileRecordRepository,
                reconciliationEngine, discrepancyResolutionService);
    }

    // ==================== generateDailyFile ====================

    @Test
    @DisplayName("generateDailyFile — 有交易时生成正确的文件记录")
    void generateDailyFileWithTransactionsCreatesCorrectRecord() {
        LocalDate date = LocalDate.of(2026, 9, 23);

        PaymentOrder order1 = createPaymentOrder("ORD-001", new BigDecimal("100"),
                PaymentOrder.OrderStatus.PAID, date.atTime(10, 0));
        PaymentOrder order2 = createPaymentOrder("ORD-002", new BigDecimal("200"),
                PaymentOrder.OrderStatus.PENDING, date.atTime(14, 0));

        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(order1, order2));
        when(fileRecordRepository.save(any(ReconciliationFileRecord.class)))
                .thenAnswer(inv -> {
                    ReconciliationFileRecord r = inv.getArgument(0);
                    r.setId(1L);
                    return r;
                });

        ReconciliationFileRecord record = reconciliationFileService.generateDailyFile(MERCHANT_ID, date);

        assertNotNull(record);
        assertEquals(MERCHANT_ID, record.getMerchantId());
        assertEquals("CSV", record.getFileType());
        assertEquals("DAILY", record.getPeriodType());
        assertEquals(date, record.getPeriodStart());
        assertEquals(date, record.getPeriodEnd());
        assertEquals(2, record.getTotalTransactions());
        assertEquals(new BigDecimal("300"), record.getTotalAmount());
        assertEquals(2, record.getMatchedCount());
        assertEquals(0, record.getDiscrepancyCount());
        assertNotNull(record.getFileUrl());
        assertTrue(record.getFileUrl().contains("/api/v1/reconciliation/download/1"));
        assertTrue(record.getFileSizeBytes() > 0);
        assertNotNull(record.getGeneratedAt());
    }

    @Test
    @DisplayName("generateDailyFile — 无交易时生成空文件记录")
    void generateDailyFileWithNoTransactionsCreatesEmptyRecord() {
        LocalDate date = LocalDate.of(2026, 9, 23);

        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of());
        when(fileRecordRepository.save(any(ReconciliationFileRecord.class)))
                .thenAnswer(inv -> {
                    ReconciliationFileRecord r = inv.getArgument(0);
                    r.setId(1L);
                    return r;
                });

        ReconciliationFileRecord record = reconciliationFileService.generateDailyFile(MERCHANT_ID, date);

        assertNotNull(record);
        assertEquals(0, record.getTotalTransactions());
        assertEquals(BigDecimal.ZERO, record.getTotalAmount());
        assertEquals(0, record.getMatchedCount());
        assertEquals(0, record.getDiscrepancyCount());
    }

    @Test
    @DisplayName("generateDailyFile — CSV 内容包含正确的头部注释")
    void generateDailyFileCsvContainsCorrectHeaderComments() {
        LocalDate date = LocalDate.of(2026, 9, 23);

        PaymentOrder order = createPaymentOrder("ORD-001", new BigDecimal("100"),
                PaymentOrder.OrderStatus.PAID, date.atTime(10, 0));

        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(order));

        String csv = reconciliationFileService.generateCsvContent(
                MERCHANT_ID, List.of(order), date, date);

        assertTrue(csv.contains("# NexusChain Daily Reconciliation File"));
        assertTrue(csv.contains("# Merchant: " + MERCHANT_ID));
        assertTrue(csv.contains("# Period: " + date));
        assertTrue(csv.contains("# Generated:"));
    }

    @Test
    @DisplayName("generateDailyFile — CSV 内容包含正确的列标题和数据行")
    void generateDailyFileCsvContainsCorrectColumnHeadersAndDataRows() {
        LocalDate date = LocalDate.of(2026, 9, 23);

        PaymentOrder order = createPaymentOrder("ORD-001", new BigDecimal("100"),
                PaymentOrder.OrderStatus.PAID, date.atTime(10, 0));
        order.setTokenSymbol("NEX");
        order.setChainTxHash("0xTxHash123");

        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(order));

        String csv = reconciliationFileService.generateCsvContent(
                MERCHANT_ID, List.of(order), date, date);

        // 列标题
        assertTrue(csv.contains("order_no,amount,currency,status,connector_id,created_at,paid_at"));

        // 数据行
        assertTrue(csv.contains("ORD-001"));
        assertTrue(csv.contains("100"));
        assertTrue(csv.contains("NEX"));
        assertTrue(csv.contains("PAID"));
        assertTrue(csv.contains("0xTxHash123"));
    }

    @Test
    @DisplayName("generateDailyFile — CSV 内容包含正确的尾部汇总")
    void generateDailyFileCsvContainsCorrectSummaryFooter() {
        LocalDate date = LocalDate.of(2026, 9, 23);

        PaymentOrder order1 = createPaymentOrder("ORD-001", new BigDecimal("100"),
                PaymentOrder.OrderStatus.PAID, date.atTime(10, 0));
        PaymentOrder order2 = createPaymentOrder("ORD-002", new BigDecimal("200"),
                PaymentOrder.OrderStatus.PENDING, date.atTime(14, 0));

        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(order1, order2));

        String csv = reconciliationFileService.generateCsvContent(
                MERCHANT_ID, List.of(order1, order2), date, date);

        assertTrue(csv.contains("# Summary:"));
        assertTrue(csv.contains("total_transactions=2"));
        assertTrue(csv.contains("total_amount=300"));
        assertTrue(csv.contains("matched=2"));
        assertTrue(csv.contains("discrepancies=0"));
    }

    // ==================== generateMonthlyFile ====================

    @Test
    @DisplayName("generateMonthlyFile — 生成月度文件记录")
    void generateMonthlyFileCreatesCorrectRecord() {
        int year = 2026;
        int month = 9;
        LocalDate periodStart = LocalDate.of(year, month, 1);
        LocalDate periodEnd = LocalDate.of(year, month, 30);

        PaymentOrder order1 = createPaymentOrder("ORD-001", new BigDecimal("500"),
                PaymentOrder.OrderStatus.PAID, periodStart.atTime(10, 0));
        PaymentOrder order2 = createPaymentOrder("ORD-002", new BigDecimal("300"),
                PaymentOrder.OrderStatus.PAID, periodEnd.atTime(10, 0));

        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(order1, order2));
        when(fileRecordRepository.save(any(ReconciliationFileRecord.class)))
                .thenAnswer(inv -> {
                    ReconciliationFileRecord r = inv.getArgument(0);
                    r.setId(1L);
                    return r;
                });

        ReconciliationFileRecord record = reconciliationFileService.generateMonthlyFile(
                MERCHANT_ID, year, month);

        assertNotNull(record);
        assertEquals("MONTHLY", record.getPeriodType());
        assertEquals(periodStart, record.getPeriodStart());
        assertEquals(periodEnd, record.getPeriodEnd());
        assertEquals(2, record.getTotalTransactions());
        assertEquals(new BigDecimal("800"), record.getTotalAmount());
    }

    // ==================== generateJsonContent ====================

    @Test
    @DisplayName("generateJsonContent — JSON 格式包含 metadata 和 transactions")
    void generateJsonContentContainsMetadataAndTransactions() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);

        PaymentOrder order = createPaymentOrder("ORD-001", new BigDecimal("100"),
                PaymentOrder.OrderStatus.PAID, start.atTime(10, 0));
        order.setTokenSymbol("NEX");

        String json = reconciliationFileService.generateJsonContent(
                MERCHANT_ID, List.of(order), start, end);

        assertTrue(json.contains("\"metadata\""));
        assertTrue(json.contains("\"merchantId\": " + MERCHANT_ID));
        assertTrue(json.contains("\"periodStart\": \"" + start + "\""));
        assertTrue(json.contains("\"periodEnd\": \"" + end + "\""));
        assertTrue(json.contains("\"transactions\""));
        assertTrue(json.contains("\"orderNo\": \"ORD-001\""));
        assertTrue(json.contains("\"amount\": \"100\""));
        assertTrue(json.contains("\"currency\": \"NEX\""));
        assertTrue(json.contains("\"status\": \"PAID\""));
        assertTrue(json.contains("\"summary\""));
        assertTrue(json.contains("\"totalTransactions\": 1"));
    }

    // ==================== generateCsvContent ====================

    @Test
    @DisplayName("generateCsvContent — 空交易列表时仍有头部和尾部")
    void generateCsvContentWithEmptyOrdersStillHasHeaderAndFooter() {
        LocalDate start = LocalDate.of(2026, 9, 23);
        LocalDate end = LocalDate.of(2026, 9, 23);

        String csv = reconciliationFileService.generateCsvContent(
                MERCHANT_ID, List.of(), start, end);

        // 头部注释存在
        assertTrue(csv.contains("# NexusChain Daily Reconciliation File"));
        assertTrue(csv.contains("# Merchant: " + MERCHANT_ID));
        assertTrue(csv.contains("# Period: " + start));

        // 列标题存在
        assertTrue(csv.contains("order_no,amount,currency,status,connector_id,created_at,paid_at"));

        // 尾部汇总存在
        assertTrue(csv.contains("# Summary:"));
        assertTrue(csv.contains("total_transactions=0"));
        assertTrue(csv.contains("total_amount=0"));
        assertTrue(csv.contains("matched=0"));
        assertTrue(csv.contains("discrepancies=0"));
    }

    // ==================== getFileHistory ====================

    @Test
    @DisplayName("getFileHistory — 返回商户的文件历史")
    void getFileHistoryReturnsMerchantFileHistory() {
        ReconciliationFileRecord record1 = new ReconciliationFileRecord();
        record1.setId(1L);
        record1.setMerchantId(MERCHANT_ID);
        record1.setGeneratedAt(LocalDateTime.of(2026, 9, 23, 10, 0));

        ReconciliationFileRecord record2 = new ReconciliationFileRecord();
        record2.setId(2L);
        record2.setMerchantId(MERCHANT_ID);
        record2.setGeneratedAt(LocalDateTime.of(2026, 9, 22, 10, 0));

        when(fileRecordRepository.findByMerchantIdOrderByGeneratedAtDesc(MERCHANT_ID))
                .thenReturn(List.of(record1, record2));

        List<ReconciliationFileRecord> history = reconciliationFileService.getFileHistory(MERCHANT_ID);

        assertEquals(2, history.size());
        assertEquals(1L, history.get(0).getId());
        assertEquals(2L, history.get(1).getId());
    }

    // ==================== getFileRecord ====================

    @Test
    @DisplayName("getFileRecord — 按 ID 查找文件记录")
    void getFileRecordFindsRecordById() {
        ReconciliationFileRecord record = new ReconciliationFileRecord();
        record.setId(1L);
        record.setMerchantId(MERCHANT_ID);
        record.setFileType("CSV");
        record.setPeriodType("DAILY");

        when(fileRecordRepository.findById(1L)).thenReturn(Optional.of(record));

        Optional<ReconciliationFileRecord> found = reconciliationFileService.getFileRecord(1L);

        assertTrue(found.isPresent());
        assertEquals(1L, found.get().getId());
        assertEquals("CSV", found.get().getFileType());
    }

    // ==================== getFileContent ====================

    @Test
    @DisplayName("getFileContent — 重新生成 CSV 内容")
    void getFileContentRegeneratesCsvContent() {
        LocalDate date = LocalDate.of(2026, 9, 23);

        ReconciliationFileRecord record = new ReconciliationFileRecord();
        record.setId(1L);
        record.setMerchantId(MERCHANT_ID);
        record.setFileType("CSV");
        record.setPeriodType("DAILY");
        record.setPeriodStart(date);
        record.setPeriodEnd(date);

        PaymentOrder order = createPaymentOrder("ORD-001", new BigDecimal("100"),
                PaymentOrder.OrderStatus.PAID, date.atTime(10, 0));

        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(order));

        String content = reconciliationFileService.getFileContent(record);

        assertNotNull(content);
        assertTrue(content.contains("# NexusChain Daily Reconciliation File"));
        assertTrue(content.contains("ORD-001"));
        assertTrue(content.contains("order_no,amount,currency,status,connector_id,created_at,paid_at"));
    }

    @Test
    @DisplayName("getFileContent — 重新生成 JSON 内容")
    void getFileContentRegeneratesJsonContent() {
        LocalDate date = LocalDate.of(2026, 9, 23);

        ReconciliationFileRecord record = new ReconciliationFileRecord();
        record.setId(1L);
        record.setMerchantId(MERCHANT_ID);
        record.setFileType("JSON");
        record.setPeriodType("DAILY");
        record.setPeriodStart(date);
        record.setPeriodEnd(date);

        PaymentOrder order = createPaymentOrder("ORD-001", new BigDecimal("100"),
                PaymentOrder.OrderStatus.PAID, date.atTime(10, 0));

        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(order));

        String content = reconciliationFileService.getFileContent(record);

        assertNotNull(content);
        assertTrue(content.contains("\"metadata\""));
        assertTrue(content.contains("\"transactions\""));
        assertTrue(content.contains("\"summary\""));
        assertTrue(content.contains("\"orderNo\": \"ORD-001\""));
    }

    // ==================== 辅助方法 ====================

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
}