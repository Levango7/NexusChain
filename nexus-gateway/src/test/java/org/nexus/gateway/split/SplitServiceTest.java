package org.nexus.gateway.split;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.security.MerchantOwnershipException;
import org.nexus.gateway.security.MerchantOwnershipGuard;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * SplitService 单元测试 — 使用 Mockito mock repositories。
 *
 * <p>覆盖分账规则创建校验、分账计算逻辑、商户结算金额计算、规则停用等场景。</p>
 */
class SplitServiceTest {

    private SplitRuleRepository splitRuleRepository;
    private SplitOrderRepository splitOrderRepository;
    private TieredSplitRuleRepository tieredSplitRuleRepository;
    private MerchantOwnershipGuard ownershipGuard;
    private SplitService splitService;

    private static final Long MERCHANT_ID = 500L;
    private static final String RECEIVER_ADDR = "0xReceiver1234567890abcdef1234567890abcdef123456";

    @BeforeEach
    void setUp() {
        splitRuleRepository = mock(SplitRuleRepository.class);
        splitOrderRepository = mock(SplitOrderRepository.class);
        tieredSplitRuleRepository = mock(TieredSplitRuleRepository.class);
        ownershipGuard = mock(MerchantOwnershipGuard.class);
        splitService = new SplitService(splitRuleRepository, splitOrderRepository, tieredSplitRuleRepository, ownershipGuard);
    }

    // ==================== createSplitRule ====================

    @Test
    @DisplayName("创建 RATIO 分账规则 — 成功")
    void createRatioSplitRuleSuccess() {
        when(splitRuleRepository.findByMerchantIdAndActiveTrueOrderByPriorityAsc(MERCHANT_ID))
                .thenReturn(List.of());
        when(splitRuleRepository.save(any(SplitRule.class))).thenAnswer(inv -> {
            SplitRule rule = inv.getArgument(0);
            rule.setId(1L);
            return rule;
        });

        SplitRule rule = splitService.createSplitRule(
                MERCHANT_ID, SplitRule.SplitType.RATIO, new BigDecimal("500"),
                RECEIVER_ADDR, "分销商佣金");

        assertNotNull(rule);
        assertEquals(SplitRule.SplitType.RATIO, rule.getSplitType());
        assertEquals(new BigDecimal("500"), rule.getSplitValue());
        assertEquals(RECEIVER_ADDR, rule.getReceiverAddress());
        assertEquals("分销商佣金", rule.getDescription());
        assertTrue(rule.isActive());
        assertEquals(10, rule.getPriority()); // RATIO 默认 priority=10
    }

    @Test
    @DisplayName("创建 FIXED 分账规则 — 成功")
    void createFixedSplitRuleSuccess() {
        when(splitRuleRepository.findByMerchantIdAndActiveTrueOrderByPriorityAsc(MERCHANT_ID))
                .thenReturn(List.of());
        when(splitRuleRepository.save(any(SplitRule.class))).thenAnswer(inv -> {
            SplitRule rule = inv.getArgument(0);
            rule.setId(2L);
            return rule;
        });

        SplitRule rule = splitService.createSplitRule(
                MERCHANT_ID, SplitRule.SplitType.FIXED, new BigDecimal("100.50"),
                RECEIVER_ADDR, "平台费");

        assertNotNull(rule);
        assertEquals(SplitRule.SplitType.FIXED, rule.getSplitType());
        assertEquals(new BigDecimal("100.50"), rule.getSplitValue());
        assertEquals(0, rule.getPriority()); // FIXED 默认 priority=0
    }

