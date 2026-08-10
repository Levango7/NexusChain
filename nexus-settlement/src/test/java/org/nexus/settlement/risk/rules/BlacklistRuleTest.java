package org.nexus.settlement.risk.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.risk.RiskTransaction;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BlacklistRule} 单元测试。
 */
class BlacklistRuleTest {

    private BlacklistRule rule;

    @BeforeEach
    void setUp() {
        rule = new BlacklistRule();
    }

    @Test
    void check_payerBlacklisted_shouldHit() {
        rule.setBlacklist(Set.of("0xBAD"));

        RiskTransaction tx = new RiskTransaction();
        tx.setPayerAddress("0xBAD");

        assertTrue(rule.check(tx));
    }

    @Test
    void check_payeeBlacklisted_shouldHit() {
        rule.setBlacklist(Set.of("0xBAD"));

        RiskTransaction tx = new RiskTransaction();
        tx.setPayeeAddress("0xBAD");

        assertTrue(rule.check(tx));
    }

    @Test
    void check_neitherBlacklisted_shouldPass() {
        rule.setBlacklist(Set.of("0xBAD"));

        RiskTransaction tx = new RiskTransaction();
        tx.setPayerAddress("0xGOOD");
        tx.setPayeeAddress("0xGOOD2");

        assertFalse(rule.check(tx));
    }

    @Test
    void check_nullBlacklist_shouldPass() {
        rule.setBlacklist(null);
        RiskTransaction tx = new RiskTransaction();
        tx.setPayerAddress("0xBAD");

        assertFalse(rule.check(tx));
    }

    @Test
    void check_emptyBlacklist_shouldPass() {
        rule.setBlacklist(Set.of());
        RiskTransaction tx = new RiskTransaction();
        tx.setPayerAddress("0xBAD");

        assertFalse(rule.check(tx));
    }

    @Test
    void check_nullTransaction_shouldPass() {
        rule.setBlacklist(Set.of("0xBAD"));
        assertFalse(rule.check(null));
    }

    @Test
    void check_nonRiskTransaction_shouldPass() {
        rule.setBlacklist(Set.of("0xBAD"));
        assertFalse(rule.check("string"));
    }

    @Test
    void check_blankAddress_shouldPass() {
        rule.setBlacklist(Set.of("0xBAD"));
        RiskTransaction tx = new RiskTransaction();
        tx.setPayerAddress("   ");
        tx.setPayeeAddress("");

        assertFalse(rule.check(tx));
    }

    @Test
    void getRuleId_shouldBeStable() {
        assertTrue("BLACKLIST".equals(rule.getRuleId()));
    }
}