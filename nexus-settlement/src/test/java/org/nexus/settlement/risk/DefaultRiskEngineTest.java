package org.nexus.settlement.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.risk.rules.AmountThresholdRule;
import org.nexus.settlement.risk.rules.BlacklistRule;
import org.nexus.settlement.risk.rules.VelocityRule;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link DefaultRiskEngine} 单元测试。
 * <p>
 * 覆盖规则链初始化、evaluate 短路拦截、动态 addRule / removeRule，
 * 以及非法规则入参的拒绝路径。
 * </p>
 */
class DefaultRiskEngineTest {

    private DefaultRiskEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DefaultRiskEngine(null);
    }

    @Test
    void evaluate_noRules_shouldApprove() {
        assertEquals(RiskDecision.APPROVED, engine.evaluate(new Object()));
        assertEquals(RiskDecision.APPROVED, engine.evaluate(null));
    }

    @Test
    void evaluate_ruleHits_shouldReject() {
        RiskRule rejecting = new TestRule("R1", true);
        engine.addRule(rejecting);

        assertEquals(RiskDecision.REJECTED, engine.evaluate("tx"));
    }

    @Test
    void evaluate_ruleMisses_shouldApprove() {
        RiskRule passing = new TestRule("R1", false);
        engine.addRule(passing);

        assertEquals(RiskDecision.APPROVED, engine.evaluate("tx"));
    }

    @Test
    void evaluate_shortCircuit_shouldStopAtFirstHit() {
        TestRule first = new TestRule("R1", true);
        TestRule second = new TestRule("R2", true);
        engine.addRule(first);
        engine.addRule(second);

        assertEquals(RiskDecision.REJECTED, engine.evaluate("tx"));
        // 第二条规则不应被调用（短路）
        assertEquals(0, second.invocations);
        assertEquals(1, first.invocations);
    }

    @Test
    void addRule_null_shouldBeIgnored() {
        engine.addRule(null);
        // 不应抛异常，evaluate 仍放行
        assertEquals(RiskDecision.APPROVED, engine.evaluate("tx"));
    }

    @Test
    void addRule_nullRuleId_shouldBeIgnored() {
        engine.addRule(new TestRule(null, true));
        assertEquals(RiskDecision.APPROVED, engine.evaluate("tx"));
    }

    @Test
    void addRule_duplicate_shouldReplace() {
        engine.addRule(new TestRule("R1", false));
        engine.addRule(new TestRule("R1", true));

        assertEquals(RiskDecision.REJECTED, engine.evaluate("tx"));
    }

    @Test
    void removeRule_existing_shouldDeactivate() {
        engine.addRule(new TestRule("R1", true));
        engine.removeRule("R1");

        assertEquals(RiskDecision.APPROVED, engine.evaluate("tx"));
    }

    @Test
    void removeRule_unknown_shouldBeNoOp() {
        engine.removeRule("NOPE");
        engine.removeRule(null);
        assertEquals(RiskDecision.APPROVED, engine.evaluate("tx"));
    }

    @Test
    void constructor_withRules_shouldRegisterAll() {
        AmountThresholdRule amountRule = new AmountThresholdRule();
        BlacklistRule blacklistRule = new BlacklistRule();
        VelocityRule velocityRule = new VelocityRule();
        DefaultRiskEngine populated = new DefaultRiskEngine(
                List.of(amountRule, blacklistRule, velocityRule));

        // 三条规则都注册：构造一个命中金额阈值的交易应被拒绝
        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantId(1L);
        tx.setAmount(new BigDecimal("1000000"));
        assertEquals(RiskDecision.REJECTED, populated.evaluate(tx));
    }

    @Test
    void constructor_nullList_shouldNotThrow() {
        DefaultRiskEngine empty = new DefaultRiskEngine(null);
        assertEquals(RiskDecision.APPROVED, empty.evaluate("tx"));
    }

    @Test
    void endToEnd_blacklistHit_shouldReject() {
        BlacklistRule blacklistRule = new BlacklistRule();
        blacklistRule.setBlacklist(Set.of("0xBAD"));
        engine.addRule(blacklistRule);

        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantId(1L);
        tx.setPayerAddress("0xBAD");
        tx.setAmount(BigDecimal.TEN);

        assertEquals(RiskDecision.REJECTED, engine.evaluate(tx));
    }

    @Test
    void endToEnd_velocityHit_shouldReject() {
        VelocityRule velocityRule = new VelocityRule();
        velocityRule.setMaxCount(2);
        velocityRule.setWindowSeconds(60);
        engine.addRule(velocityRule);

        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantId(1L);
        tx.setPayerAddress("0xABC");
        tx.setAmount(BigDecimal.TEN);

        // 前两次放行
        assertEquals(RiskDecision.APPROVED, engine.evaluate(tx));
        assertEquals(RiskDecision.APPROVED, engine.evaluate(tx));
        // 第三次命中频率限制
        assertEquals(RiskDecision.REJECTED, engine.evaluate(tx));
    }

    @Test
    void endToEnd_amountThresholdHit_shouldReject() {
        AmountThresholdRule amountRule = new AmountThresholdRule();
        amountRule.setThreshold(new BigDecimal("1000"));
        engine.addRule(amountRule);

        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantId(1L);
        tx.setAmount(new BigDecimal("1500"));

        assertEquals(RiskDecision.REJECTED, engine.evaluate(tx));
    }

    /** 测试用规则桩：固定返回 check 结果，统计调用次数。 */
    static class TestRule implements RiskRule {
        final String ruleId;
        final boolean result;
        int invocations = 0;

        TestRule(String ruleId, boolean result) {
            this.ruleId = ruleId;
            this.result = result;
        }

        @Override
        public String getRuleId() {
            return ruleId;
        }

        @Override
        public boolean check(Object transaction) {
            invocations++;
            return result;
        }
    }
}