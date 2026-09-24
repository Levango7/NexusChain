package org.nexus.settlement.risk.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.risk.RiskTransaction;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link IpScoreRule} 单元测试。
 */
class IpScoreRuleTest {

    private IpScoreRule rule;

    @BeforeEach
    void setUp() {
        rule = new IpScoreRule();
    }

    @Test
    void getRuleId_shouldReturnIpScore() {
        assertEquals("IP_SCORE", rule.getRuleId());
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
    void score_noIpAddress_shouldReturn0() {
        RiskTransaction tx = new RiskTransaction();
        assertEquals(0, rule.score(tx));
    }

    @Test
    void score_blacklistedIp_shouldReturn100() {
        rule.addToBlacklist("1.2.3.4");
        RiskTransaction tx = new RiskTransaction();
        tx.setIpAddress("1.2.3.4");
        assertEquals(100, rule.score(tx));
    }

    @Test
    void score_proxyIp_shouldReturn70() {
        rule.addProxyIp("5.6.7.8");
        RiskTransaction tx = new RiskTransaction();
        tx.setIpAddress("5.6.7.8");
        assertEquals(70, rule.score(tx));
    }

    @Test
    void score_proxyIpWithWildcard_shouldReturn70() {
        rule.addProxyIp("10.0.0.*");
        RiskTransaction tx = new RiskTransaction();
        tx.setIpAddress("10.0.0.99");
        assertEquals(70, rule.score(tx));
    }

    @Test
    void score_normalIpWithRegion_shouldReturn10() {
        RiskTransaction tx = new RiskTransaction();
        tx.setIpAddress("8.8.8.8");
        tx.setRegion("US");
        assertEquals(10, rule.score(tx));
    }

    @Test
    void score_normalIpNoRegion_shouldReturn0() {
        RiskTransaction tx = new RiskTransaction();
        tx.setIpAddress("8.8.8.8");
        assertEquals(0, rule.score(tx));
    }

    @Test
    void check_scoreBelow80_shouldReturnFalse() {
        rule.addProxyIp("5.6.7.8");
        RiskTransaction tx = new RiskTransaction();
        tx.setIpAddress("5.6.7.8");
        assertFalse(rule.check(tx));
    }

    @Test
    void check_scoreAbove80_shouldReturnTrue() {
        rule.addToBlacklist("1.2.3.4");
        RiskTransaction tx = new RiskTransaction();
        tx.setIpAddress("1.2.3.4");
        assertTrue(rule.check(tx));
    }

    @Test
    void blacklistManagement_addRemoveSet_shouldWork() {
        rule.addToBlacklist("1.1.1.1");
        rule.addToBlacklist("2.2.2.2");
        assertEquals(2, rule.getBlacklistIps().size());

        rule.removeFromBlacklist("1.1.1.1");
        assertEquals(1, rule.getBlacklistIps().size());

        rule.setBlacklistIps(Set.of("3.3.3.3"));
        assertEquals(1, rule.getBlacklistIps().size());
        assertTrue(rule.getBlacklistIps().contains("3.3.3.3"));
    }

    @Test
    void proxyManagement_addRemoveSet_shouldWork() {
        rule.addProxyIp("1.1.1.1");
        rule.addProxyIp("2.2.2.2");
        assertEquals(2, rule.getProxyIps().size());

        rule.removeProxyIp("1.1.1.1");
        assertEquals(1, rule.getProxyIps().size());

        rule.setProxyIps(Set.of("3.3.3.3"));
        assertEquals(1, rule.getProxyIps().size());
        assertTrue(rule.getProxyIps().contains("3.3.3.3"));
    }
}