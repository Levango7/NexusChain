package org.nexus.gateway.limit;

import java.math.BigDecimal;

/**
 * 限额检查结果值对象（不可变）。
 *
 * <p>不是 JPA 实体，仅用于传递限额检查的结果信息。</p>
 *
 * <p>违反类型枚举值：</p>
 * <ul>
 *   <li>{@code SINGLE_MIN} — 单笔金额低于最小限制</li>
 *   <li>{@code SINGLE_MAX} — 单笔金额超过最大限制</li>
 *   <li>{@code DAILY_AMOUNT} — 日累计金额超过限制</li>
 *   <li>{@code DAILY_COUNT} — 日交易笔数超过限制</li>
 *   <li>{@code MONTHLY_AMOUNT} — 月累计金额超过限制</li>
 *   <li>{@code MONTHLY_COUNT} — 月交易笔数超过限制</li>
 * </ul>
 */
public final class LimitCheckResult {

    private final boolean passed;
    private final String violationType;
    private final String violationMessage;
    private final BigDecimal currentAccumulatedAmount;
    private final int currentTransactionCount;
    private final BigDecimal limitValue;

    private LimitCheckResult(boolean passed, String violationType, String violationMessage,
                             BigDecimal currentAccumulatedAmount, int currentTransactionCount,
                             BigDecimal limitValue) {
        this.passed = passed;
        this.violationType = violationType;
        this.violationMessage = violationMessage;
        this.currentAccumulatedAmount = currentAccumulatedAmount;
        this.currentTransactionCount = currentTransactionCount;
        this.limitValue = limitValue;
    }

    /**
     * 检查通过。
     */
    public static LimitCheckResult passed() {
        return new LimitCheckResult(true, null, null, null, 0, null);
    }

    /**
     * 检查失败。
     *
     * @param violationType       违反的限额类型
     * @param message             详细违反信息
     * @param currentAmount       当前累计金额（可为 null）
     * @param currentCount        当前交易笔数
     * @param limitValue          被违反的限额值（可为 null）
     */
    public static LimitCheckResult failed(String violationType, String message,
                                          BigDecimal currentAmount, int currentCount,
                                          BigDecimal limitValue) {
        return new LimitCheckResult(false, violationType, message, currentAmount, currentCount, limitValue);
    }

    public boolean isPassed() { return passed; }

    public String getViolationType() { return violationType; }

    public String getViolationMessage() { return violationMessage; }

    public BigDecimal getCurrentAccumulatedAmount() { return currentAccumulatedAmount; }

    public int getCurrentTransactionCount() { return currentTransactionCount; }

    public BigDecimal getLimitValue() { return limitValue; }
}