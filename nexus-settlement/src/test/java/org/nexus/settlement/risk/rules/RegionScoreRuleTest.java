package org.nexus.settlement.risk.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.risk.RiskTransaction;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RegionScoreRule} 单元测试。
 */
class RegionScoreRuleTest {

    private RegionScoreRule rule;

    @BeforeEach
    void setUp() {
        rule = new RegionScoreRule();
    }

    @Test
    void getRuleId_shouldReturnRegionScore() {
        assertEquals("REGION_SCORE", rule.getRuleId());
    }

    @Test
    void getWeight_shouldReturn5() {
        assertEquals(5, rule.getWeight());
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
    void score_noRegionNoCrossBorder_shouldReturn0() {
        RiskTransaction tx = new RiskTransaction();
        assertEquals(0, rule.score(tx));
    }

    @Test
    void score_highRiskRegion_shouldReturn80() {
        rule.addHighRiskRegion("XX");
        RiskTransaction tx = new RiskTransaction();
        tx.setRegion("XX");
        assertEquals(80, rule.score(tx));
    }

    @Test
    void score_crossBorder_shouldReturn60() {
        RiskTransaction tx = new RiskTransaction();
        tx.setCrossBorder(true);
        assertEquals(60, rule.score(tx));
    }

    @Test
    void score_mediumRiskRegion_shouldReturn30() {
        rule.addMediumRiskRegion("YY");
        RiskTransaction tx = new RiskTransaction();
        tx.setRegion("YY");
        assertEquals(30, rule.score(tx));
    }

    @Test
    void score_highRiskRegionTakesPriorityOverCrossBorder() {
        rule.addHighRiskRegion("XX");
        RiskTransaction tx = new RiskTransaction();
        tx.setRegion("XX");
        tx.setCrossBorder(true);
        assertEquals(80, rule.score(tx));
    }

    @Test
    void check_highRiskRegion_shouldReturnTrue() {
        rule.addHighRiskRegion("XX");
        RiskTransaction tx = new RiskTransaction();
        tx.setRegion("XX");
        assertTrue(rule.check(tx));
    }

    @Test
    void check_mediumRiskRegion_shouldReturnFalse() {
        rule.addMediumRiskRegion("YY");
        RiskTransaction tx = new RiskTransaction();
        tx.setRegion("YY");
        assertFalse(rule.check(tx));
    }

    @Test
    void highRiskRegionManagement_shouldWork() {
        rule.addHighRiskRegion("A");
        rule.addHighRiskRegion("B");
        assertEquals(2, rule.getHighRiskRegions().size());

        rule.removeHighRiskRegion("A");
        assertEquals(1, rule.getHighRiskRegions().size());

        rule.setHighRiskRegions(Set.of("C"));
        assertEquals(1, rule.getHighRiskRegions().size());
    }

    @Test
    void mediumRiskRegionManagement_shouldWork() {
        rule.addMediumRiskRegion("A");
        rule.addMediumRiskRegion("B");
        assertEquals(2, rule.getMediumRiskRegions().size());

        rule.removeMediumRiskRegion("A");
        assertEquals(1, rule.getMediumRiskRegions().size());

        rule.setMediumRiskRegions(Set.of("C"));
        assertEquals(1, rule.getMediumRiskRegions().size());
    }
}