package org.nexus.settlement.risk.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.risk.RiskTransaction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MerchantHistoryScoreRule} 单元测试。
 */
class MerchantHistoryScoreRuleTest {

    private MerchantHistoryScoreRule rule;

    @BeforeEach
    void setUp() {
        rule = new MerchantHistoryScoreRule();
    }

    @Test
    void getRuleId_shouldReturnMerchantHistoryScore() {
        assertEquals("MERCHANT_HISTORY_SCORE", rule.getRuleId());
    }

    @Test
    void getWeight_shouldReturn7() {
        assertEquals(7, rule.getWeight());
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
    void score_noHistoryData_shouldReturn0() {
        RiskTransaction tx = new RiskTransaction();
        assertEquals(0, rule.score(tx));
    }

    @Test
    void score_highComplaintRate_shouldReturn80() {
        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantComplaintRate(0.15); // > 10%
        assertEquals(80, rule.score(tx));
    }

    @Test
    void score_mediumComplaintRate_shouldReturn50() {
        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantComplaintRate(0.07); // > 5% but <= 10%
        assertEquals(50, rule.score(tx));
    }

    @Test
    void score_highRefundRate_shouldReturn60() {
        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantRefundRate(0.20); // > 15%
        assertEquals(60, rule.score(tx));
    }

    @Test
    void score_mediumRefundRate_shouldReturn30() {
        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantRefundRate(0.10); // > 8% but <= 15%
        assertEquals(30, rule.score(tx));
    }

    @Test
    void score_highViolationCount_shouldReturn70() {
        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantViolationCount(5); // >= 3
        assertEquals(70, rule.score(tx));
    }

    @Test
    void score_mediumViolationCount_shouldReturn40() {
        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantViolationCount(1); // >= 1 but < 3
        assertEquals(40, rule.score(tx));
    }

    @Test
    void score_multipleFactors_shouldReturnMax() {
        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantComplaintRate(0.15); // → 80
        tx.setMerchantRefundRate(0.20);    // → 60
        tx.setMerchantViolationCount(5);   // → 70
        assertEquals(80, rule.score(tx));  // max(80, 60, 70) = 80
    }

    @Test
    void check_highComplaintRate_shouldReturnTrue() {
        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantComplaintRate(0.15);
        assertTrue(rule.check(tx));
    }

    @Test
    void check_mediumComplaintRate_shouldReturnFalse() {
        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantComplaintRate(0.07);
        assertFalse(rule.check(tx));
    }
}