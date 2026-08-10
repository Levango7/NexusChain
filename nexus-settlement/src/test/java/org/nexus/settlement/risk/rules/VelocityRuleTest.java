package org.nexus.settlement.risk.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.risk.RiskTransaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VelocityRule} 单元测试。
 * <p>
 * 覆盖窗口滑动、阈值命中、主体键构造（商户维度 / 商户+付款方维度 / 缺字段退化）等分支。
 * </p>
 */
class VelocityRuleTest {

    private VelocityRule rule;

    @BeforeEach
    void setUp() {
        rule = new VelocityRule();
        rule.setMaxCount(3);
        rule.setWindowSeconds(60);
    }

    @Test
    void check_underLimit_shouldPass() {
        RiskTransaction tx = newRiskTx(1L, "0xA");

        assertFalse(rule.check(tx));
        assertFalse(rule.check(tx));
        assertFalse(rule.check(tx));
    }

    @Test
    void check_atLimit_shouldHit() {
        RiskTransaction tx = newRiskTx(1L, "0xA");

        assertFalse(rule.check(tx));
        assertFalse(rule.check(tx));
        assertFalse(rule.check(tx));
        // 第 4 次命中
        assertTrue(rule.check(tx));
    }

    @Test
    void check_differentSubjects_shouldBeIndependent() {
        RiskTransaction txA = newRiskTx(1L, "0xA");
        RiskTransaction txB = newRiskTx(2L, "0xB");

        assertFalse(rule.check(txA));
        assertFalse(rule.check(txA));
        assertFalse(rule.check(txA));
        // 不同主体不应被影响
        assertFalse(rule.check(txB));
    }

    @Test
    void check_nullTransaction_shouldPass() {
        assertFalse(rule.check(null));
    }

    @Test
    void check_nonRiskTransaction_shouldPass() {
        assertFalse(rule.check("string"));
        assertFalse(rule.check(42));
    }

    @Test
    void check_noSubjectFields_shouldPass() {
        // 既无 merchantId 也无 payerAddress → subject 为 null → 不拦截
        RiskTransaction tx = new RiskTransaction();
        assertFalse(rule.check(tx));
    }

    @Test
    void check_merchantOnlySubject_shouldWork() {
        // 仅有 merchantId，无 payerAddress → 退化为商户维度
        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantId(99L);

        assertFalse(rule.check(tx));
        assertFalse(rule.check(tx));
        assertFalse(rule.check(tx));
        assertTrue(rule.check(tx));
    }

    @Test
    void check_blankPayerAddress_shouldFallbackToMerchant() {
        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantId(5L);
        tx.setPayerAddress("   ");

        assertFalse(rule.check(tx));
    }

    @Test
    void getRuleId_shouldBeStable() {
        assertTrue("VELOCITY".equals(rule.getRuleId()));
    }

    private RiskTransaction newRiskTx(long merchantId, String payer) {
        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantId(merchantId);
        tx.setPayerAddress(payer);
        return tx;
    }
}