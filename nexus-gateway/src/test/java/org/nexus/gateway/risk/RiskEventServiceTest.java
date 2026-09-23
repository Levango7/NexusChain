package org.nexus.gateway.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link RiskEventService} 单元测试 — 使用 Mockito mock RiskEventRepository。
 *
 * <p>覆盖事件创建、便捷方法委托、查询方法和统计方法等场景。</p>
 */
@ExtendWith(MockitoExtension.class)
class RiskEventServiceTest {

    @Mock private RiskEventRepository riskEventRepository;

    private RiskEventService riskEventService;

    private static final Long MERCHANT_ID = 500L;
    private static final String ORDER_ID = "ORD-20250923-001";
    private static final String PAYER_ADDRESS = "0xPayer1234567890abcdef1234567890abcdef1234567890abcdef1234";

    @BeforeEach
    void setUp() {
        riskEventService = new RiskEventService(riskEventRepository);
    }

    // ==================== recordEvent ====================

    @Test
    @DisplayName("recordEvent：创建事件 → eventId 非空, occurredAt 非空")
    void recordEvent_eventIdAndOccurredAtNotNull() {
        when(riskEventRepository.save(any(RiskEvent.class))).thenAnswer(inv -> {
            RiskEvent event = inv.getArgument(0);
            event.setId(1L);
            return event;
        });

        RiskEvent event = riskEventService.recordEvent(
                RiskEvent.EventType.PAYMENT_EVALUATION, MERCHANT_ID, ORDER_ID,
                PAYER_ADDRESS, null, new BigDecimal("100"), "NEX",
                "APPROVED", 10, "rule1", "支付评估通过", null, "192.168.1.1");

        assertNotNull(event.getEventId());
        assertFalse(event.getEventId().isBlank());
        assertEquals(32, event.getEventId().length()); // UUID 去横线后 32 字符
        assertNotNull(event.getOccurredAt());
    }

    @Test
    @DisplayName("recordEvent：所有字段正确保存")
    void recordEvent_allFieldsSaved() {
        when(riskEventRepository.save(any(RiskEvent.class))).thenAnswer(inv -> {
            RiskEvent event = inv.getArgument(0);
            event.setId(1L);
            return event;
        });

        RiskEvent event = riskEventService.recordEvent(
                RiskEvent.EventType.PAYMENT_EVALUATION, MERCHANT_ID, ORDER_ID,
                PAYER_ADDRESS, "0xPayee", new BigDecimal("100"), "NEX",
                "APPROVED", 10, "rule1,rule2", "支付评估通过",
                "fp_hash_123", "192.168.1.1");

        assertEquals(RiskEvent.EventType.PAYMENT_EVALUATION, event.getEventType());
        assertEquals(MERCHANT_ID, event.getMerchantId());
        assertEquals(ORDER_ID, event.getOrderId());
        assertEquals(PAYER_ADDRESS, event.getPayerAddress());
        assertEquals("0xPayee", event.getPayeeAddress());
        assertEquals(new BigDecimal("100"), event.getAmount());
        assertEquals("NEX", event.getCurrency());
        assertEquals("APPROVED", event.getRiskDecision());
        assertEquals(10, event.getRiskScore());
        assertEquals("rule1,rule2", event.getTriggeredRules());
        assertEquals("支付评估通过", event.getDescription());
        assertEquals("fp_hash_123", event.getFingerprintHash());
        assertEquals("192.168.1.1", event.getIpAddress());
    }

    // ==================== recordPaymentEvaluation ====================

    @Test
    @DisplayName("recordPaymentEvaluation：eventType = PAYMENT_EVALUATION")
    void recordPaymentEvaluation_correctEventType() {
        when(riskEventRepository.save(any(RiskEvent.class))).thenAnswer(inv -> {
            RiskEvent event = inv.getArgument(0);
            event.setId(1L);
            return event;
        });

        RiskEvent event = riskEventService.recordPaymentEvaluation(
                MERCHANT_ID, ORDER_ID, PAYER_ADDRESS,
                new BigDecimal("100"), "NEX", "APPROVED", 10, "rule1");

        assertEquals(RiskEvent.EventType.PAYMENT_EVALUATION, event.getEventType());
        assertEquals(MERCHANT_ID, event.getMerchantId());
        assertEquals(ORDER_ID, event.getOrderId());
        assertEquals(PAYER_ADDRESS, event.getPayerAddress());
        assertEquals(new BigDecimal("100"), event.getAmount());
        assertEquals("NEX", event.getCurrency());
        assertEquals("APPROVED", event.getRiskDecision());
        assertEquals(10, event.getRiskScore());
        assertEquals("rule1", event.getTriggeredRules());
        assertNull(event.getPayeeAddress()); // 支付评估不设置 payeeAddress
    }

    // ==================== recordRefundEvaluation ====================

