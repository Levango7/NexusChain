package org.nexus.gateway.reconciliation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.model.PaymentOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReconciliationEngine 单元测试。
 *
 * <p>覆盖对账引擎的核心比对逻辑：精确匹配、金额容差、长款/短款发现、
 * 金额不一致/状态不一致分类、CSV 解析等场景。</p>
 */
class ReconciliationEngineTest {

    private ReconciliationEngine engine;

    private static final Long MERCHANT_ID = 500L;

    @BeforeEach
    void setUp() {
        engine = new ReconciliationEngine();
        engine.setAmountTolerance(new BigDecimal("0.01"));
    }

    // ==================== 全匹配场景 ====================

    @Test
    @DisplayName("reconcile — 双方完全匹配时返回零差异")
    void reconcileAllMatchedReturnsZeroDiscrepancies() {
        ChannelRecord channelRecord = createChannelRecord("ORD-001", new BigDecimal("100"), "PAID");
        PaymentOrder internalOrder = createPaymentOrder("ORD-001", new BigDecimal("100"),
                PaymentOrder.OrderStatus.PAID);

        ReconciliationDiffReport report = engine.reconcile(
                MERCHANT_ID, List.of(channelRecord), List.of(internalOrder), null);

        assertEquals(1, report.getMatchedCount());
        assertEquals(0, report.getMismatchedCount());
        assertEquals(0, report.getMissingCount());
        assertEquals(0, report.getExtraCount());
        assertTrue(report.getDiscrepancies().isEmpty());
        assertEquals(1, report.getTotalInternal());
        assertEquals(1, report.getTotalChannel());
    }

    // ==================== 长款场景（渠道有内部无） ====================

    @Test
    @DisplayName("reconcile — 渠道有记录但内部无 → 长款 LONG_AMOUNT")
    void reconcileChannelOnlyRecordIsLongAmount() {
        ChannelRecord channelRecord = createChannelRecord("ORD-001", new BigDecimal("100"), "PAID");

        ReconciliationDiffReport report = engine.reconcile(
                MERCHANT_ID, List.of(channelRecord), List.of(), null);

        assertEquals(0, report.getMatchedCount());
        assertEquals(1, report.getExtraCount());
        assertEquals(0, report.getMissingCount());
        assertEquals(1, report.getDiscrepancies().size());

        ReconciliationDiscrepancy discrepancy = report.getDiscrepancies().get(0);
        assertEquals(ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT, discrepancy.getDiscrepancyType());
        assertEquals("ORD-001", discrepancy.getTransactionId());
        assertEquals(new BigDecimal("100"), discrepancy.getChannelAmount());
        assertNull(discrepancy.getInternalAmount());
        assertEquals(ReconciliationDiscrepancy.ResolutionType.PENDING_INVESTIGATION,
                discrepancy.getResolutionType());
        assertEquals(ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED, discrepancy.getStatus());
    }

    // ==================== 短款场景（内部有渠道无） ====================

    @Test
    @DisplayName("reconcile — 内部有记录但渠道无 → 短款 SHORT_AMOUNT")
    void reconcileInternalOnlyRecordIsShortAmount() {
        PaymentOrder internalOrder = createPaymentOrder("ORD-001", new BigDecimal("100"),
                PaymentOrder.OrderStatus.PAID);

        ReconciliationDiffReport report = engine.reconcile(
                MERCHANT_ID, List.of(), List.of(internalOrder), null);

        assertEquals(0, report.getMatchedCount());
        assertEquals(0, report.getExtraCount());
        assertEquals(1, report.getMissingCount());
        assertEquals(1, report.getDiscrepancies().size());

        ReconciliationDiscrepancy discrepancy = report.getDiscrepancies().get(0);
        assertEquals(ReconciliationDiscrepancy.DiscrepancyType.SHORT_AMOUNT, discrepancy.getDiscrepancyType());
        assertEquals("ORD-001", discrepancy.getTransactionId());
        assertEquals(new BigDecimal("100"), discrepancy.getInternalAmount());
        assertNull(discrepancy.getChannelAmount());
        assertEquals(ReconciliationDiscrepancy.ResolutionType.PENDING_INVESTIGATION,
                discrepancy.getResolutionType());
    }

    // ==================== 金额不一致场景 ====================

