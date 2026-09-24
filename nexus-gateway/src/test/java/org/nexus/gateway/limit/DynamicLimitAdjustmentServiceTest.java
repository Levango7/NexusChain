package org.nexus.gateway.limit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DynamicLimitAdjustmentService 单元测试 — 使用 Mockito mock repositories。
 *
 * <p>覆盖动态限额调整规则的创建、评估触发条件、限额调整计算、调整历史查询等场景。</p>
 */
class DynamicLimitAdjustmentServiceTest {

    private LimitAdjustmentRuleRepository ruleRepository;
    private LimitAdjustmentRecordRepository recordRepository;
    private MerchantLimitConfigRepository limitConfigRepository;
    private PaymentOrderRepository paymentOrderRepository;
    private DynamicLimitAdjustmentService dynamicLimitAdjustmentService;

    private static final Long MERCHANT_ID = 500L;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(LimitAdjustmentRuleRepository.class);
        recordRepository = mock(LimitAdjustmentRecordRepository.class);
        limitConfigRepository = mock(MerchantLimitConfigRepository.class);
        paymentOrderRepository = mock(PaymentOrderRepository.class);
        dynamicLimitAdjustmentService = new DynamicLimitAdjustmentService(
                ruleRepository, recordRepository, limitConfigRepository, paymentOrderRepository);
    }

    // ==================== createAdjustmentRule ====================

    @Test
    @DisplayName("createAdjustmentRule：创建 CONSECUTIVE_SUCCESS 规则 — 成功")
    void createConsecutiveSuccessRule() {
        when(ruleRepository.save(any(LimitAdjustmentRule.class))).thenAnswer(inv -> {
            LimitAdjustmentRule rule = inv.getArgument(0);
            rule.setId(1L);
            return rule;
        });

        LimitAdjustmentRule rule = new LimitAdjustmentRule();
        rule.setMerchantId(MERCHANT_ID);
        rule.setRuleType(LimitAdjustmentRule.RuleType.CONSECUTIVE_SUCCESS);
        rule.setTriggerThreshold(10);
        rule.setAdjustmentDirection(LimitAdjustmentRule.AdjustmentDirection.INCREASE);
        rule.setAdjustmentPercentage(new BigDecimal("10"));
        rule.setTargetLimitType(LimitAdjustmentRule.TargetLimitType.DAILY_MAX);

        LimitAdjustmentRule saved = dynamicLimitAdjustmentService.createAdjustmentRule(rule);

        assertNotNull(saved);
        assertEquals(LimitAdjustmentRule.RuleType.CONSECUTIVE_SUCCESS, saved.getRuleType());
        assertTrue(saved.isActive());
    }

    @Test
    @DisplayName("createAdjustmentRule：CONSECUTIVE_SUCCESS + DECREASE 方向 — 抛出异常")
    void createRuleConsecutiveSuccessWithDecrease() {
        LimitAdjustmentRule rule = new LimitAdjustmentRule();
        rule.setRuleType(LimitAdjustmentRule.RuleType.CONSECUTIVE_SUCCESS);
        rule.setTriggerThreshold(10);
        rule.setAdjustmentDirection(LimitAdjustmentRule.AdjustmentDirection.DECREASE);
        rule.setAdjustmentPercentage(new BigDecimal("10"));
        rule.setTargetLimitType(LimitAdjustmentRule.TargetLimitType.DAILY_MAX);

        assertThrows(IllegalArgumentException.class, () ->
                dynamicLimitAdjustmentService.createAdjustmentRule(rule));
    }

    @Test
    @DisplayName("createAdjustmentRule：HIGH_REFUND_RATE + INCREASE 方向 — 抛出异常")
    void createRuleHighRefundRateWithIncrease() {
        LimitAdjustmentRule rule = new LimitAdjustmentRule();
        rule.setRuleType(LimitAdjustmentRule.RuleType.HIGH_REFUND_RATE);
        rule.setTriggerThreshold(20);
        rule.setAdjustmentDirection(LimitAdjustmentRule.AdjustmentDirection.INCREASE);
        rule.setAdjustmentPercentage(new BigDecimal("10"));
        rule.setTargetLimitType(LimitAdjustmentRule.TargetLimitType.DAILY_MAX);

        assertThrows(IllegalArgumentException.class, () ->
                dynamicLimitAdjustmentService.createAdjustmentRule(rule));
    }

    @Test
    @DisplayName("createAdjustmentRule：triggerThreshold <= 0 — 抛出异常")
    void createRuleInvalidThreshold() {
        LimitAdjustmentRule rule = new LimitAdjustmentRule();
        rule.setRuleType(LimitAdjustmentRule.RuleType.CONSECUTIVE_SUCCESS);
        rule.setTriggerThreshold(0);
        rule.setAdjustmentDirection(LimitAdjustmentRule.AdjustmentDirection.INCREASE);
        rule.setAdjustmentPercentage(new BigDecimal("10"));
        rule.setTargetLimitType(LimitAdjustmentRule.TargetLimitType.DAILY_MAX);

        assertThrows(IllegalArgumentException.class, () ->
                dynamicLimitAdjustmentService.createAdjustmentRule(rule));
    }

    // ==================== evaluateAndAdjust ====================

    @Test
    @DisplayName("evaluateAndAdjust：无规则时返回空列表")
    void evaluateAndAdjustNoRules() {
        when(ruleRepository.findByMerchantIdAndActiveTrue(MERCHANT_ID))
                .thenReturn(List.of());

        List<LimitAdjustmentRecord> result = dynamicLimitAdjustmentService.evaluateAndAdjust(MERCHANT_ID);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("evaluateAndAdjust：无限额配置时返回空列表")
    void evaluateAndAdjustNoConfig() {
        when(ruleRepository.findByMerchantIdAndActiveTrue(MERCHANT_ID))
                .thenReturn(List.of(new LimitAdjustmentRule()));
        when(limitConfigRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.empty());

        List<LimitAdjustmentRecord> result = dynamicLimitAdjustmentService.evaluateAndAdjust(MERCHANT_ID);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("evaluateAndAdjust：CONSECUTIVE_SUCCESS 触发提升限额")
    void evaluateAndAdjustConsecutiveSuccessTriggered() {
        LimitAdjustmentRule rule = new LimitAdjustmentRule();
        rule.setId(1L);
        rule.setMerchantId(MERCHANT_ID);
        rule.setRuleType(LimitAdjustmentRule.RuleType.CONSECUTIVE_SUCCESS);
        rule.setTriggerThreshold(5);
        rule.setAdjustmentDirection(LimitAdjustmentRule.AdjustmentDirection.INCREASE);
        rule.setAdjustmentPercentage(new BigDecimal("10"));
        rule.setTargetLimitType(LimitAdjustmentRule.TargetLimitType.DAILY_MAX);
        rule.setAdjustmentCap(new BigDecimal("200000"));

        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setDailyAccumulatedMaxAmount(new BigDecimal("100000"));

        when(ruleRepository.findByMerchantIdAndActiveTrue(MERCHANT_ID))
                .thenReturn(List.of(rule));
        when(limitConfigRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(config));

        // 模拟 5 笔连续成功交易
        List<PaymentOrder> orders = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            PaymentOrder order = new PaymentOrder();
            order.setStatus(PaymentOrder.OrderStatus.PAID);
            orders.add(order);
        }
        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(orders);
        when(limitConfigRepository.save(any(MerchantLimitConfig.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(recordRepository.save(any(LimitAdjustmentRecord.class)))
                .thenAnswer(inv -> {
                    LimitAdjustmentRecord record = inv.getArgument(0);
                    record.setId(1L);
                    return record;
                });

        List<LimitAdjustmentRecord> result = dynamicLimitAdjustmentService.evaluateAndAdjust(MERCHANT_ID);

        assertEquals(1, result.size());
        LimitAdjustmentRecord record = result.get(0);
        assertEquals(LimitAdjustmentRule.RuleType.CONSECUTIVE_SUCCESS, record.getRuleType());
        assertEquals(new BigDecimal("100000"), record.getOldValue());
        // 100000 * (1 + 10%) = 110000
        assertEquals(new BigDecimal("110000.00000000"), record.getNewValue());
        assertEquals(LimitAdjustmentRule.AdjustmentDirection.INCREASE, record.getAdjustmentDirection());
    }

    @Test
    @DisplayName("evaluateAndAdjust：CONSECUTIVE_SUCCESS 未达阈值不触发")
    void evaluateAndAdjustConsecutiveSuccessNotTriggered() {
        LimitAdjustmentRule rule = new LimitAdjustmentRule();
        rule.setId(1L);
        rule.setMerchantId(MERCHANT_ID);
        rule.setRuleType(LimitAdjustmentRule.RuleType.CONSECUTIVE_SUCCESS);
        rule.setTriggerThreshold(10);
        rule.setAdjustmentDirection(LimitAdjustmentRule.AdjustmentDirection.INCREASE);
        rule.setAdjustmentPercentage(new BigDecimal("10"));
        rule.setTargetLimitType(LimitAdjustmentRule.TargetLimitType.DAILY_MAX);

        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setDailyAccumulatedMaxAmount(new BigDecimal("100000"));

        when(ruleRepository.findByMerchantIdAndActiveTrue(MERCHANT_ID))
                .thenReturn(List.of(rule));
        when(limitConfigRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(config));

        // 模拟 3 笔连续成功交易（未达阈值 10）
        List<PaymentOrder> orders = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            PaymentOrder order = new PaymentOrder();
            order.setStatus(PaymentOrder.OrderStatus.PAID);
            orders.add(order);
        }
        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(orders);

        List<LimitAdjustmentRecord> result = dynamicLimitAdjustmentService.evaluateAndAdjust(MERCHANT_ID);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("evaluateAndAdjust：调整上限约束 — 不超过 adjustmentCap")
    void evaluateAndAdjustWithCap() {
        LimitAdjustmentRule rule = new LimitAdjustmentRule();
        rule.setId(1L);
        rule.setMerchantId(MERCHANT_ID);
        rule.setRuleType(LimitAdjustmentRule.RuleType.CONSECUTIVE_SUCCESS);
        rule.setTriggerThreshold(5);
        rule.setAdjustmentDirection(LimitAdjustmentRule.AdjustmentDirection.INCREASE);
        rule.setAdjustmentPercentage(new BigDecimal("50"));
        rule.setTargetLimitType(LimitAdjustmentRule.TargetLimitType.DAILY_MAX);
        rule.setAdjustmentCap(new BigDecimal("120000"));

        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setDailyAccumulatedMaxAmount(new BigDecimal("100000"));

        when(ruleRepository.findByMerchantIdAndActiveTrue(MERCHANT_ID))
                .thenReturn(List.of(rule));
        when(limitConfigRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(config));

        List<PaymentOrder> orders = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            PaymentOrder order = new PaymentOrder();
            order.setStatus(PaymentOrder.OrderStatus.PAID);
            orders.add(order);
        }
        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(orders);
        when(limitConfigRepository.save(any(MerchantLimitConfig.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(recordRepository.save(any(LimitAdjustmentRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<LimitAdjustmentRecord> result = dynamicLimitAdjustmentService.evaluateAndAdjust(MERCHANT_ID);

        assertEquals(1, result.size());
        // 100000 * 1.5 = 150000，但 cap = 120000，所以 new = 120000
        assertEquals(new BigDecimal("120000"), result.get(0).getNewValue());
    }

    @Test
    @DisplayName("evaluateAndAdjust：目标限额值为 null — 不调整")
    void evaluateAndAdjustTargetNull() {
        LimitAdjustmentRule rule = new LimitAdjustmentRule();
        rule.setId(1L);
        rule.setMerchantId(MERCHANT_ID);
        rule.setRuleType(LimitAdjustmentRule.RuleType.CONSECUTIVE_SUCCESS);
        rule.setTriggerThreshold(5);
        rule.setAdjustmentDirection(LimitAdjustmentRule.AdjustmentDirection.INCREASE);
        rule.setAdjustmentPercentage(new BigDecimal("10"));
        rule.setTargetLimitType(LimitAdjustmentRule.TargetLimitType.DAILY_MAX);

        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        // dailyAccumulatedMaxAmount 为 null

        when(ruleRepository.findByMerchantIdAndActiveTrue(MERCHANT_ID))
                .thenReturn(List.of(rule));
        when(limitConfigRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(config));

        List<PaymentOrder> orders = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            PaymentOrder order = new PaymentOrder();
            order.setStatus(PaymentOrder.OrderStatus.PAID);
            orders.add(order);
        }
        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(orders);

        List<LimitAdjustmentRecord> result = dynamicLimitAdjustmentService.evaluateAndAdjust(MERCHANT_ID);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("getAdjustmentHistory：查询商户调整历史")
    void getAdjustmentHistory() {
        LimitAdjustmentRecord record = new LimitAdjustmentRecord();
        record.setId(1L);
        record.setMerchantId(MERCHANT_ID);

        when(recordRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(record));

        List<LimitAdjustmentRecord> result = dynamicLimitAdjustmentService.getAdjustmentHistory(MERCHANT_ID);

        assertEquals(1, result.size());
        assertEquals(MERCHANT_ID, result.get(0).getMerchantId());
    }
}