    @Test
    @DisplayName("recordRefundEvaluation：eventType = REFUND_EVALUATION")
    void recordRefundEvaluation_correctEventType() {
        when(riskEventRepository.save(any(RiskEvent.class))).thenAnswer(inv -> {
            RiskEvent event = inv.getArgument(0);
            event.setId(1L);
            return event;
        });

        String receiverAddress = "0xReceiver1234567890abcdef1234567890abcdef1234567890abcdef12";
        RiskEvent event = riskEventService.recordRefundEvaluation(
                MERCHANT_ID, ORDER_ID, receiverAddress,
                new BigDecimal("50"), "REJECTED", 80, "rule2");

        assertEquals(RiskEvent.EventType.REFUND_EVALUATION, event.getEventType());
        assertEquals(MERCHANT_ID, event.getMerchantId());
        assertEquals(ORDER_ID, event.getOrderId());
        assertEquals(receiverAddress, event.getPayeeAddress());
        assertEquals(new BigDecimal("50"), event.getAmount());
        assertEquals("REJECTED", event.getRiskDecision());
        assertEquals(80, event.getRiskScore());
        assertEquals("rule2", event.getTriggeredRules());
        assertNull(event.getPayerAddress()); // 退款评估不设置 payerAddress
    }

    // ==================== recordRuleTriggered ====================

    @Test
    @DisplayName("recordRuleTriggered：eventType = RULE_TRIGGERED")
    void recordRuleTriggered_correctEventType() {
        when(riskEventRepository.save(any(RiskEvent.class))).thenAnswer(inv -> {
            RiskEvent event = inv.getArgument(0);
            event.setId(1L);
            return event;
        });

        RiskEvent event = riskEventService.recordRuleTriggered(
                MERCHANT_ID, ORDER_ID, "RULE_HIGH_AMOUNT", "金额超过单笔限额");

        assertEquals(RiskEvent.EventType.RULE_TRIGGERED, event.getEventType());
        assertEquals(MERCHANT_ID, event.getMerchantId());
        assertEquals(ORDER_ID, event.getOrderId());
        assertEquals("RULE_HIGH_AMOUNT", event.getTriggeredRules());
        assertEquals("金额超过单笔限额", event.getDescription());
    }

    // ==================== recordManualReview ====================

    @Test
    @DisplayName("recordManualReview：eventType = MANUAL_REVIEW")
    void recordManualReview_correctEventType() {
        when(riskEventRepository.save(any(RiskEvent.class))).thenAnswer(inv -> {
            RiskEvent event = inv.getArgument(0);
            event.setId(1L);
            return event;
        });

        RiskEvent event = riskEventService.recordManualReview(
                MERCHANT_ID, ORDER_ID, "需要人工复核大额交易");

        assertEquals(RiskEvent.EventType.MANUAL_REVIEW, event.getEventType());
        assertEquals(MERCHANT_ID, event.getMerchantId());
        assertEquals(ORDER_ID, event.getOrderId());
        assertEquals("需要人工复核大额交易", event.getDescription());
    }

    // ==================== recordBlacklistAction ====================

    @Test
    @DisplayName("recordBlacklistAction：eventType = BLACKLIST_ACTION, description 包含 action 和 reason")
    void recordBlacklistAction_correctEventTypeAndDescription() {
        when(riskEventRepository.save(any(RiskEvent.class))).thenAnswer(inv -> {
            RiskEvent event = inv.getArgument(0);
            event.setId(1L);
            return event;
        });

        String targetAddress = "0xBadActor1234567890abcdef1234567890abcdef1234567890abcdef1234";
        RiskEvent event = riskEventService.recordBlacklistAction(
                MERCHANT_ID, targetAddress, "ADD", "欺诈交易");

        assertEquals(RiskEvent.EventType.BLACKLIST_ACTION, event.getEventType());
        assertEquals(MERCHANT_ID, event.getMerchantId());
        assertEquals(targetAddress, event.getPayeeAddress());
        assertEquals("ADD: 欺诈交易", event.getDescription());
        assertTrue(event.getDescription().contains("ADD"));
        assertTrue(event.getDescription().contains("欺诈交易"));
    }

    // ==================== getEventsByMerchant ====================

    @Test
    @DisplayName("getEventsByMerchant：返回列表按时间倒序")
    void getEventsByMerchant_returnsListDescending() {
        LocalDateTime now = LocalDateTime.now();
        RiskEvent event1 = new RiskEvent();
        event1.setId(1L);
        event1.setMerchantId(MERCHANT_ID);
        event1.setOccurredAt(now.minusHours(2));

        RiskEvent event2 = new RiskEvent();
        event2.setId(2L);
        event2.setMerchantId(MERCHANT_ID);
        event2.setOccurredAt(now.minusHours(1));

        when(riskEventRepository.findByMerchantIdOrderByOccurredAtDesc(MERCHANT_ID))
                .thenReturn(List.of(event2, event1)); // 倒序：最近的在前

        List<RiskEvent> events = riskEventService.getEventsByMerchant(MERCHANT_ID);

        assertEquals(2, events.size());
        assertEquals(2L, events.get(0).getId()); // 最近的事件在前
        assertEquals(1L, events.get(1).getId());
    }

