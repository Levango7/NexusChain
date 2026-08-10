package org.nexus.settlement.risk.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.risk.RiskTransaction;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AmountThresholdRule} 单元测试。
 */
class AmountThresholdRuleTest {

    private AmountThresholdRule rule;

    @BeforeEach
    void setUp() {
        rule = new AmountThresholdRule();
        // 默认阈值由 @Value 注入未触发，手动设置
        rule.setThreshold(new BigDecimal("100000"));
    }

    @Test
    void check_aboveThreshold_shouldHit() {
        RiskTransaction tx = new RiskTransaction();
        tx.setAmount(new BigDecimal("150000"));

        assertTrue(rule.check(tx));
    }

    @Test
    void check_atThreshold_shouldNotHit() {
        RiskTransaction tx = new RiskTransaction();
        tx.setAmount(new BigDecimal("100000"));

        // 严格大于才拦截
        assertFalse(rule.check(tx));
    }

    @Test
    void check_belowThreshold_shouldPass() {
        RiskTransaction tx = new RiskTransaction();
        tx.setAmount(new BigDecimal("50000"));

        assertFalse(rule.check(tx));
    }

    @Test
    void check_nullAmount_shouldPass() {
        RiskTransaction tx = new RiskTransaction();
        assertFalse(rule.check(tx));
    }

    @Test
    void check_nullTransaction_shouldPass() {
        assertFalse(rule.check(null));
    }

    @Test
    void check_nonRiskTransaction_shouldPass() {
        assertFalse(rule.check("not-a-tx"));
        assertFalse(rule.check(42));
    }

    @Test
    void check_nullThreshold_shouldPass() {
        rule.setThreshold(null);
        RiskTransaction tx = new RiskTransaction();
        tx.setAmount(new BigDecimal("1000000"));

        assertFalse(rule.check(tx));
    }

    @Test
    void getRuleId_shouldBeStable() {
        assertTrue("AMOUNT_THRESHOLD".equals(rule.getRuleId()));
    }
}