    @Test
    @DisplayName("reconcile — 金额超出容差 → AMOUNT_MISMATCH")
    void reconcileAmountBeyondToleranceIsMismatch() {
        ChannelRecord channelRecord = createChannelRecord("ORD-001", new BigDecimal("100"), "PAID");
        PaymentOrder internalOrder = createPaymentOrder("ORD-001", new BigDecimal("105"),
                PaymentOrder.OrderStatus.PAID);

        ReconciliationDiffReport report = engine.reconcile(
                MERCHANT_ID, List.of(channelRecord), List.of(internalOrder), null);

        assertEquals(0, report.getMatchedCount());
        assertEquals(1, report.getMismatchedCount());
        assertEquals(1, report.getDiscrepancies().size());

        ReconciliationDiscrepancy discrepancy = report.getDiscrepancies().get(0);
        assertEquals(ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH, discrepancy.getDiscrepancyType());
        assertEquals(new BigDecimal("100"), discrepancy.getChannelAmount());
        assertEquals(new BigDecimal("105"), discrepancy.getInternalAmount());
        assertEquals(new BigDecimal("5"), discrepancy.getAmountDiff());
        assertEquals(ReconciliationDiscrepancy.ResolutionType.MANUAL_REVIEW,
                discrepancy.getResolutionType());
    }

    @Test
    @DisplayName("reconcile — 金额在容差范围内 → 视为匹配")
    void reconcileAmountWithinToleranceIsMatched() {
        ChannelRecord channelRecord = createChannelRecord("ORD-001", new BigDecimal("100.00"), "PAID");
        PaymentOrder internalOrder = createPaymentOrder("ORD-001", new BigDecimal("100.01"),
                PaymentOrder.OrderStatus.PAID);

        ReconciliationDiffReport report = engine.reconcile(
                MERCHANT_ID, List.of(channelRecord), List.of(internalOrder), null);

        assertEquals(1, report.getMatchedCount());
        assertEquals(0, report.getMismatchedCount());
        assertTrue(report.getDiscrepancies().isEmpty());
    }

    // ==================== 状态不一致场景 ====================

    @Test
    @DisplayName("reconcile — 状态不同但金额一致 → STATUS_MISMATCH")
    void reconcileStatusDifferentIsStatusMismatch() {
        ChannelRecord channelRecord = createChannelRecord("ORD-001", new BigDecimal("100"), "PAID");
        PaymentOrder internalOrder = createPaymentOrder("ORD-001", new BigDecimal("100"),
                PaymentOrder.OrderStatus.PENDING);

        ReconciliationDiffReport report = engine.reconcile(
                MERCHANT_ID, List.of(channelRecord), List.of(internalOrder), null);

        assertEquals(0, report.getMatchedCount());
        assertEquals(1, report.getMismatchedCount());
        assertEquals(1, report.getDiscrepancies().size());

        ReconciliationDiscrepancy discrepancy = report.getDiscrepancies().get(0);
        assertEquals(ReconciliationDiscrepancy.DiscrepancyType.STATUS_MISMATCH, discrepancy.getDiscrepancyType());
        assertEquals("PAID", discrepancy.getChannelStatus());
        assertEquals("PENDING", discrepancy.getInternalStatus());
        assertEquals(ReconciliationDiscrepancy.ResolutionType.MANUAL_REVIEW,
                discrepancy.getResolutionType());
    }

    // ==================== 混合场景 ====================

    @Test
    @DisplayName("reconcile — 混合场景：1匹配 + 1长款 + 1短款 + 1金额不一致")
    void reconcileMixedScenario() {
        // 匹配的交易
        ChannelRecord chMatched = createChannelRecord("ORD-001", new BigDecimal("100"), "PAID");
        PaymentOrder intMatched = createPaymentOrder("ORD-001", new BigDecimal("100"),
                PaymentOrder.OrderStatus.PAID);

        // 长款：渠道有内部无
        ChannelRecord chLong = createChannelRecord("ORD-002", new BigDecimal("200"), "PAID");

        // 短款：内部有渠道无
        PaymentOrder intShort = createPaymentOrder("ORD-003", new BigDecimal("300"),
                PaymentOrder.OrderStatus.PAID);

        // 金额不一致
        ChannelRecord chMismatch = createChannelRecord("ORD-004", new BigDecimal("400"), "PAID");
        PaymentOrder intMismatch = createPaymentOrder("ORD-004", new BigDecimal("450"),
                PaymentOrder.OrderStatus.PAID);

        ReconciliationDiffReport report = engine.reconcile(
                MERCHANT_ID,
                List.of(chMatched, chLong, chMismatch),
                List.of(intMatched, intShort, intMismatch),
                null);

        assertEquals(1, report.getMatchedCount());
        assertEquals(1, report.getMismatchedCount());
        assertEquals(1, report.getMissingCount());
        assertEquals(1, report.getExtraCount());
        assertEquals(3, report.getDiscrepancies().size());
        assertEquals(3, report.getTotalInternal());
        assertEquals(3, report.getTotalChannel());
    }

    // ==================== 空输入场景 ====================

