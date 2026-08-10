package org.nexus.governance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GovernanceVotingService.VoteTally} 通过判定单元测试。
 */
class VoteTallyTest {

    private GovernanceVotingService.VoteTally tally(double yes, double no, double abstain) {
        GovernanceVotingService.VoteTally t = new GovernanceVotingService.VoteTally();
        // 通过反射注入 yes/no/abstain（包私有字段）
        try {
            java.lang.reflect.Field fYes = t.getClass().getDeclaredField("yes");
            fYes.setAccessible(true);
            fYes.set(t, BigDecimal.valueOf(yes));
            java.lang.reflect.Field fNo = t.getClass().getDeclaredField("no");
            fNo.setAccessible(true);
            fNo.set(t, BigDecimal.valueOf(no));
            java.lang.reflect.Field fAbstain = t.getClass().getDeclaredField("abstain");
            fAbstain.setAccessible(true);
            fAbstain.set(t, BigDecimal.valueOf(abstain));
        } catch (Exception e) {
            fail(e);
        }
        return t;
    }

    @Test
    void passesWhenYesBeatsNoAndMeetsQuorumAndTurnout() {
        // yes=60, no=30, abstain=15, quorum=50
        // yes > no ✓, yes >= 50 ✓, total=105 >= 50*1.5=75 ✓
        GovernanceVotingService.VoteTally t = tally(60, 30, 15);
        assertTrue(t.passes(BigDecimal.valueOf(50)));
    }

    @Test
    void failsWhenYesNotGreaterThanNo() {
        GovernanceVotingService.VoteTally t = tally(30, 30, 0);
        assertFalse(t.passes(BigDecimal.valueOf(10)));
    }

    @Test
    void failsWhenYesLessThanNo() {
        GovernanceVotingService.VoteTally t = tally(20, 30, 0);
        assertFalse(t.passes(BigDecimal.valueOf(10)));
    }

    @Test
    void failsWhenYesBelowQuorum() {
        // yes=40 < quorum=50
        GovernanceVotingService.VoteTally t = tally(40, 10, 0);
        assertFalse(t.passes(BigDecimal.valueOf(50)));
    }

    @Test
    void failsWhenTurnoutBelowThreshold() {
        // yes=60, no=5, abstain=5, total=70 < 50*1.5=75
        GovernanceVotingService.VoteTally t = tally(60, 5, 5);
        assertFalse(t.passes(BigDecimal.valueOf(50)));
    }

    @Test
    void passesExactlyAtThresholds() {
        // yes=50, no=0, abstain=25, total=75 = 50*1.5
        GovernanceVotingService.VoteTally t = tally(50, 0, 25);
        assertTrue(t.passes(BigDecimal.valueOf(50)));
    }

    @Test
    void gettersReturnZerosByDefault() {
        GovernanceVotingService.VoteTally t = new GovernanceVotingService.VoteTally();
        assertEquals(BigDecimal.ZERO, t.getYes());
        assertEquals(BigDecimal.ZERO, t.getNo());
        assertEquals(BigDecimal.ZERO, t.getAbstain());
    }
}