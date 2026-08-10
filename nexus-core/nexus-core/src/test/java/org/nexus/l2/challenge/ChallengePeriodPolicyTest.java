package org.nexus.l2.challenge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChallengePeriodPolicy} 单元测试。
 */
class ChallengePeriodPolicyTest {

    private ChallengePeriodPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ChallengePeriodPolicy();
    }

    @Test
    void defaultConstructorSetsDefaults() {
        assertEquals(Duration.ofDays(7), policy.getBaseWindow());
        assertEquals(new BigDecimal("1000000"), policy.getHighValueThreshold());
        assertEquals(Duration.ofDays(1), policy.getExtensionPerTier());
        assertEquals(Duration.ofDays(30), policy.getMaxExtension());
        assertEquals(Duration.ofDays(7), policy.getSuspiciousExtension());
        assertEquals(Duration.ofDays(14), policy.getMaxSuspiciousExtension());
    }

    @Test
    void computeChallengePeriodBaseOnly() {
        Duration d = policy.computeChallengePeriod(1, BigDecimal.ZERO);
        assertEquals(Duration.ofDays(7), d);
    }

    @Test
    void computeChallengePeriodNullValue() {
        Duration d = policy.computeChallengePeriod(1, (BigDecimal) null);
        assertEquals(Duration.ofDays(7), d);
    }

    @Test
    void computeChallengePeriodHighValue() {
        // 金额 1000万 = 10 * threshold → tier=1 → +1 day
        Duration d = policy.computeChallengePeriod(1, new BigDecimal("10000000"));
        assertEquals(Duration.ofDays(8), d);
    }

    @Test
    void computeChallengePeriodVeryHighValue() {
        // 金额 1亿 = 100 * threshold → tier=2 → +2 days
        Duration d = policy.computeChallengePeriod(1, new BigDecimal("100000000"));
        assertEquals(Duration.ofDays(9), d);
    }

    @Test
    void computeChallengePeriodWithBigInteger() {
        Duration d = policy.computeChallengePeriod(1, BigInteger.valueOf(100));
        assertEquals(Duration.ofDays(7), d);
    }

    @Test
    void computeChallengePeriodNullBigInteger() {
        Duration d = policy.computeChallengePeriod(1, (BigInteger) null);
        assertEquals(Duration.ofDays(7), d);
    }

    @Test
    void computeHighValueExtensionBelowThreshold() {
        assertEquals(Duration.ZERO, policy.computeHighValueExtension(new BigDecimal("500000")));
    }

    @Test
    void computeHighValueExtensionNull() {
        assertEquals(Duration.ZERO, policy.computeHighValueExtension(null));
    }

    @Test
    void computeHighValueExtensionNegative() {
        assertEquals(Duration.ZERO, policy.computeHighValueExtension(new BigDecimal("-1")));
    }

    @Test
    void computeHighValueExtensionCappedAtMax() {
        // 极高金额 → tier 很大，但被 maxExtension=30 天封顶
        Duration ext = policy.computeHighValueExtension(new BigDecimal("1000000000000000000"));
        assertTrue(ext.compareTo(Duration.ofDays(30)) <= 0);
    }

    @Test
    void reportSuspiciousActivityExtendsPeriod() {
        policy.reportSuspiciousActivity(1, "double sign");
        Duration d = policy.computeChallengePeriod(1, BigDecimal.ZERO);
        // base 7 + suspicious 7 = 14
        assertEquals(Duration.ofDays(14), d);
    }

    @Test
    void reportSuspiciousActivityCappedAtMax() {
        policy.reportSuspiciousActivity(1, "a");
        policy.reportSuspiciousActivity(1, "b");
        policy.reportSuspiciousActivity(1, "c");
        // 3 * 7 = 21, capped at 14
        assertEquals(Duration.ofDays(14), policy.getSuspiciousExtension(1));
    }

    @Test
    void getSuspiciousExtensionNoReport() {
        assertEquals(Duration.ZERO, policy.getSuspiciousExtension(999));
    }

    @Test
    void getSuspiciousReasons() {
        policy.reportSuspiciousActivity(1, "reason1");
        policy.reportSuspiciousActivity(1, "reason2");
        List<String> reasons = policy.getSuspiciousReasons(1);
        assertEquals(2, reasons.size());
        assertTrue(reasons.contains("reason1"));
        assertTrue(reasons.contains("reason2"));
    }

    @Test
    void getSuspiciousReasonsEmpty() {
        assertTrue(policy.getSuspiciousReasons(999).isEmpty());
    }

    @Test
    void overrideChallengePeriod() {
        policy.overrideChallengePeriod(1, Duration.ofDays(100));
        Duration d = policy.computeChallengePeriod(1, new BigDecimal("10000000"));
        assertEquals(Duration.ofDays(100), d);
    }

    @Test
    void overrideChallengePeriodNullIsNoOp() {
        policy.overrideChallengePeriod(1, null);
        Duration d = policy.computeChallengePeriod(1, BigDecimal.ZERO);
        assertEquals(Duration.ofDays(7), d);
    }

    @Test
    void overrideChallengePeriodZeroIsNoOp() {
        policy.overrideChallengePeriod(1, Duration.ZERO);
        Duration d = policy.computeChallengePeriod(1, BigDecimal.ZERO);
        assertEquals(Duration.ofDays(7), d);
    }

    @Test
    void isChallengeWindowOverNullSubmitTimeReturnsFalse() {
        assertFalse(policy.isChallengeWindowOver(1, null, BigDecimal.ZERO));
    }

    @Test
    void isChallengeWindowOverPastSubmitTime() {
        Instant past = Instant.now().minus(Duration.ofDays(10));
        assertTrue(policy.isChallengeWindowOver(1, past, BigDecimal.ZERO));
    }

    @Test
    void isChallengeWindowOverRecentSubmitTime() {
        Instant recent = Instant.now();
        assertFalse(policy.isChallengeWindowOver(1, recent, BigDecimal.ZERO));
    }

    @Test
    void remainingChallengeTimeNullSubmitTimeReturnsFullWindow() {
        Duration remaining = policy.remainingChallengeTime(1, null, BigDecimal.ZERO);
        assertEquals(Duration.ofDays(7), remaining);
    }

    @Test
    void remainingChallengeTimePastDeadlineReturnsZero() {
        Instant past = Instant.now().minus(Duration.ofDays(100));
        assertEquals(Duration.ZERO, policy.remainingChallengeTime(1, past, BigDecimal.ZERO));
    }

    @Test
    void remainingChallengeTimePositive() {
        Instant recent = Instant.now();
        Duration remaining = policy.remainingChallengeTime(1, recent, BigDecimal.ZERO);
        assertTrue(remaining.toDays() >= 6); // ~7 days minus a tiny bit
    }

    @Test
    void clearRemovesAllState() {
        policy.reportSuspiciousActivity(1, "reason");
        policy.overrideChallengePeriod(1, Duration.ofDays(100));
        policy.clear(1);
        assertEquals(Duration.ZERO, policy.getSuspiciousExtension(1));
        assertTrue(policy.getSuspiciousReasons(1).isEmpty());
        Duration d = policy.computeChallengePeriod(1, BigDecimal.ZERO);
        assertEquals(Duration.ofDays(7), d);
    }
}