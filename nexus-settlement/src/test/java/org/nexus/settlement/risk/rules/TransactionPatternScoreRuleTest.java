package org.nexus.settlement.risk.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.risk.RiskTransaction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TransactionPatternScoreRule} 单元测试。
 */
class TransactionPatternScoreRuleTest {

    private TransactionPatternScoreRule rule;

    @BeforeEach
    void setUp() {
        rule = new TransactionPatternScoreRule();
    }

    @Test
    void getRuleId_shouldReturnTransactionPatternScore() {
        assertEquals("TRANSACTION_PATTERN_SCORE", rule.getRuleId());
    }

    @Test
    void getWeight_shouldReturn6() {
        assertEquals(6, rule.getWeight());
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
    void score_noPatternData_shouldReturn0() {
        RiskTransaction tx = new RiskTransaction();
        assertEquals(0, rule.score(tx));
    }

    @Test
    void score_highSplitOrderCount_shouldReturn80() {
        RiskTransaction tx = new RiskTransaction();
        tx.setSplitOrderCount(5); // >= 5
        assertEquals(80, rule.score(tx));
    }

    @Test
    void score_mediumSplitOrderCount_shouldReturn50() {
        RiskTransaction tx = new RiskTransaction();
        tx.setSplitOrderCount(3); // >= 3 but < 5
        assertEquals(50, rule.score(tx));
    }

    @Test
    void score_highRecentTransactionCount_shouldReturn90() {
        RiskTransaction tx = new RiskTransaction();
        tx.setRecentTransactionCount(20); // >= 20
        assertEquals(90, rule.score(tx));
    }

    @Test
    void score_mediumRecentTransactionCount_shouldReturn60() {
        RiskTransaction tx = new RiskTransaction();
        tx.setRecentTransactionCount(10); // >= 10 but < 20
        assertEquals(60, rule.score(tx));
    }

    @Test
    void score_multipleFactors_shouldReturnMax() {
        RiskTransaction tx = new RiskTransaction();
        tx.setSplitOrderCount(5);          // → 80
        tx.setRecentTransactionCount(20);  // → 90
        assertEquals(90, rule.score(tx));   // max(80, 90) = 90
    }

    @Test
    void check_highSplitOrderCount_shouldReturnTrue() {
        RiskTransaction tx = new RiskTransaction();
        tx.setSplitOrderCount(5);
        assertTrue(rule.check(tx));
    }

    @Test
    void check_mediumSplitOrderCount_shouldReturnFalse() {
        RiskTransaction tx = new RiskTransaction();
        tx.setSplitOrderCount(3);
        assertFalse(rule.check(tx));
    }
}