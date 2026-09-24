package org.nexus.settlement.risk.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.risk.RiskTransaction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChannelScoreRule} 单元测试。
 */
class ChannelScoreRuleTest {

    private ChannelScoreRule rule;

    @BeforeEach
    void setUp() {
        rule = new ChannelScoreRule();
    }

    @Test
    void getRuleId_shouldReturnChannelScore() {
        assertEquals("CHANNEL_SCORE", rule.getRuleId());
    }

    @Test
    void getWeight_shouldReturn4() {
        assertEquals(4, rule.getWeight());
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
    void score_noChannelData_shouldReturn0() {
        RiskTransaction tx = new RiskTransaction();
        assertEquals(0, rule.score(tx));
    }

    @Test
    void score_highFailureRate_shouldReturn80() {
        RiskTransaction tx = new RiskTransaction();
        tx.setChannelFailureRate(0.25); // > 20%
        assertEquals(80, rule.score(tx));
    }

    @Test
    void score_mediumFailureRate_shouldReturn50() {
        RiskTransaction tx = new RiskTransaction();
        tx.setChannelFailureRate(0.15); // > 10% but <= 20%
        assertEquals(50, rule.score(tx));
    }

    @Test
    void score_highChargebackRate_shouldReturn70() {
        RiskTransaction tx = new RiskTransaction();
        tx.setChannelChargebackRate(0.08); // > 5%
        assertEquals(70, rule.score(tx));
    }

    @Test
    void score_mediumChargebackRate_shouldReturn40() {
        RiskTransaction tx = new RiskTransaction();
        tx.setChannelChargebackRate(0.03); // > 2% but <= 5%
        assertEquals(40, rule.score(tx));
    }

    @Test
    void score_multipleFactors_shouldReturnMax() {
        RiskTransaction tx = new RiskTransaction();
        tx.setChannelFailureRate(0.25);     // → 80
        tx.setChannelChargebackRate(0.08);  // → 70
        assertEquals(80, rule.score(tx));    // max(80, 70) = 80
    }

    @Test
    void check_highFailureRate_shouldReturnTrue() {
        RiskTransaction tx = new RiskTransaction();
        tx.setChannelFailureRate(0.25);
        assertTrue(rule.check(tx));
    }

    @Test
    void check_mediumFailureRate_shouldReturnFalse() {
        RiskTransaction tx = new RiskTransaction();
        tx.setChannelFailureRate(0.15);
        assertFalse(rule.check(tx));
    }
}