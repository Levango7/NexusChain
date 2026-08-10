package org.nexus.governance.delegation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.pos.StakingService;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link VotingPowerCalculator} 单元测试。
 */
class VotingPowerCalculatorTest {

    private VotingPowerCalculator calc;
    private StakingService staking;
    private DelegationService delegation;

    @BeforeEach
    void setUp() throws Exception {
        calc = new VotingPowerCalculator();
        staking = mock(StakingService.class);
        delegation = mock(DelegationService.class);
        inject(calc, "stakingService", staking);
        inject(calc, "delegationService", delegation);
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void calculateNullVoterReturnsZero() {
        assertEquals(BigDecimal.ZERO, calc.calculateVotingPower(null, Instant.now()));
    }

    @Test
    void calculateNullNowReturnsZero() {
        assertEquals(BigDecimal.ZERO, calc.calculateVotingPower("v", null));
    }

    @Test
    void calculateReturnsSelfStakeWhenNoDelegationNoLock() {
        when(staking.getStake("v")).thenReturn(BigDecimal.valueOf(100));
        when(delegation.getDelegators("v")).thenReturn(Collections.emptySet());
        BigDecimal power = calc.calculateVotingPower("v", Instant.now());
        assertEquals(BigDecimal.valueOf(100), power);
    }

    @Test
    void calculateIncludesDelegatedStake() {
        when(staking.getStake("v")).thenReturn(BigDecimal.valueOf(50));
        when(staking.getStake("d1")).thenReturn(BigDecimal.valueOf(30));
        when(staking.getStake("d2")).thenReturn(BigDecimal.valueOf(20));
        Set<String> delegators = new HashSet<>();
        delegators.add("d1");
        delegators.add("d2");
        when(delegation.getDelegators("v")).thenReturn(delegators);
        BigDecimal power = calc.calculateVotingPower("v", Instant.now());
        // 50 + 30 + 20 = 100
        assertEquals(BigDecimal.valueOf(100), power);
    }

    @Test
    void calculateWithNegativeStakeTreatedAsZero() {
        when(staking.getStake("v")).thenReturn(new BigDecimal("-10"));
        when(delegation.getDelegators("v")).thenReturn(Collections.emptySet());
        BigDecimal power = calc.calculateVotingPower("v", Instant.now());
        assertEquals(BigDecimal.ZERO, power);
    }

    @Test
    void lockValidRegistersLock() {
        boolean ok = calc.lock("v", BigDecimal.valueOf(100),
                Instant.now(), Instant.now().plus(Duration.ofDays(30)));
        assertTrue(ok);
        assertEquals(1, calc.getLocks("v").size());
    }

    @Test
    void lockNullVoterReturnsFalse() {
        assertFalse(calc.lock(null, BigDecimal.ONE, Instant.now(), Instant.now().plusSeconds(1)));
    }

    @Test
    void lockInvalidAmountReturnsFalse() {
        assertFalse(calc.lock("v", BigDecimal.ZERO, Instant.now(), Instant.now().plusSeconds(1)));
        assertEquals(0, calc.getLocks("v").size());
    }

    @Test
    void lockInvalidTimeRangeReturnsFalse() {
        Instant now = Instant.now();
        assertFalse(calc.lock("v", BigDecimal.ONE, now, now));
    }

    @Test
    void getLocksNullReturnsEmpty() {
        assertTrue(calc.getLocks(null).isEmpty());
    }

    @Test
    void getLocksUnknownReturnsEmpty() {
        assertTrue(calc.getLocks("unknown").isEmpty());
    }

    @Test
    void getLocksReturnsUnmodifiableList() {
        calc.lock("v", BigDecimal.ONE, Instant.now(), Instant.now().plusSeconds(60));
        assertThrows(UnsupportedOperationException.class, () ->
                calc.getLocks("v").add(null));
    }

    @Test
    void lockBonusAddedToVotingPower() {
        when(staking.getStake("v")).thenReturn(BigDecimal.ZERO);
        when(delegation.getDelegators("v")).thenReturn(Collections.emptySet());
        // 锁仓 100，1 天
        calc.lock("v", BigDecimal.valueOf(100),
                Instant.now(), Instant.now().plus(Duration.ofDays(1)));
        BigDecimal power = calc.calculateVotingPower("v", Instant.now());
        // power = 0 + lockBonus > 0
        assertTrue(power.signum() > 0);
    }

    @Test
    void maturedLockContributesZero() {
        when(staking.getStake("v")).thenReturn(BigDecimal.ZERO);
        when(delegation.getDelegators("v")).thenReturn(Collections.emptySet());
        Instant past = Instant.now().minus(Duration.ofDays(2));
        Instant recent = Instant.now().minus(Duration.ofDays(1));
        calc.lock("v", BigDecimal.valueOf(100), past, recent);
        // 锁仓已到期
        BigDecimal power = calc.calculateVotingPower("v", Instant.now());
        assertEquals(BigDecimal.ZERO, power);
    }

    @Test
    void getLockHoldersReturnsVotersWithLocks() {
        calc.lock("v1", BigDecimal.ONE, Instant.now(), Instant.now().plusSeconds(60));
        calc.lock("v2", BigDecimal.ONE, Instant.now(), Instant.now().plusSeconds(60));
        assertEquals(2, calc.getLockHolders().size());
    }

    @Test
    void lockMultiplierZeroDurationReturnsOne() {
        assertEquals(BigDecimal.ONE, calc.lockMultiplier(Duration.ZERO));
    }

    @Test
    void lockMultiplierNullReturnsOne() {
        assertEquals(BigDecimal.ONE, calc.lockMultiplier(null));
    }

    @Test
    void lockMultiplierNegativeReturnsOne() {
        assertEquals(BigDecimal.ONE, calc.lockMultiplier(Duration.ofSeconds(-1)));
    }

    @Test
    void lockMultiplierFullLockReturnsFour() {
        BigDecimal m = calc.lockMultiplier(Duration.ofDays(365));
        // 应接近 4
        assertTrue(m.compareTo(new BigDecimal("3.99")) > 0);
        assertTrue(m.compareTo(new BigDecimal("4.01")) < 0);
    }

    @Test
    void lockMultiplierCappedAtFour() {
        BigDecimal m = calc.lockMultiplier(Duration.ofDays(1000));
        assertTrue(m.compareTo(new BigDecimal("3.99")) > 0);
        assertTrue(m.compareTo(new BigDecimal("4.01")) < 0);
    }
}