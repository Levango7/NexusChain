package org.nexus.gateway.subscription;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 按比例计算工具（P4-T8 订阅与循环计费引擎）。
 *
 * <p>用于订阅升级时计算当前周期剩余天数的差价。计算公式：</p>
 * <pre>
 *   剩余天数 = currentPeriodEnd - upgradeDate（按天取整）
 *   周期总天数 = currentPeriodEnd - currentPeriodStart（按天取整）
 *   旧计划剩余价值 = oldPlan.amount × (剩余天数 / 周期总天数)
 *   新计划剩余价值 = newPlan.amount × (剩余天数 / 周期总天数)
 *   按比例差价 = 新计划剩余价值 - 旧计划剩余价值
 * </pre>
 *
 * <p>差价为正表示客户需补缴，差价为负表示需退款（通常升级场景仅补缴）。
 * 周期总天数为 0 时返回 0（防御性，避免除零）。</p>
 */
@Component
public class ProrationCalculator {

    private static final int SCALE = 18;
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_UP);

    /**
     * 计算当前周期剩余天数的按比例差价。
     *
     * @param oldPlan            原计划
     * @param newPlan            新计划
     * @param currentPeriodStart 当前周期开始时间
     * @param currentPeriodEnd   当前周期结束时间
     * @param upgradeDate        升级时间（通常为 now）
     * @return 按比例差价（正数=补缴，负数=退款），保留 18 位小数
     */
    public BigDecimal calculateProration(SubscriptionPlan oldPlan, SubscriptionPlan newPlan,
                                         LocalDateTime currentPeriodStart,
                                         LocalDateTime currentPeriodEnd,
                                         LocalDateTime upgradeDate) {
        if (oldPlan == null || newPlan == null) {
            throw new IllegalArgumentException("plans must not be null");
        }
        if (currentPeriodStart == null || currentPeriodEnd == null || upgradeDate == null) {
            throw new IllegalArgumentException("dates must not be null");
        }
        // 边界 clamp：允许 upgradeDate 略早于 currentPeriodStart 或略晚于 currentPeriodEnd
        // （由时钟漂移导致），clamp 到有效窗口内
        LocalDateTime effectiveUpgrade = upgradeDate;
        if (effectiveUpgrade.isBefore(currentPeriodStart)) {
            effectiveUpgrade = currentPeriodStart;
        }
        if (effectiveUpgrade.isAfter(currentPeriodEnd)) {
            effectiveUpgrade = currentPeriodEnd;
        }

        long totalDays = ChronoUnit.DAYS.between(currentPeriodStart, currentPeriodEnd);
        if (totalDays <= 0) {
            return BigDecimal.ZERO;
        }

        long remainingDays = ChronoUnit.DAYS.between(effectiveUpgrade, currentPeriodEnd);
        if (remainingDays < 0) {
            remainingDays = 0;
        }

        // 比例 = remainingDays / totalDays
        BigDecimal ratio = BigDecimal.valueOf(remainingDays)
                .divide(BigDecimal.valueOf(totalDays), SCALE, RoundingMode.HALF_UP);

        BigDecimal oldRemainingValue = oldPlan.getAmount().multiply(ratio, MC);
        BigDecimal newRemainingValue = newPlan.getAmount().multiply(ratio, MC);

        return newRemainingValue.subtract(oldRemainingValue).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算当前周期剩余天数的按比例差价（直接传金额，不依赖计划实体）。
     *
     * <p>供测试与无计划实体的场景使用。</p>
     *
     * @param oldAmount          原计划金额
     * @param newAmount          新计划金额
     * @param currentPeriodStart 当前周期开始时间
     * @param currentPeriodEnd   当前周期结束时间
     * @param upgradeDate        升级时间
     * @return 按比例差价
     */
    public BigDecimal calculateProration(BigDecimal oldAmount, BigDecimal newAmount,
                                         LocalDateTime currentPeriodStart,
                                         LocalDateTime currentPeriodEnd,
                                         LocalDateTime upgradeDate) {
        if (oldAmount == null || newAmount == null) {
            throw new IllegalArgumentException("amounts must not be null");
        }
        if (currentPeriodStart == null || currentPeriodEnd == null || upgradeDate == null) {
            throw new IllegalArgumentException("dates must not be null");
        }
        // 边界 clamp：同上重载方法
        LocalDateTime effectiveUpgrade = upgradeDate;
        if (effectiveUpgrade.isBefore(currentPeriodStart)) {
            effectiveUpgrade = currentPeriodStart;
        }
        if (effectiveUpgrade.isAfter(currentPeriodEnd)) {
            effectiveUpgrade = currentPeriodEnd;
        }

        long totalDays = ChronoUnit.DAYS.between(currentPeriodStart, currentPeriodEnd);
        if (totalDays <= 0) {
            return BigDecimal.ZERO;
        }

        long remainingDays = ChronoUnit.DAYS.between(effectiveUpgrade, currentPeriodEnd);
        if (remainingDays < 0) {
            remainingDays = 0;
        }

        BigDecimal ratio = BigDecimal.valueOf(remainingDays)
                .divide(BigDecimal.valueOf(totalDays), SCALE, RoundingMode.HALF_UP);

        BigDecimal oldRemainingValue = oldAmount.multiply(ratio, MC);
        BigDecimal newRemainingValue = newAmount.multiply(ratio, MC);

        return newRemainingValue.subtract(oldRemainingValue).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算下次扣款金额（新计划金额 + 按比例差价）。
     *
     * <p>升级立即生效时，下次扣款金额 = 新计划周期金额 + 当前周期按比例差价。
     * 此方法便于调用方一次性获得补缴金额。</p>
     *
     * @return 新计划金额 + 按比例差价
     */
    public BigDecimal calculateUpgradeCharge(SubscriptionPlan oldPlan, SubscriptionPlan newPlan,
                                             LocalDateTime currentPeriodStart,
                                             LocalDateTime currentPeriodEnd,
                                             LocalDateTime upgradeDate) {
        BigDecimal proration = calculateProration(oldPlan, newPlan,
                currentPeriodStart, currentPeriodEnd, upgradeDate);
        return newPlan.getAmount().add(proration);
    }
}