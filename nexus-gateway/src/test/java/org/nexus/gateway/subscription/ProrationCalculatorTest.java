package org.nexus.gateway.subscription;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProrationCalculator} 单元测试（P4-T8）。
 *
 * <p>覆盖按比例差价计算的各种场景：周期中点升级、周期开始升级、
 * 周期结束升级、零差价、降级（负差价）、参数校验。</p>
 */
class ProrationCalculatorTest {

    private final ProrationCalculator calculator = new ProrationCalculator();

    private SubscriptionPlan plan(String planId, BigDecimal amount) {
        SubscriptionPlan p = new SubscriptionPlan();
        p.setPlanId(planId);
        p.setAmount(amount);
        return p;
    }

    @Test
    @DisplayName("calculateProration: 周期中点升级 -> 差价为新旧差额的一半")
    void calculateProration_midCycle() {
        // 30 天周期，第 15 天升级，旧计划 100，新计划 200
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = start.plusDays(30);
        LocalDateTime upgrade = start.plusDays(15);

        BigDecimal proration = calculator.calculateProration(
                plan("old", new BigDecimal("100")),
                plan("new", new BigDecimal("200")),
                start, end, upgrade);

        // 剩余 15 天 / 总 30 天 = 0.5
        // 差价 = (200 - 100) * 0.5 = 50
        assertEquals(new BigDecimal("50.000000000000000000"), proration);
    }

    @Test
    @DisplayName("calculateProration: 周期开始升级 -> 全额差价")
    void calculateProration_startOfCycle() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = start.plusDays(30);
        LocalDateTime upgrade = start;

        BigDecimal proration = calculator.calculateProration(
                plan("old", new BigDecimal("100")),
                plan("new", new BigDecimal("200")),
                start, end, upgrade);

        // 剩余 30 天 / 总 30 天 = 1.0
        // 差价 = (200 - 100) * 1.0 = 100
        assertEquals(new BigDecimal("100.000000000000000000"), proration);
    }

    @Test
    @DisplayName("calculateProration: 周期结束升级 -> 零差价")
    void calculateProration_endOfCycle() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = start.plusDays(30);
        LocalDateTime upgrade = end;

        BigDecimal proration = calculator.calculateProration(
                plan("old", new BigDecimal("100")),
                plan("new", new BigDecimal("200")),
                start, end, upgrade);

        // 剩余 0 天 -> 差价 0
        assertEquals(0, proration.compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("calculateProration: 同金额计划 -> 零差价")
    void calculateProration_sameAmount() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = start.plusDays(30);
        LocalDateTime upgrade = start.plusDays(10);

        BigDecimal proration = calculator.calculateProration(
                plan("old", new BigDecimal("100")),
                plan("new", new BigDecimal("100")),
                start, end, upgrade);

        assertEquals(0, proration.compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("calculateProration: 降级 -> 负差价（应退款）")
    void calculateProration_downgrade_negative() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = start.plusDays(30);
        LocalDateTime upgrade = start.plusDays(15);

        BigDecimal proration = calculator.calculateProration(
                plan("old", new BigDecimal("200")),
                plan("new", new BigDecimal("100")),
                start, end, upgrade);

        // 差价 = (100 - 200) * 0.5 = -50
        assertTrue(proration.signum() < 0, "Downgrade proration should be negative");
        assertEquals(new BigDecimal("-50.000000000000000000"), proration);
    }

    @Test
    @DisplayName("calculateProration: 直接传金额重载")
    void calculateProration_amountsOverload() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = start.plusDays(10);
        LocalDateTime upgrade = start.plusDays(5);

        BigDecimal proration = calculator.calculateProration(
                new BigDecimal("100"), new BigDecimal("300"),
                start, end, upgrade);

        // 剩余 5/10 = 0.5, 差价 = (300-100)*0.5 = 100
        assertEquals(new BigDecimal("100.000000000000000000"), proration);
    }

    @Test
    @DisplayName("calculateProration: 周期总天数为 0 -> 返回 0（防御性）")
    void calculateProration_zeroPeriodDays() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = start; // 0 天周期
        LocalDateTime upgrade = start;

        BigDecimal proration = calculator.calculateProration(
                plan("old", new BigDecimal("100")),
                plan("new", new BigDecimal("200")),
                start, end, upgrade);

        assertEquals(0, proration.compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("calculateProration: upgradeDate 早于 periodStart -> clamp 到 start（全额差价）")
    void calculateProration_upgradeBeforeStart_clampedToStart() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 10, 0, 0);
        LocalDateTime end = start.plusDays(30);
        LocalDateTime upgrade = start.minusDays(1);

        // clamp 后 upgrade = start，剩余 30 天，差价 = (200-100) * 1.0 = 100
        BigDecimal proration = calculator.calculateProration(
                plan("old", new BigDecimal("100")),
                plan("new", new BigDecimal("200")),
                start, end, upgrade);

        assertEquals(new BigDecimal("100.000000000000000000"), proration);
    }

    @Test
    @DisplayName("calculateProration: upgradeDate 晚于 periodEnd -> clamp 到 end（零差价）")
    void calculateProration_upgradeAfterEnd_clampedToEnd() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = start.plusDays(30);
        LocalDateTime upgrade = end.plusDays(1);

        BigDecimal proration = calculator.calculateProration(
                plan("old", new BigDecimal("100")),
                plan("new", new BigDecimal("200")),
                start, end, upgrade);

        assertEquals(0, proration.compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("calculateProration: null 参数 -> 抛异常")
    void calculateProration_nullArgs_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                calculator.calculateProration(null, plan("new", BigDecimal.ONE),
                        LocalDateTime.now(), LocalDateTime.now().plusDays(1), LocalDateTime.now()));
        assertThrows(IllegalArgumentException.class, () ->
                calculator.calculateProration(plan("old", BigDecimal.ONE), plan("new", BigDecimal.ONE),
                        null, LocalDateTime.now().plusDays(1), LocalDateTime.now()));
    }

    @Test
    @DisplayName("calculateUpgradeCharge: 新计划金额 + 按比例差价")
    void calculateUpgradeCharge() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = start.plusDays(30);
        LocalDateTime upgrade = start.plusDays(15);

        // 旧 100，新 200，中点升级：proration = 50，upgradeCharge = 200 + 50 = 250
        BigDecimal charge = calculator.calculateUpgradeCharge(
                plan("old", new BigDecimal("100")),
                plan("new", new BigDecimal("200")),
                start, end, upgrade);

        assertEquals(new BigDecimal("250.000000000000000000"), charge);
    }

    @Test
    @DisplayName("calculateProration: 7 天周期，第 3 天升级")
    void calculateProration_weeklyCycle() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = start.plusDays(7);
        LocalDateTime upgrade = start.plusDays(3);

        // 剩余 4/7, 旧 1000 新 2000, 差价 = (2000-1000) * 4/7 ≈ 571.428...
        BigDecimal proration = calculator.calculateProration(
                plan("old", new BigDecimal("1000")),
                plan("new", new BigDecimal("2000")),
                start, end, upgrade);

        assertTrue(proration.signum() > 0);
        // 验证近似值：1000 * 4/7 ≈ 571.43
        assertTrue(proration.doubleValue() > 571 && proration.doubleValue() < 572,
                "proration should be ~571.43, got " + proration);
    }
}