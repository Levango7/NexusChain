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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LimitCheckService 单元测试 — 使用 Mockito mock repositories。
 *
 * <p>覆盖限额检查的各维度（单笔、日累计、日笔数、月累计、月笔数），
 * 以及综合检查、配置管理等功能。</p>
 */
class LimitCheckServiceTest {

    private MerchantLimitConfigRepository limitConfigRepository;
    private PaymentOrderRepository paymentOrderRepository;
    private LimitCheckService limitCheckService;

    private static final Long MERCHANT_ID = 500L;

    @BeforeEach
    void setUp() {
        limitConfigRepository = mock(MerchantLimitConfigRepository.class);
        paymentOrderRepository = mock(PaymentOrderRepository.class);
        limitCheckService = new LimitCheckService(limitConfigRepository, paymentOrderRepository);
    }

    // ==================== checkLimits ====================

    @Test
    @DisplayName("checkLimits：无配置时返回 passed")
    void checkLimitsNoConfigReturnsPassed() {
        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        LimitCheckResult result = limitCheckService.checkLimits(MERCHANT_ID, new BigDecimal("100"));

        assertTrue(result.isPassed());
        assertNull(result.getViolationType());
    }

    @Test
    @DisplayName("checkLimits：综合检查 — 单笔金额超限先返回 -> failed(SINGLE_MAX)")
    void checkLimitsSingleMaxFailsFirst() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setSingleTransactionMaxAmount(new BigDecimal("1000"));
        config.setDailyAccumulatedMaxAmount(new BigDecimal("10000"));

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        LimitCheckResult result = limitCheckService.checkLimits(MERCHANT_ID, new BigDecimal("5000"));

