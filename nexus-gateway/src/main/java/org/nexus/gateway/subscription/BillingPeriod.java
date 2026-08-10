package org.nexus.gateway.subscription;

import java.time.LocalDateTime;

/**
 * 计费周期枚举（P4-T8 订阅与循环计费引擎）。
 *
 * <p>支持 DAILY / WEEKLY / MONTHLY / YEARLY 四种周期，并提供计算下一个
 * 周期日期的方法。MONTHLY/YEARLY 采用 {@link LocalDateTime#plusMonths(long)}
 * / {@link LocalDateTime#plusYears(long)}，由 JSR-310 处理月末溢出
 * （如 1月31日 + 1 月 = 2月28日）。</p>
 */
public enum BillingPeriod {

    /** 每日扣款。 */
    DAILY(1),
    /** 每周扣款。 */
    WEEKLY(7),
    /** 每月扣款。 */
    MONTHLY(30),
    /** 每年扣款。 */
    YEARLY(365);

    /** 估算天数，仅用于按比例计算与日志展示，不作为扣款调度依据。 */
    private final int approxDays;

    BillingPeriod(int approxDays) {
        this.approxDays = approxDays;
    }

    /**
     * 计算从 {@code start} 开始的下一个周期结束时间。
     *
     * @param start 当前周期开始时间
     * @return 下一个周期开始时间（即当前周期结束时间，含）
     */
    public LocalDateTime nextPeriodStart(LocalDateTime start) {
        if (start == null) {
            throw new IllegalArgumentException("start must not be null");
        }
        return switch (this) {
            case DAILY -> start.plusDays(1);
            case WEEKLY -> start.plusWeeks(1);
            case MONTHLY -> start.plusMonths(1);
            case YEARLY -> start.plusYears(1);
        };
    }

    /**
     * 估算周期天数（用于按比例计算与日志展示）。
     *
     * <p>注意：MONTHLY/YEARLY 返回固定 30/365 天作为估算值，按比例计算
     * 实际使用 {@link java.time.temporal.ChronoUnit#DAYS} 计算精确天数。</p>
     *
     * @return 估算天数
     */
    public int approxDays() {
        return approxDays;
    }
}