    @Test
    @DisplayName("创建 RATIO 规则时 value > 10000 — 抛出异常")
    void createRatioSplitRuleValueExceedsMax() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                splitService.createSplitRule(
                        MERCHANT_ID, SplitRule.SplitType.RATIO, new BigDecimal("10001"),
                        RECEIVER_ADDR, "超额"));

        assertTrue(ex.getMessage().contains("must be <= 10000"));
    }

    @Test
    @DisplayName("创建 RATIO 规则时 value < 1 — 抛出异常")
    void createRatioSplitRuleValueBelowMin() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                splitService.createSplitRule(
                        MERCHANT_ID, SplitRule.SplitType.RATIO, new BigDecimal("0"),
                        RECEIVER_ADDR, "无效"));

        assertTrue(ex.getMessage().contains("must be >= 1"));
    }

    @Test
    @DisplayName("创建 FIXED 规则时 value ≤ 0 — 抛出异常")
    void createFixedSplitRuleValueNotPositive() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                splitService.createSplitRule(
                        MERCHANT_ID, SplitRule.SplitType.FIXED, new BigDecimal("0"),
                        RECEIVER_ADDR, "无效"));

        assertTrue(ex.getMessage().contains("must be > 0"));
    }

    @Test
    @DisplayName("RATIO 规则总和超过 10000 — 抛出异常")
    void createRatioSplitRuleTotalExceedsMax() {
        // 已有一条 8000 基点的规则
        SplitRule existing = new SplitRule();
        existing.setSplitType(SplitRule.SplitType.RATIO);
        existing.setSplitValue(new BigDecimal("8000"));

        when(splitRuleRepository.findByMerchantIdAndActiveTrueOrderByPriorityAsc(MERCHANT_ID))
                .thenReturn(List.of(existing));

        // 再加 3000 基点 → 总和 11000 > 10000
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                splitService.createSplitRule(
                        MERCHANT_ID, SplitRule.SplitType.RATIO, new BigDecimal("3000"),
                        RECEIVER_ADDR, "超额"));

        assertTrue(ex.getMessage().contains("exceeds 10000"));
    }

    // ==================== calculateSplits ====================

    @Test
    @DisplayName("calculateSplits：无规则时返回空列表")
    void calculateSplitsNoRulesReturnsEmpty() {
        when(splitRuleRepository.findByMerchantIdAndActiveTrueOrderByPriorityAsc(MERCHANT_ID))
                .thenReturn(List.of());

        List<SplitOrder> splits = splitService.calculateSplits(
                "ORD-001", 1001L, MERCHANT_ID, new BigDecimal("1000"));

        assertNotNull(splits);
        assertTrue(splits.isEmpty());
    }

    @Test
    @DisplayName("calculateSplits：单条 RATIO 规则（500bp = 5%）— 金额正确")
    void calculateSplitsSingleRatioRule() {
        SplitRule rule = new SplitRule();
        rule.setId(1L);
        rule.setSplitType(SplitRule.SplitType.RATIO);
        rule.setSplitValue(new BigDecimal("500")); // 5%
        rule.setReceiverAddress(RECEIVER_ADDR);
        rule.setPriority(10);

        when(splitRuleRepository.findByMerchantIdAndActiveTrueOrderByPriorityAsc(MERCHANT_ID))
                .thenReturn(List.of(rule));

        List<SplitOrder> splits = splitService.calculateSplits(
                "ORD-001", 1001L, MERCHANT_ID, new BigDecimal("1000"));

        assertEquals(1, splits.size());
        // 1000 * 500 / 10000 = 50
        assertEquals(new BigDecimal("50.00000000"), splits.get(0).getAmount());
        assertEquals(SplitRule.SplitType.RATIO, splits.get(0).getSplitType());
        assertEquals(RECEIVER_ADDR, splits.get(0).getReceiverAddress());
        assertEquals(SplitOrder.SplitStatus.PENDING, splits.get(0).getStatus());
    }

    @Test
    @DisplayName("calculateSplits：单条 FIXED 规则 — 金额正确")
    void calculateSplitsSingleFixedRule() {
        SplitRule rule = new SplitRule();
        rule.setId(1L);
        rule.setSplitType(SplitRule.SplitType.FIXED);
        rule.setSplitValue(new BigDecimal("100"));
        rule.setReceiverAddress(RECEIVER_ADDR);
        rule.setPriority(0);

        when(splitRuleRepository.findByMerchantIdAndActiveTrueOrderByPriorityAsc(MERCHANT_ID))
                .thenReturn(List.of(rule));

        List<SplitOrder> splits = splitService.calculateSplits(
                "ORD-001", 1001L, MERCHANT_ID, new BigDecimal("1000"));

        assertEquals(1, splits.size());
        assertEquals(new BigDecimal("100"), splits.get(0).getAmount());
        assertEquals(SplitRule.SplitType.FIXED, splits.get(0).getSplitType());
    }

    @Test
    @DisplayName("calculateSplits：FIXED + RATIO 混合 — FIXED 先扣除，RATIO 对剩余金额按比例")
    void calculateSplitsMixedFixedAndRatio() {
        SplitRule fixedRule = new SplitRule();
        fixedRule.setId(1L);
        fixedRule.setSplitType(SplitRule.SplitType.FIXED);
        fixedRule.setSplitValue(new BigDecimal("200"));
        fixedRule.setReceiverAddress("0xFixedReceiver");
        fixedRule.setPriority(0);

        SplitRule ratioRule = new SplitRule();
        ratioRule.setId(2L);
        ratioRule.setSplitType(SplitRule.SplitType.RATIO);
        ratioRule.setSplitValue(new BigDecimal("1000")); // 10%
        ratioRule.setReceiverAddress("0xRatioReceiver");
        ratioRule.setPriority(10);

        when(splitRuleRepository.findByMerchantIdAndActiveTrueOrderByPriorityAsc(MERCHANT_ID))
                .thenReturn(List.of(fixedRule, ratioRule));

        List<SplitOrder> splits = splitService.calculateSplits(
                "ORD-001", 1001L, MERCHANT_ID, new BigDecimal("1000"));

        assertEquals(2, splits.size());

        // FIXED 先扣除 200
        SplitOrder fixedSplit = splits.get(0);
        assertEquals(SplitRule.SplitType.FIXED, fixedSplit.getSplitType());
        assertEquals(new BigDecimal("200"), fixedSplit.getAmount());

        // RATIO 对剩余金额 800 按 10% 分配 = 80
        SplitOrder ratioSplit = splits.get(1);
        assertEquals(SplitRule.SplitType.RATIO, ratioSplit.getSplitType());
        assertEquals(new BigDecimal("80.00000000"), ratioSplit.getAmount());
    }

    @Test
    @DisplayName("calculateSplits：多条 RATIO 规则 — 每条独立计算比例")
    void calculateSplitsMultipleRatioRules() {
        SplitRule rule1 = new SplitRule();
        rule1.setId(1L);
        rule1.setSplitType(SplitRule.SplitType.RATIO);
        rule1.setSplitValue(new BigDecimal("300")); // 3%
        rule1.setReceiverAddress("0xReceiver1");
        rule1.setPriority(10);

        SplitRule rule2 = new SplitRule();
        rule2.setId(2L);
        rule2.setSplitType(SplitRule.SplitType.RATIO);
        rule2.setSplitValue(new BigDecimal("500")); // 5%
        rule2.setReceiverAddress("0xReceiver2");
        rule2.setPriority(10);

        when(splitRuleRepository.findByMerchantIdAndActiveTrueOrderByPriorityAsc(MERCHANT_ID))
                .thenReturn(List.of(rule1, rule2));

        List<SplitOrder> splits = splitService.calculateSplits(
                "ORD-001", 1001L, MERCHANT_ID, new BigDecimal("1000"));

        assertEquals(2, splits.size());

        // 第一条：1000 * 300 / 10000 = 30
        assertEquals(new BigDecimal("30.00000000"), splits.get(0).getAmount());

        // 第二条：1000 * 500 / 10000 = 50
        assertEquals(new BigDecimal("50.00000000"), splits.get(1).getAmount());
    }

    @Test
    @DisplayName("calculateSplits：分账总额不超过订单金额")
    void calculateSplitsTotalDoesNotExceedOrderAmount() {
        SplitRule fixedRule = new SplitRule();
        fixedRule.setId(1L);
        fixedRule.setSplitType(SplitRule.SplitType.FIXED);
        fixedRule.setSplitValue(new BigDecimal("300"));
        fixedRule.setReceiverAddress("0xFixedReceiver");
        fixedRule.setPriority(0);

        SplitRule ratioRule = new SplitRule();
        ratioRule.setId(2L);
        ratioRule.setSplitType(SplitRule.SplitType.RATIO);
        ratioRule.setSplitValue(new BigDecimal("5000")); // 50%
        ratioRule.setReceiverAddress("0xRatioReceiver");
        ratioRule.setPriority(10);

        when(splitRuleRepository.findByMerchantIdAndActiveTrueOrderByPriorityAsc(MERCHANT_ID))
                .thenReturn(List.of(fixedRule, ratioRule));

        BigDecimal totalAmount = new BigDecimal("1000");
        List<SplitOrder> splits = splitService.calculateSplits(
                "ORD-001", 1001L, MERCHANT_ID, totalAmount);

        BigDecimal totalSplit = BigDecimal.ZERO;
        for (SplitOrder split : splits) {
            totalSplit = totalSplit.add(split.getAmount());
        }

        // 总分账金额 = 300 (FIXED) + 350 (RATIO: 700 * 50%) = 650 ≤ 1000
        assertTrue(totalSplit.compareTo(totalAmount) <= 0);
    }

    // ==================== getMerchantSettlementAmount ====================

    @Test
    @DisplayName("getMerchantSettlementAmount：无分账时返回全额")
    void getMerchantSettlementAmountNoSplitsReturnsFullAmount() {
        BigDecimal totalAmount = new BigDecimal("1000");
        BigDecimal settlement = splitService.getMerchantSettlementAmount(totalAmount, List.of());

        assertEquals(totalAmount, settlement);
    }

    @Test
    @DisplayName("getMerchantSettlementAmount：有分账时返回扣除后的余额")
    void getMerchantSettlementAmountWithSplitsReturnsRemaining() {
        BigDecimal totalAmount = new BigDecimal("1000");

        SplitOrder split1 = new SplitOrder();
        split1.setAmount(new BigDecimal("200"));

        SplitOrder split2 = new SplitOrder();
        split2.setAmount(new BigDecimal("50"));

        BigDecimal settlement = splitService.getMerchantSettlementAmount(
                totalAmount, List.of(split1, split2));

        // 1000 - 200 - 50 = 750
        assertEquals(new BigDecimal("750"), settlement);
    }

    // ==================== deactivateSplitRule ====================

    @Test
    @DisplayName("deactivateSplitRule：成功停用")
    void deactivateSplitRuleSuccess() {
        SplitRule rule = new SplitRule();
        rule.setId(1L);
        rule.setMerchantId(MERCHANT_ID);
        rule.setActive(true);

        when(splitRuleRepository.findByIdAndMerchantId(1L, MERCHANT_ID))
                .thenReturn(Optional.of(rule));
        when(splitRuleRepository.save(any(SplitRule.class))).thenAnswer(inv -> inv.getArgument(0));

        splitService.deactivateSplitRule(1L, MERCHANT_ID);

        assertFalse(rule.isActive());
        verify(splitRuleRepository).save(rule);
    }

    @Test
    @DisplayName("deactivateSplitRule：规则不属于该商户 — 抛出异常")
    void deactivateSplitRuleNotOwnedByMerchant() {
        when(splitRuleRepository.findByIdAndMerchantId(1L, MERCHANT_ID))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                splitService.deactivateSplitRule(1L, MERCHANT_ID));
    }

    // ==================== 阶梯分账 ====================

    @Test
    @DisplayName("createTieredSplitRule：创建阶梯分账规则 — 成功")
    void createTieredSplitRuleSuccess() {
        when(tieredSplitRuleRepository.findByMerchantIdAndActiveTrueOrderByTierOrderAsc(MERCHANT_ID))
                .thenReturn(List.of());
        when(tieredSplitRuleRepository.save(any(TieredSplitRule.class))).thenAnswer(inv -> {
            TieredSplitRule rule = inv.getArgument(0);
            rule.setId(1L);
            return rule;
        });

        TieredSplitRule rule = splitService.createTieredSplitRule(
                MERCHANT_ID, RECEIVER_ADDR,
                BigDecimal.ZERO, new BigDecimal("1000"),
                new BigDecimal("1000"), "0-1000元区间分账10%");

        assertNotNull(rule);
        assertEquals(MERCHANT_ID, rule.getMerchantId());
        assertEquals(RECEIVER_ADDR, rule.getReceiverAddress());
        assertEquals(BigDecimal.ZERO, rule.getTierMinAmount());
        assertEquals(new BigDecimal("1000"), rule.getTierMaxAmount());
        assertEquals(new BigDecimal("1000"), rule.getSplitRatio());
        assertTrue(rule.isActive());
        assertEquals(0, rule.getTierOrder());
    }

    @Test
    @DisplayName("createTieredSplitRule：splitRatio > 10000 — 抛出异常")
    void createTieredSplitRuleRatioExceedsMax() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                splitService.createTieredSplitRule(
                        MERCHANT_ID, RECEIVER_ADDR,
                        BigDecimal.ZERO, new BigDecimal("1000"),
                        new BigDecimal("10001"), "超额"));

        assertTrue(ex.getMessage().contains("must be <= 10000"));
    }

    @Test
    @DisplayName("createTieredSplitRule：tierMaxAmount <= tierMinAmount — 抛出异常")
    void createTieredSplitRuleInvalidTierRange() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                splitService.createTieredSplitRule(
                        MERCHANT_ID, RECEIVER_ADDR,
                        new BigDecimal("1000"), new BigDecimal("500"),
                        new BigDecimal("1000"), "无效区间"));

        assertTrue(ex.getMessage().contains("tierMaxAmount must be > tierMinAmount"));
    }

    @Test
    @DisplayName("createTieredSplitRule：区间重叠 — 抛出异常")
    void createTieredSplitRuleOverlap() {
        TieredSplitRule existing = new TieredSplitRule();
        existing.setId(1L);
        existing.setTierMinAmount(BigDecimal.ZERO);
        existing.setTierMaxAmount(new BigDecimal("1000"));

        when(tieredSplitRuleRepository.findByMerchantIdAndActiveTrueOrderByTierOrderAsc(MERCHANT_ID))
                .thenReturn(List.of(existing));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                splitService.createTieredSplitRule(
                        MERCHANT_ID, RECEIVER_ADDR,
                        new BigDecimal("500"), new BigDecimal("1500"),
                        new BigDecimal("800"), "重叠区间"));

        assertTrue(ex.getMessage().contains("overlaps"));
    }

    @Test
    @DisplayName("calculateTieredSplits：匹配第一阶梯（0-1000元，10%）— 金额正确")
    void calculateTieredSplitsFirstTier() {
        TieredSplitRule rule1 = new TieredSplitRule();
        rule1.setId(1L);
        rule1.setReceiverAddress(RECEIVER_ADDR);
        rule1.setTierMinAmount(BigDecimal.ZERO);
        rule1.setTierMaxAmount(new BigDecimal("1000"));
        rule1.setSplitRatio(new BigDecimal("1000")); // 10%
        rule1.setTierOrder(0);

        TieredSplitRule rule2 = new TieredSplitRule();
        rule2.setId(2L);
        rule2.setReceiverAddress(RECEIVER_ADDR);
        rule2.setTierMinAmount(new BigDecimal("1000"));
        rule2.setTierMaxAmount(new BigDecimal("10000"));
        rule2.setSplitRatio(new BigDecimal("800")); // 8%
        rule2.setTierOrder(1);

        when(tieredSplitRuleRepository.findByMerchantIdAndActiveTrueOrderByTierOrderAsc(MERCHANT_ID))
                .thenReturn(List.of(rule1, rule2));

        List<SplitOrder> splits = splitService.calculateTieredSplits(
                "ORD-001", 1001L, MERCHANT_ID, new BigDecimal("500"));

        assertEquals(1, splits.size());
        // 500 * 1000 / 10000 = 50
        assertEquals(new BigDecimal("50.00000000"), splits.get(0).getAmount());
        assertEquals(SplitRule.SplitType.RATIO, splits.get(0).getSplitType());
    }

    @Test
    @DisplayName("calculateTieredSplits：匹配第二阶梯（1000-10000元，8%）— 金额正确")
    void calculateTieredSplitsSecondTier() {
        TieredSplitRule rule1 = new TieredSplitRule();
        rule1.setId(1L);
        rule1.setReceiverAddress(RECEIVER_ADDR);
        rule1.setTierMinAmount(BigDecimal.ZERO);
        rule1.setTierMaxAmount(new BigDecimal("1000"));
        rule1.setSplitRatio(new BigDecimal("1000")); // 10%
        rule1.setTierOrder(0);

        TieredSplitRule rule2 = new TieredSplitRule();
        rule2.setId(2L);
        rule2.setReceiverAddress(RECEIVER_ADDR);
        rule2.setTierMinAmount(new BigDecimal("1000"));
        rule2.setTierMaxAmount(new BigDecimal("10000"));
        rule2.setSplitRatio(new BigDecimal("800")); // 8%
        rule2.setTierOrder(1);

        when(tieredSplitRuleRepository.findByMerchantIdAndActiveTrueOrderByTierOrderAsc(MERCHANT_ID))
                .thenReturn(List.of(rule1, rule2));

        List<SplitOrder> splits = splitService.calculateTieredSplits(
                "ORD-001", 1001L, MERCHANT_ID, new BigDecimal("5000"));

        assertEquals(1, splits.size());
        // 5000 * 800 / 10000 = 400
        assertEquals(new BigDecimal("400.00000000"), splits.get(0).getAmount());
    }

    @Test
    @DisplayName("calculateTieredSplits：匹配最高阶梯（10000+元，5%，无上限）— 金额正确")
    void calculateTieredSplitsTopTierNoUpperBound() {
        TieredSplitRule rule = new TieredSplitRule();
        rule.setId(1L);
        rule.setReceiverAddress(RECEIVER_ADDR);
        rule.setTierMinAmount(new BigDecimal("10000"));
        rule.setTierMaxAmount(null); // 无上限
        rule.setSplitRatio(new BigDecimal("500")); // 5%
        rule.setTierOrder(0);

        when(tieredSplitRuleRepository.findByMerchantIdAndActiveTrueOrderByTierOrderAsc(MERCHANT_ID))
                .thenReturn(List.of(rule));

        List<SplitOrder> splits = splitService.calculateTieredSplits(
                "ORD-001", 1001L, MERCHANT_ID, new BigDecimal("50000"));

        assertEquals(1, splits.size());
        // 50000 * 500 / 10000 = 2500
        assertEquals(new BigDecimal("2500.00000000"), splits.get(0).getAmount());
    }

    @Test
    @DisplayName("calculateTieredSplits：无阶梯规则时返回空列表")
    void calculateTieredSplitsNoRulesReturnsEmpty() {
        when(tieredSplitRuleRepository.findByMerchantIdAndActiveTrueOrderByTierOrderAsc(MERCHANT_ID))
                .thenReturn(List.of());

        List<SplitOrder> splits = splitService.calculateTieredSplits(
                "ORD-001", 1001L, MERCHANT_ID, new BigDecimal("1000"));

        assertNotNull(splits);
        assertTrue(splits.isEmpty());
    }

    @Test
    @DisplayName("TieredSplitRule.matchesTier：金额在区间内 — 返回 true")
    void tieredSplitRuleMatchesTier() {
        TieredSplitRule rule = new TieredSplitRule();
        rule.setTierMinAmount(new BigDecimal("1000"));
        rule.setTierMaxAmount(new BigDecimal("10000"));

        assertTrue(rule.matchesTier(new BigDecimal("5000")));
        assertTrue(rule.matchesTier(new BigDecimal("1000"))); // 左闭
        assertFalse(rule.matchesTier(new BigDecimal("10000"))); // 右开
        assertFalse(rule.matchesTier(new BigDecimal("500")));
    }

    @Test
    @DisplayName("TieredSplitRule.matchesTier：无上限区间 — 金额 >= tierMin 时返回 true")
    void tieredSplitRuleMatchesTierNoUpperBound() {
        TieredSplitRule rule = new TieredSplitRule();
        rule.setTierMinAmount(new BigDecimal("10000"));
        rule.setTierMaxAmount(null);

        assertTrue(rule.matchesTier(new BigDecimal("10000")));
        assertTrue(rule.matchesTier(new BigDecimal("999999")));
        assertFalse(rule.matchesTier(new BigDecimal("5000")));
    }
}