    @Test
    @DisplayName("reconcile — 双方都为空时返回零差异")
    void reconcileBothEmptyReturnsZero() {
        ReconciliationDiffReport report = engine.reconcile(
                MERCHANT_ID, List.of(), List.of(), null);

        assertEquals(0, report.getMatchedCount());
        assertEquals(0, report.getMismatchedCount());
        assertEquals(0, report.getMissingCount());
        assertEquals(0, report.getExtraCount());
        assertTrue(report.getDiscrepancies().isEmpty());
        assertEquals(0, report.getTotalInternal());
        assertEquals(0, report.getTotalChannel());
    }

    @Test
    @DisplayName("reconcile — null 输入安全处理")
    void reconcileNullInputsHandledSafely() {
        ReconciliationDiffReport report = engine.reconcile(
                MERCHANT_ID, null, null, null);

        assertEquals(0, report.getMatchedCount());
        assertTrue(report.getDiscrepancies().isEmpty());
    }

    // ==================== CSV 解析 ====================

    @Test
    @DisplayName("parseCsvContent — 正确解析 CSV 格式对账文件")
    void parseCsvContentParsesValidCsv() {
        String csv = "# NexusChain Daily Reconciliation File\n"
                + "# Merchant: 500\n"
                + "# Period: 2026-09-23\n"
                + "order_no,amount,currency,status,connector_id,created_at,paid_at\n"
                + "ORD-001,100,NEX,PAID,0xTx1,2026-09-23T10:00,2026-09-23T10:05\n"
                + "ORD-002,200,NEX,PENDING,,2026-09-23T14:00,\n";

        List<ChannelRecord> records = engine.parseCsvContent(csv);

        assertEquals(2, records.size());
        assertEquals("ORD-001", records.get(0).getTransactionId());
        assertEquals(new BigDecimal("100"), records.get(0).getAmount());
        assertEquals("PAID", records.get(0).getStatus());
        assertEquals("NEX", records.get(0).getCurrency());
        assertEquals("ORD-002", records.get(1).getTransactionId());
        assertEquals(new BigDecimal("200"), records.get(1).getAmount());
        assertEquals("PENDING", records.get(1).getStatus());
    }

    @Test
    @DisplayName("parseCsvContent — 空内容返回空列表")
    void parseCsvContentEmptyReturnsEmptyList() {
        List<ChannelRecord> records = engine.parseCsvContent("");
        assertTrue(records.isEmpty());

        List<ChannelRecord> records2 = engine.parseCsvContent(null);
        assertTrue(records2.isEmpty());
    }

    @Test
    @DisplayName("parseCsvContent — 只有注释和标题行时返回空列表")
    void parseCsvContentOnlyCommentsAndHeaderReturnsEmpty() {
        String csv = "# NexusChain Daily Reconciliation File\n"
                + "# Merchant: 500\n"
                + "order_no,amount,currency,status,connector_id,created_at,paid_at\n";

        List<ChannelRecord> records = engine.parseCsvContent(csv);
        assertTrue(records.isEmpty());
    }

    // ==================== 差异金额汇总 ====================

    @Test
    @DisplayName("reconcile — 差异金额汇总正确计算")
    void reconcileTotalDiscrepancyAmountCalculatedCorrectly() {
        // 长款 100
        ChannelRecord chLong = createChannelRecord("ORD-001", new BigDecimal("100"), "PAID");
        // 短款 200
        PaymentOrder intShort = createPaymentOrder("ORD-002", new BigDecimal("200"),
                PaymentOrder.OrderStatus.PAID);
        // 金额不一致，差异 5
        ChannelRecord chMismatch = createChannelRecord("ORD-003", new BigDecimal("300"), "PAID");
        PaymentOrder intMismatch = createPaymentOrder("ORD-003", new BigDecimal("305"),
                PaymentOrder.OrderStatus.PAID);

        ReconciliationDiffReport report = engine.reconcile(
                MERCHANT_ID,
                List.of(chLong, chMismatch),
                List.of(intShort, intMismatch),
                null);

        // 长款 100 + 短款 200 + 金额差异 5 = 305
        assertEquals(new BigDecimal("305"), report.getTotalDiscrepancyAmount());
    }

    // ==================== 辅助方法 ====================

    private ChannelRecord createChannelRecord(String transactionId, BigDecimal amount, String status) {
        ChannelRecord record = new ChannelRecord();
        record.setTransactionId(transactionId);
        record.setAmount(amount);
        record.setStatus(status);
        record.setCurrency("NEX");
        return record;
    }

    private PaymentOrder createPaymentOrder(String orderNo, BigDecimal amount,
                                             PaymentOrder.OrderStatus status) {
        PaymentOrder order = new PaymentOrder();
        order.setOrderNo(orderNo);
        order.setMerchantId(MERCHANT_ID);
        order.setAmount(amount);
        order.setStatus(status);
        order.setTokenSymbol("NEX");
        order.setCreatedAt(LocalDateTime.of(2026, 9, 23, 10, 0));
        return order;
    }
}