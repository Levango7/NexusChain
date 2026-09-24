package org.nexus.settlement.risk.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.risk.RiskTransaction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DeviceClusterScoreRule} 单元测试。
 */
class DeviceClusterScoreRuleTest {

    private DeviceClusterScoreRule rule;

    @BeforeEach
    void setUp() {
        rule = new DeviceClusterScoreRule();
    }

    @Test
    void getRuleId_shouldReturnDeviceClusterScore() {
        assertEquals("DEVICE_CLUSTER_SCORE", rule.getRuleId());
    }

    @Test
    void getWeight_shouldReturn8() {
        assertEquals(8, rule.getWeight());
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
    void score_noDeviceData_shouldReturn0() {
        RiskTransaction tx = new RiskTransaction();
        assertEquals(0, rule.score(tx));
    }

    @Test
    void score_highLinkedAccountCount_shouldReturn90() {
        RiskTransaction tx = new RiskTransaction();
        tx.setLinkedAccountCount(5); // >= 5
        assertEquals(90, rule.score(tx));
    }

    @Test
    void score_mediumLinkedAccountCount_shouldReturn60() {
        RiskTransaction tx = new RiskTransaction();
        tx.setLinkedAccountCount(3); // >= 3 but < 5
        assertEquals(60, rule.score(tx));
    }

    @Test
    void score_highLinkedDeviceCount_shouldReturn80() {
        RiskTransaction tx = new RiskTransaction();
        tx.setLinkedDeviceCount(5); // >= 5
        assertEquals(80, rule.score(tx));
    }

    @Test
    void score_mediumLinkedDeviceCount_shouldReturn50() {
        RiskTransaction tx = new RiskTransaction();
        tx.setLinkedDeviceCount(3); // >= 3 but < 5
        assertEquals(50, rule.score(tx));
    }

    @Test
    void score_multipleFactors_shouldReturnMax() {
        RiskTransaction tx = new RiskTransaction();
        tx.setLinkedAccountCount(5);  // → 90
        tx.setLinkedDeviceCount(5);   // → 80
        assertEquals(90, rule.score(tx)); // max(90, 80) = 90
    }

    @Test
    void check_highLinkedAccountCount_shouldReturnTrue() {
        RiskTransaction tx = new RiskTransaction();
        tx.setLinkedAccountCount(5);
        assertTrue(rule.check(tx));
    }

    @Test
    void check_mediumLinkedAccountCount_shouldReturnFalse() {
        RiskTransaction tx = new RiskTransaction();
        tx.setLinkedAccountCount(3);
        assertFalse(rule.check(tx));
    }
}