        assertFalse(result.isPassed());
        assertEquals("SINGLE_MAX", result.getViolationType());
    }

    @Test
    @DisplayName("checkLimits：综合检查 — 日累计超限 -> failed(DAILY_AMOUNT)")
    void checkLimitsDailyAmountFails() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setSingleTransactionMaxAmount(new BigDecimal("10000"));
        config.setDailyAccumulatedMaxAmount(new BigDecimal("5000"));

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        // 模拟当天已有累计 4000
        PaymentOrder paidOrder = createPaidOrder(new BigDecimal("4000"));
        when(paymentOrderRepository.findByMerchantIdAndStatusAndPaidAtBetween(
                eq(MERCHANT_ID), eq(PaymentOrder.OrderStatus.PAID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(paidOrder));

        // 新交易 2000，累计将达 6000 > 5000
        LimitCheckResult result = limitCheckService.checkLimits(MERCHANT_ID, new BigDecimal("2000"));

        assertFalse(result.isPassed());
        assertEquals("DAILY_AMOUNT", result.getViolationType());
    }

    @Test
    @DisplayName("checkLimits：综合检查 — 全部通过 -> passed")
    void checkLimitsAllPassed() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setSingleTransactionMinAmount(new BigDecimal("1"));
        config.setSingleTransactionMaxAmount(new BigDecimal("10000"));
        config.setDailyAccumulatedMaxAmount(new BigDecimal("50000"));
        config.setDailyMaxTransactionCount(100);
        config.setMonthlyAccumulatedMaxAmount(new BigDecimal("500000"));
        config.setMonthlyMaxTransactionCount(2000);

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));
        when(paymentOrderRepository.findByMerchantIdAndStatusAndPaidAtBetween(
                eq(MERCHANT_ID), eq(PaymentOrder.OrderStatus.PAID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        LimitCheckResult result = limitCheckService.checkLimits(MERCHANT_ID, new BigDecimal("100"));

        assertTrue(result.isPassed());
    }

    // ==================== checkSingleTransactionLimit ====================

    @Test
    @DisplayName("checkSingleTransactionLimit：金额 < singleMin -> failed(SINGLE_MIN)")
    void checkSingleTransactionLimitBelowMin() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setSingleTransactionMinAmount(new BigDecimal("10"));

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        LimitCheckResult result = limitCheckService.checkSingleTransactionLimit(MERCHANT_ID, new BigDecimal("5"));

        assertFalse(result.isPassed());
        assertEquals("SINGLE_MIN", result.getViolationType());
    }

    @Test
    @DisplayName("checkSingleTransactionLimit：金额 > singleMax -> failed(SINGLE_MAX)")
    void checkSingleTransactionLimitAboveMax() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setSingleTransactionMaxAmount(new BigDecimal("1000"));

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        LimitCheckResult result = limitCheckService.checkSingleTransactionLimit(MERCHANT_ID, new BigDecimal("5000"));

        assertFalse(result.isPassed());
        assertEquals("SINGLE_MAX", result.getViolationType());
    }

    @Test
    @DisplayName("checkSingleTransactionLimit：金额在 min~max 之间 -> passed")
    void checkSingleTransactionLimitInRange() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setSingleTransactionMinAmount(new BigDecimal("10"));
        config.setSingleTransactionMaxAmount(new BigDecimal("1000"));

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        LimitCheckResult result = limitCheckService.checkSingleTransactionLimit(MERCHANT_ID, new BigDecimal("500"));

        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("checkSingleTransactionLimit：只有 singleMin 无 singleMax -> 只检查 min")
    void checkSingleTransactionLimitOnlyMin() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setSingleTransactionMinAmount(new BigDecimal("10"));
        // singleMax 为 null

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        // 金额 > 10，应该通过（无 max 限制）
        LimitCheckResult result = limitCheckService.checkSingleTransactionLimit(MERCHANT_ID, new BigDecimal("999999"));
        assertTrue(result.isPassed());

        // 金额 < 10，应该失败
        result = limitCheckService.checkSingleTransactionLimit(MERCHANT_ID, new BigDecimal("5"));
        assertFalse(result.isPassed());
        assertEquals("SINGLE_MIN", result.getViolationType());
    }

    @Test
    @DisplayName("checkSingleTransactionLimit：只有 singleMax 无 singleMin -> 只检查 max")
    void checkSingleTransactionLimitOnlyMax() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setSingleTransactionMaxAmount(new BigDecimal("1000"));
        // singleMin 为 null

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        // 金额 < 1000，应该通过（无 min 限制）
        LimitCheckResult result = limitCheckService.checkSingleTransactionLimit(MERCHANT_ID, new BigDecimal("1"));
        assertTrue(result.isPassed());

        // 金额 > 1000，应该失败
        result = limitCheckService.checkSingleTransactionLimit(MERCHANT_ID, new BigDecimal("5000"));
        assertFalse(result.isPassed());
        assertEquals("SINGLE_MAX", result.getViolationType());
    }

    // ==================== checkDailyAmountLimit ====================

    @Test
    @DisplayName("checkDailyAmountLimit：累计 + 新金额 > dailyMax -> failed(DAILY_AMOUNT)")
    void checkDailyAmountLimitExceeds() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setDailyAccumulatedMaxAmount(new BigDecimal("5000"));

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        // 当天已有累计 4000
        PaymentOrder paidOrder = createPaidOrder(new BigDecimal("4000"));
        when(paymentOrderRepository.findByMerchantIdAndStatusAndPaidAtBetween(
                eq(MERCHANT_ID), eq(PaymentOrder.OrderStatus.PAID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(paidOrder));

        // 新交易 2000，累计将达 6000 > 5000
        LimitCheckResult result = limitCheckService.checkDailyAmountLimit(MERCHANT_ID, new BigDecimal("2000"));

        assertFalse(result.isPassed());
        assertEquals("DAILY_AMOUNT", result.getViolationType());
    }

    @Test
    @DisplayName("checkDailyAmountLimit：累计 + 新金额 <= dailyMax -> passed")
    void checkDailyAmountLimitWithinRange() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setDailyAccumulatedMaxAmount(new BigDecimal("5000"));

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        // 当天已有累计 3000
        PaymentOrder paidOrder = createPaidOrder(new BigDecimal("3000"));
        when(paymentOrderRepository.findByMerchantIdAndStatusAndPaidAtBetween(
                eq(MERCHANT_ID), eq(PaymentOrder.OrderStatus.PAID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(paidOrder));

        // 新交易 2000，累计将达 5000 = 5000，不超限
        LimitCheckResult result = limitCheckService.checkDailyAmountLimit(MERCHANT_ID, new BigDecimal("2000"));

        assertTrue(result.isPassed());
    }

    @Test
    @DisplayName("checkDailyAmountLimit：无 dailyMax 配置 -> passed")
    void checkDailyAmountLimitNoConfig() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        // dailyMax 为 null

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        LimitCheckResult result = limitCheckService.checkDailyAmountLimit(MERCHANT_ID, new BigDecimal("999999"));

        assertTrue(result.isPassed());
    }

    // ==================== checkDailyCountLimit ====================

    @Test
    @DisplayName("checkDailyCountLimit：笔数 >= dailyMaxCount -> failed(DAILY_COUNT)")
    void checkDailyCountLimitExceeds() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setDailyMaxTransactionCount(5);

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        // 当天已有 5 笔
        List<PaymentOrder> paidOrders = List.of(
                createPaidOrder(new BigDecimal("100")),
                createPaidOrder(new BigDecimal("200")),
                createPaidOrder(new BigDecimal("300")),
                createPaidOrder(new BigDecimal("400")),
                createPaidOrder(new BigDecimal("500")));
        when(paymentOrderRepository.findByMerchantIdAndStatusAndPaidAtBetween(
                eq(MERCHANT_ID), eq(PaymentOrder.OrderStatus.PAID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(paidOrders);

        LimitCheckResult result = limitCheckService.checkDailyCountLimit(MERCHANT_ID);

        assertFalse(result.isPassed());
        assertEquals("DAILY_COUNT", result.getViolationType());
    }

    @Test
    @DisplayName("checkDailyCountLimit：笔数 < dailyMaxCount -> passed")
    void checkDailyCountLimitWithinRange() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setDailyMaxTransactionCount(10);

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        // 当天已有 3 笔
        List<PaymentOrder> paidOrders = List.of(
                createPaidOrder(new BigDecimal("100")),
                createPaidOrder(new BigDecimal("200")),
                createPaidOrder(new BigDecimal("300")));
        when(paymentOrderRepository.findByMerchantIdAndStatusAndPaidAtBetween(
                eq(MERCHANT_ID), eq(PaymentOrder.OrderStatus.PAID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(paidOrders);

        LimitCheckResult result = limitCheckService.checkDailyCountLimit(MERCHANT_ID);

        assertTrue(result.isPassed());
    }

    // ==================== checkMonthlyAmountLimit ====================

    @Test
    @DisplayName("checkMonthlyAmountLimit：累计 + 新金额 > monthlyMax -> failed(MONTHLY_AMOUNT)")
    void checkMonthlyAmountLimitExceeds() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setMonthlyAccumulatedMaxAmount(new BigDecimal("50000"));

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        // 当月已有累计 40000
        PaymentOrder paidOrder = createPaidOrder(new BigDecimal("40000"));
        when(paymentOrderRepository.findByMerchantIdAndStatusAndPaidAtBetween(
                eq(MERCHANT_ID), eq(PaymentOrder.OrderStatus.PAID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(paidOrder));

        // 新交易 20000，累计将达 60000 > 50000
        LimitCheckResult result = limitCheckService.checkMonthlyAmountLimit(MERCHANT_ID, new BigDecimal("20000"));

        assertFalse(result.isPassed());
        assertEquals("MONTHLY_AMOUNT", result.getViolationType());
    }

    @Test
    @DisplayName("checkMonthlyAmountLimit：累计 + 新金额 <= monthlyMax -> passed")
    void checkMonthlyAmountLimitWithinRange() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setMonthlyAccumulatedMaxAmount(new BigDecimal("50000"));

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        // 当月已有累计 30000
        PaymentOrder paidOrder = createPaidOrder(new BigDecimal("30000"));
        when(paymentOrderRepository.findByMerchantIdAndStatusAndPaidAtBetween(
                eq(MERCHANT_ID), eq(PaymentOrder.OrderStatus.PAID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(paidOrder));

        // 新交易 20000，累计将达 50000 = 50000，不超限
        LimitCheckResult result = limitCheckService.checkMonthlyAmountLimit(MERCHANT_ID, new BigDecimal("20000"));

        assertTrue(result.isPassed());
    }

    // ==================== checkMonthlyCountLimit ====================

    @Test
    @DisplayName("checkMonthlyCountLimit：笔数 >= monthlyMaxCount -> failed(MONTHLY_COUNT)")
    void checkMonthlyCountLimitExceeds() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setMonthlyMaxTransactionCount(50);

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        // 当月已有 50 笔
        List<PaymentOrder> paidOrders = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            paidOrders.add(createPaidOrder(new BigDecimal("100")));
        }
        when(paymentOrderRepository.findByMerchantIdAndStatusAndPaidAtBetween(
                eq(MERCHANT_ID), eq(PaymentOrder.OrderStatus.PAID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(paidOrders);

        LimitCheckResult result = limitCheckService.checkMonthlyCountLimit(MERCHANT_ID);

        assertFalse(result.isPassed());
        assertEquals("MONTHLY_COUNT", result.getViolationType());
    }

    @Test
    @DisplayName("checkMonthlyCountLimit：笔数 < monthlyMaxCount -> passed")
    void checkMonthlyCountLimitWithinRange() {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setActive(true);
        config.setMonthlyMaxTransactionCount(100);

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        // 当月已有 10 笔
        List<PaymentOrder> paidOrders = List.of(
                createPaidOrder(new BigDecimal("100")),
                createPaidOrder(new BigDecimal("200")));
        when(paymentOrderRepository.findByMerchantIdAndStatusAndPaidAtBetween(
                eq(MERCHANT_ID), eq(PaymentOrder.OrderStatus.PAID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(paidOrders);

        LimitCheckResult result = limitCheckService.checkMonthlyCountLimit(MERCHANT_ID);

        assertTrue(result.isPassed());
    }

    // ==================== createOrUpdateConfig ====================

    @Test
    @DisplayName("createOrUpdateConfig：创建新配置 — 成功")
    void createOrUpdateConfigNewConfigSuccess() {
        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());
        when(limitConfigRepository.save(any(MerchantLimitConfig.class))).thenAnswer(inv -> {
            MerchantLimitConfig config = inv.getArgument(0);
            config.setId(1L);
            return config;
        });

        MerchantLimitConfig config = limitCheckService.createOrUpdateConfig(
                MERCHANT_ID,
                new BigDecimal("1"),
                new BigDecimal("100000"),
                new BigDecimal("500000"),
                new BigDecimal("5000000"),
                100,
                2000);

        assertNotNull(config);
        assertEquals(MERCHANT_ID, config.getMerchantId());
        assertEquals(new BigDecimal("1"), config.getSingleTransactionMinAmount());
        assertEquals(new BigDecimal("100000"), config.getSingleTransactionMaxAmount());
        assertEquals(new BigDecimal("500000"), config.getDailyAccumulatedMaxAmount());
        assertEquals(new BigDecimal("5000000"), config.getMonthlyAccumulatedMaxAmount());
        assertEquals(100, config.getDailyMaxTransactionCount());
        assertEquals(2000, config.getMonthlyMaxTransactionCount());
        assertTrue(config.isActive());
    }

    @Test
    @DisplayName("createOrUpdateConfig：更新已有配置 — 成功")
    void createOrUpdateConfigUpdateExistingSuccess() {
        MerchantLimitConfig existing = new MerchantLimitConfig();
        existing.setId(5L);
        existing.setMerchantId(MERCHANT_ID);
        existing.setActive(true);
        existing.setSingleTransactionMinAmount(new BigDecimal("1"));
        existing.setSingleTransactionMaxAmount(new BigDecimal("1000"));

        when(limitConfigRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(existing));
        when(limitConfigRepository.save(any(MerchantLimitConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        MerchantLimitConfig config = limitCheckService.createOrUpdateConfig(
                MERCHANT_ID,
                new BigDecimal("5"),
                new BigDecimal("50000"),
                new BigDecimal("200000"),
                new BigDecimal("2000000"),
                50,
                1000);

        assertNotNull(config);
        assertEquals(5L, config.getId());
        assertEquals(MERCHANT_ID, config.getMerchantId());
        assertEquals(new BigDecimal("5"), config.getSingleTransactionMinAmount());
        assertEquals(new BigDecimal("50000"), config.getSingleTransactionMaxAmount());
        assertEquals(new BigDecimal("200000"), config.getDailyAccumulatedMaxAmount());
        assertEquals(50, config.getDailyMaxTransactionCount());
        assertEquals(1000, config.getMonthlyMaxTransactionCount());
    }

    @Test
    @DisplayName("createOrUpdateConfig：singleMin > singleMax -> 抛出异常")
    void createOrUpdateConfigSingleMinGreaterThanSingleMax() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                limitCheckService.createOrUpdateConfig(
                        MERCHANT_ID,
                        new BigDecimal("1000"),
                        new BigDecimal("100"),
                        null, null, null, null));

        assertTrue(ex.getMessage().contains("must be <="));
    }

    // ==================== getDailyAccumulatedAmount ====================

    @Test
    @DisplayName("getDailyAccumulatedAmount：正确计算当天累计")
    void getDailyAccumulatedAmountCorrect() {
        PaymentOrder order1 = createPaidOrder(new BigDecimal("1000"));
        PaymentOrder order2 = createPaidOrder(new BigDecimal("2000"));
        PaymentOrder order3 = createPaidOrder(new BigDecimal("3000"));

        when(paymentOrderRepository.findByMerchantIdAndStatusAndPaidAtBetween(
                eq(MERCHANT_ID), eq(PaymentOrder.OrderStatus.PAID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(order1, order2, order3));

        BigDecimal total = limitCheckService.getDailyAccumulatedAmount(MERCHANT_ID);

        assertEquals(new BigDecimal("6000"), total);
    }

    // ==================== getDailyTransactionCount ====================

    @Test
    @DisplayName("getDailyTransactionCount：正确计算当天笔数")
    void getDailyTransactionCountCorrect() {
        PaymentOrder order1 = createPaidOrder(new BigDecimal("100"));
        PaymentOrder order2 = createPaidOrder(new BigDecimal("200"));
        PaymentOrder order3 = createPaidOrder(new BigDecimal("300"));

        when(paymentOrderRepository.findByMerchantIdAndStatusAndPaidAtBetween(
                eq(MERCHANT_ID), eq(PaymentOrder.OrderStatus.PAID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(order1, order2, order3));

        int count = limitCheckService.getDailyTransactionCount(MERCHANT_ID);

        assertEquals(3, count);
    }

    // --- Helper ---

    private PaymentOrder createPaidOrder(BigDecimal amount) {
        PaymentOrder order = new PaymentOrder();
        order.setAmount(amount);
        order.setStatus(PaymentOrder.OrderStatus.PAID);
        order.setMerchantId(MERCHANT_ID);
        order.setPaidAt(LocalDateTime.now());
        return order;
    }
}