    // ==================== getEventsByType ====================

    @Test
    @DisplayName("getEventsByType：按类型筛选")
    void getEventsByType_filtersByType() {
        RiskEvent event = new RiskEvent();
        event.setId(1L);
        event.setEventType(RiskEvent.EventType.PAYMENT_EVALUATION);

        when(riskEventRepository.findByEventTypeOrderByOccurredAtDesc(RiskEvent.EventType.PAYMENT_EVALUATION))
                .thenReturn(List.of(event));

        List<RiskEvent> events = riskEventService.getEventsByType(RiskEvent.EventType.PAYMENT_EVALUATION);

        assertEquals(1, events.size());
        assertEquals(RiskEvent.EventType.PAYMENT_EVALUATION, events.get(0).getEventType());
    }

    // ==================== getEventsByDecision ====================

    @Test
    @DisplayName("getEventsByDecision：按决策筛选")
    void getEventsByDecision_filtersByDecision() {
        RiskEvent event = new RiskEvent();
        event.setId(1L);
        event.setRiskDecision("REJECTED");

        when(riskEventRepository.findByRiskDecisionOrderByOccurredAtDesc("REJECTED"))
                .thenReturn(List.of(event));

        List<RiskEvent> events = riskEventService.getEventsByDecision("REJECTED");

        assertEquals(1, events.size());
        assertEquals("REJECTED", events.get(0).getRiskDecision());
    }

    // ==================== getEventsByOrder ====================

    @Test
    @DisplayName("getEventsByOrder：按订单筛选")
    void getEventsByOrder_filtersByOrder() {
        RiskEvent event = new RiskEvent();
        event.setId(1L);
        event.setOrderId(ORDER_ID);

        when(riskEventRepository.findByOrderId(ORDER_ID))
                .thenReturn(List.of(event));

        List<RiskEvent> events = riskEventService.getEventsByOrder(ORDER_ID);

        assertEquals(1, events.size());
        assertEquals(ORDER_ID, events.get(0).getOrderId());
    }

    // ==================== getEventsByTimeRange ====================

    @Test
    @DisplayName("getEventsByTimeRange：按时间范围筛选")
    void getEventsByTimeRange_filtersByTimeRange() {
        LocalDateTime start = LocalDateTime.now().minusHours(24);
        LocalDateTime end = LocalDateTime.now();

        RiskEvent event = new RiskEvent();
        event.setId(1L);
        event.setOccurredAt(LocalDateTime.now().minusHours(1));

        when(riskEventRepository.findByOccurredAtBetweenOrderByOccurredAtDesc(start, end))
                .thenReturn(List.of(event));

        List<RiskEvent> events = riskEventService.getEventsByTimeRange(start, end);

        assertEquals(1, events.size());
        assertNotNull(events.get(0).getOccurredAt());
    }

    // ==================== countEventsByMerchantAndDecision ====================

    @Test
    @DisplayName("countEventsByMerchantAndDecision：正确统计")
    void countEventsByMerchantAndDecision_correctCount() {
        when(riskEventRepository.countByMerchantIdAndRiskDecision(MERCHANT_ID, "REJECTED"))
                .thenReturn(15L);

        long count = riskEventService.countEventsByMerchantAndDecision(MERCHANT_ID, "REJECTED");

        assertEquals(15L, count);
    }

    // ==================== countEventsByType ====================

    @Test
    @DisplayName("countEventsByType：正确统计")
    void countEventsByType_correctCount() {
        when(riskEventRepository.countByEventType(RiskEvent.EventType.PAYMENT_EVALUATION))
                .thenReturn(80L);

        long count = riskEventService.countEventsByType(RiskEvent.EventType.PAYMENT_EVALUATION);

        assertEquals(80L, count);
    }

    // ==================== recordEvent with null merchantId ====================

    @Test
    @DisplayName("recordEvent：null merchantId 也能记录（系统级事件）")
    void recordEvent_nullMerchantId_systemLevelEvent() {
        when(riskEventRepository.save(any(RiskEvent.class))).thenAnswer(inv -> {
            RiskEvent event = inv.getArgument(0);
            event.setId(1L);
            return event;
        });

        RiskEvent event = riskEventService.recordEvent(
                RiskEvent.EventType.DEVICE_FINGERPRINT, null, null,
                null, null, null, null, null, null,
                null, "系统级设备指纹检测", "fp_hash_456", "10.0.0.1");

        assertNotNull(event.getEventId());
        assertNotNull(event.getOccurredAt());
        assertEquals(RiskEvent.EventType.DEVICE_FINGERPRINT, event.getEventType());
        assertNull(event.getMerchantId());
        assertEquals("系统级设备指纹检测", event.getDescription());
        assertEquals("fp_hash_456", event.getFingerprintHash());
        assertEquals("10.0.0.1", event.getIpAddress());
    }
}