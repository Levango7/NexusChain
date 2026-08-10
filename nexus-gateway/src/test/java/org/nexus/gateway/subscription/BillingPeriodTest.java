package org.nexus.gateway.subscription;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BillingPeriod} 枚举测试（P4-T8）。
 */
class BillingPeriodTest {

    @Test
    @DisplayName("DAILY: nextPeriodStart = start + 1 day")
    void daily_nextPeriodStart() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 10, 30);
        LocalDateTime next = BillingPeriod.DAILY.nextPeriodStart(start);
        assertEquals(LocalDateTime.of(2026, 1, 2, 10, 30), next);
    }

    @Test
    @DisplayName("WEEKLY: nextPeriodStart = start + 7 days")
    void weekly_nextPeriodStart() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime next = BillingPeriod.WEEKLY.nextPeriodStart(start);
        assertEquals(LocalDateTime.of(2026, 1, 8, 0, 0), next);
    }

    @Test
    @DisplayName("MONTHLY: nextPeriodStart = start + 1 month（月末溢出处理）")
    void monthly_nextPeriodStart() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 31, 0, 0);
        LocalDateTime next = BillingPeriod.MONTHLY.nextPeriodStart(start);
        // 1月31日 + 1 月 = 2月28日（2026 非闰年）
        assertEquals(LocalDateTime.of(2026, 2, 28, 0, 0), next);
    }

    @Test
    @DisplayName("YEARLY: nextPeriodStart = start + 1 year")
    void yearly_nextPeriodStart() {
        LocalDateTime start = LocalDateTime.of(2026, 6, 15, 12, 0);
        LocalDateTime next = BillingPeriod.YEARLY.nextPeriodStart(start);
        assertEquals(LocalDateTime.of(2027, 6, 15, 12, 0), next);
    }

    @Test
    @DisplayName("nextPeriodStart: null -> 抛异常")
    void nextPeriodStart_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> BillingPeriod.DAILY.nextPeriodStart(null));
    }

    @Test
    @DisplayName("approxDays: DAILY=1, WEEKLY=7, MONTHLY=30, YEARLY=365")
    void approxDays() {
        assertEquals(1, BillingPeriod.DAILY.approxDays());
        assertEquals(7, BillingPeriod.WEEKLY.approxDays());
        assertEquals(30, BillingPeriod.MONTHLY.approxDays());
        assertEquals(365, BillingPeriod.YEARLY.approxDays());
    }
}