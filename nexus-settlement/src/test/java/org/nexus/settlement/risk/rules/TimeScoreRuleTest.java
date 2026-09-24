package org.nexus.settlement.risk.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.risk.RiskTransaction;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TimeScoreRule} 单元测试。
 */
class TimeScoreRuleTest {

    private TimeScoreRule rule;

    @BeforeEach
    void setUp() {
        rule = new TimeScoreRule();
        // 使用 UTC 时区确保测试可重复
        rule.setZoneId(ZoneId.of("UTC"));
    }

    @Test
    void getRuleId_shouldReturnTimeScore() {
        assertEquals("TIME_SCORE", rule.getRuleId());
    }

    @Test
    void getWeight_shouldReturn3() {
        assertEquals(3, rule.getWeight());
    }

    @Test
    void score_nullTransaction_shouldReturn0() {
        assertEquals(0, rule.score(null));
    }

    @Test
    void score_nonRiskTransaction_shouldReturn0() {
        assertEquals(0, rule.score("not a transaction"));
    }

    @Test
    void score_midnightTransaction_shouldReturn70() {
        // 构造一个 UTC 凌晨 2:00 的时间戳
        ZonedDateTime midnight = ZonedDateTime.of(2026, 1, 1, 2, 0, 0, 0, ZoneId.of("UTC"));
        RiskTransaction tx = new RiskTransaction();
        tx.setTimestamp(midnight.toInstant());
        assertEquals(70, rule.score(tx));
    }

    @Test
    void score_businessHoursTransaction_shouldReturn0() {
        // 构造一个 UTC 上午 10:00 的时间戳
        ZonedDateTime business = ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneId.of("UTC"));
        RiskTransaction tx = new RiskTransaction();
        tx.setTimestamp(business.toInstant());
        assertEquals(0, rule.score(tx));
    }

    @Test
    void score_nonBusinessHoursTransaction_shouldReturn40() {
        // 构造一个 UTC 晚上 20:00 的时间戳
        ZonedDateTime evening = ZonedDateTime.of(2026, 1, 1, 20, 0, 0, 0, ZoneId.of("UTC"));
        RiskTransaction tx = new RiskTransaction();
        tx.setTimestamp(evening.toInstant());
        assertEquals(40, rule.score(tx));
    }

    @Test
    void score_nullTimestamp_shouldUseCurrentTime() {
        RiskTransaction tx = new RiskTransaction();
        tx.setTimestamp(null);
        // 不验证具体评分（取决于当前时间），只确保不抛异常
        int score = rule.score(tx);
        assertTrue(score >= 0 && score <= 100);
    }

    @Test
    void check_midnightTransaction_shouldReturnFalse() {
        // score=70 < 80，所以 check 返回 false
        ZonedDateTime midnight = ZonedDateTime.of(2026, 1, 1, 2, 0, 0, 0, ZoneId.of("UTC"));
        RiskTransaction tx = new RiskTransaction();
        tx.setTimestamp(midnight.toInstant());
        assertFalse(rule.check(tx));
    }

    @Test
    void zoneIdManagement_shouldWork() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        rule.setZoneId(zone);
        assertEquals(zone, rule.getZoneId());
    }
}