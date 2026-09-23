package org.nexus.settlement.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.risk.rules.AmountScoreRule;
import org.nexus.settlement.risk.rules.BlacklistScoreRule;
import org.nexus.settlement.risk.rules.VelocityScoreRule;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultRiskScoreEngine} 单元测试。
 * <p>
 * 覆盖评分引擎的核心评估逻辑、加权计算、决策映射、阈值配置、
 * 动态增删规则，以及各评分规则的独立验证。
 * </p>
 */
class DefaultRiskScoreEngineTest {

    private DefaultRiskScoreEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DefaultRiskScoreEngine(null);
    }

    // ===== 1. evaluateWithScore：无规则时 → totalScore=0, decision=APPROVED =====
    @Test
    void evaluateWithScore_noRules_shouldReturnZeroScoreApproved() {
        RiskScoreResult result = engine.evaluateWithScore(new Object());

        assertEquals(0, result.getTotalScore());
        assertEquals(RiskDecision.APPROVED, result.getDecision());
        assertTrue(result.getRuleScores().isEmpty());
        assertTrue(result.getRuleWeights().isEmpty());
    }

    // ===== 2. evaluateWithScore：单条规则评分0 → totalScore=0, decision=APPROVED =====
    @Test
    void evaluateWithScore_singleRuleScoreZero_shouldApprove() {
        engine.addRule(new TestScoringRule("R1", 0, 5));

        RiskScoreResult result = engine.evaluateWithScore("tx");

        assertEquals(0, result.getTotalScore());
        assertEquals(RiskDecision.APPROVED, result.getDecision());
    }

    // ===== 3. evaluateWithScore：单条规则评分20 → totalScore=20, decision=APPROVED =====
    @Test
    void evaluateWithScore_singleRuleScore20_shouldApprove() {
        engine.addRule(new TestScoringRule("R1", 20, 5));

        RiskScoreResult result = engine.evaluateWithScore("tx");

        assertEquals(20, result.getTotalScore());
        assertEquals(RiskDecision.APPROVED, result.getDecision());
    }

    // ===== 4. evaluateWithScore：单条规则评分50 → totalScore=50, decision=PENDING_REVIEW =====
    @Test
    void evaluateWithScore_singleRuleScore50_shouldPendingReview() {
        engine.addRule(new TestScoringRule("R1", 50, 5));

        RiskScoreResult result = engine.evaluateWithScore("tx");

        assertEquals(50, result.getTotalScore());
        assertEquals(RiskDecision.PENDING_REVIEW, result.getDecision());
    }

    // ===== 5. evaluateWithScore：单条规则评分90 → totalScore=90, decision=REJECTED =====
    @Test
    void evaluateWithScore_singleRuleScore90_shouldReject() {
        engine.addRule(new TestScoringRule("R1", 90, 5));

        RiskScoreResult result = engine.evaluateWithScore("tx");

        assertEquals(90, result.getTotalScore());
        assertEquals(RiskDecision.REJECTED, result.getDecision());
    }

    // ===== 6. evaluateWithScore：多条规则加权计算 → 验证加权总分正确 =====
    @Test
    void evaluateWithScore_multipleRules_shouldComputeWeightedTotal() {
        // 规则A: score=40, weight=5 → 40*5=200
        // 规则B: score=60, weight=3 → 60*3=180
        // 总分 = (200+180) / (5+3) = 380/8 = 47
        engine.addRule(new TestScoringRule("A", 40, 5));
        engine.addRule(new TestScoringRule("B", 60, 3));

        RiskScoreResult result = engine.evaluateWithScore("tx");

        assertEquals(47, result.getTotalScore());
        assertEquals(RiskDecision.PENDING_REVIEW, result.getDecision());
    }

    // ===== 7. evaluateWithScore：不同权重的规则 → 验证权重影响 =====
    @Test
    void evaluateWithScore_differentWeights_shouldAffectTotalScore() {
        // 高权重规则评分低，低权重规则评分高
        // 规则A: score=10, weight=10 → 10*10=100
        // 规则B: score=90, weight=1 → 90*1=90
        // 总分 = (100+90) / (10+1) = 190/11 = 17
        engine.addRule(new TestScoringRule("A", 10, 10));
        engine.addRule(new TestScoringRule("B", 90, 1));

        RiskScoreResult result = engine.evaluateWithScore("tx");

        assertEquals(17, result.getTotalScore());
        assertEquals(RiskDecision.APPROVED, result.getDecision());
    }

    // ===== 8. evaluate：兼容接口 → 返回 decision =====
    @Test
    void evaluate_shouldReturnDecision() {
        engine.addRule(new TestScoringRule("R1", 90, 5));

        RiskDecision decision = engine.evaluate("tx");

        assertEquals(RiskDecision.REJECTED, decision);
    }

    // ===== 9. evaluate：黑名单命中 → REJECTED =====
    @Test
    void evaluate_blacklistHit_shouldReject() {
        BlacklistScoreRule blacklistRule = new BlacklistScoreRule();
        blacklistRule.addToBlacklist("0xBAD");
        engine.addRule(blacklistRule);

        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantId(1L);
        tx.setPayerAddress("0xBAD");
        tx.setAmount(BigDecimal.TEN);

        assertEquals(RiskDecision.REJECTED, engine.evaluate(tx));
    }

    // ===== 10. evaluate：金额大但非黑名单 → PENDING_REVIEW 或 APPROVED =====
    @Test
    void evaluate_largeAmountNotBlacklisted_shouldNotReject() {
        AmountScoreRule amountRule = new AmountScoreRule();
        BlacklistScoreRule blacklistRule = new BlacklistScoreRule();
        engine.addRule(amountRule);
        engine.addRule(blacklistRule);

        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantId(1L);
        tx.setPayerAddress("0xGOOD");
        tx.setAmount(new BigDecimal("50000")); // 10,000 ≤ amount < 100,000 → score=40

        RiskDecision decision = engine.evaluate(tx);
        // AmountScoreRule: score=40, weight=3 → 40*3=120
        // BlacklistScoreRule: score=0, weight=10 → 0*10=0
        // totalScore = 120 / 13 = 9 → APPROVED (0-30)
        // 关键验证：大额但非黑名单不应被拒绝
        assertTrue(decision == RiskDecision.APPROVED || decision == RiskDecision.PENDING_REVIEW,
                "Large amount but not blacklisted should not be REJECTED, got: " + decision);
    }

    // ===== 11. AmountScoreRule：各金额区间评分正确 =====
    @Test
    void amountScoreRule_allRanges_shouldScoreCorrectly() {
        AmountScoreRule rule = new AmountScoreRule();

        // amount == null → 0
        RiskTransaction txNull = new RiskTransaction();
        assertEquals(0, rule.score(txNull));

        // amount < 1000 → 0
        RiskTransaction txSmall = new RiskTransaction();
        txSmall.setAmount(new BigDecimal("500"));
        assertEquals(0, rule.score(txSmall));

        // 1000 ≤ amount < 10000 → 20
        RiskTransaction txMedium = new RiskTransaction();
        txMedium.setAmount(new BigDecimal("5000"));
        assertEquals(20, rule.score(txMedium));

        // 10000 ≤ amount < 100000 → 40
        RiskTransaction txLarge = new RiskTransaction();
        txLarge.setAmount(new BigDecimal("50000"));
        assertEquals(40, rule.score(txLarge));

        // 100000 ≤ amount < 1000000 → 70
        RiskTransaction txVeryLarge = new RiskTransaction();
        txVeryLarge.setAmount(new BigDecimal("500000"));
        assertEquals(70, rule.score(txVeryLarge));

        // amount ≥ 1000000 → 90
        RiskTransaction txHuge = new RiskTransaction();
        txHuge.setAmount(new BigDecimal("2000000"));
        assertEquals(90, rule.score(txHuge));
    }

    // ===== 12. VelocityScoreRule：高频交易评分高 =====
    @Test
    void velocityScoreRule_highFrequency_shouldScoreHigh() {
        VelocityScoreRule rule = new VelocityScoreRule();

        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantId(1L);
        tx.setPayerAddress("0xABC");

        // 第1次 → 0
        assertEquals(0, rule.score(tx));
        // 第2次 → 0
        assertEquals(0, rule.score(tx));
        // 第3次 → 0
        assertEquals(0, rule.score(tx));
        // 第4次 → 50 (>3次 in 1分钟)
        assertEquals(50, rule.score(tx));
        // 第5次 → 50
        assertEquals(50, rule.score(tx));
        // 第6次 → 80 (>5次 in 1分钟)
        assertEquals(80, rule.score(tx));
    }

    // ===== 13. BlacklistScoreRule：黑名单地址评分100 =====
    @Test
    void blacklistScoreRule_blacklistedAddress_shouldScore100() {
        BlacklistScoreRule rule = new BlacklistScoreRule();
        rule.addToBlacklist("0xBAD");

        RiskTransaction tx = new RiskTransaction();
        tx.setPayerAddress("0xBAD");
        tx.setMerchantId(1L);

        assertEquals(100, rule.score(tx));
    }

    // ===== 14. BlacklistScoreRule：非黑名单评分0 =====
    @Test
    void blacklistScoreRule_nonBlacklisted_shouldScore0() {
        BlacklistScoreRule rule = new BlacklistScoreRule();
        rule.addToBlacklist("0xBAD");

        RiskTransaction tx = new RiskTransaction();
        tx.setPayerAddress("0xGOOD");
        tx.setPayeeAddress("0xGOOD2");
        tx.setMerchantId(1L);

        assertEquals(0, rule.score(tx));
    }

    // ===== 15. 评分阈值可配置：approve-threshold=20 → 评分21为PENDING_REVIEW =====
    @Test
    void configurableThresholds_approveThreshold20_shouldPendingReviewAt21() {
        engine.setApproveThreshold(20);
        engine.addRule(new TestScoringRule("R1", 21, 5));

        RiskScoreResult result = engine.evaluateWithScore("tx");

        assertEquals(21, result.getTotalScore());
        assertEquals(RiskDecision.PENDING_REVIEW, result.getDecision());
    }

    // ===== 16. addRule/removeRule：动态增删规则 =====
    @Test
    void addRuleRemoveRule_shouldDynamicallyUpdate() {
        engine.addRule(new TestScoringRule("R1", 90, 5));
        assertEquals(RiskDecision.REJECTED, engine.evaluate("tx"));

        engine.removeRule("R1");
        assertEquals(RiskDecision.APPROVED, engine.evaluate("tx"));

        engine.addRule(new TestScoringRule("R2", 50, 5));
        assertEquals(RiskDecision.PENDING_REVIEW, engine.evaluate("tx"));
    }

    // ===== 17. RiskScoreResult：ruleScores明细正确 =====
    @Test
    void riskScoreResult_ruleScores_shouldBeCorrect() {
        engine.addRule(new TestScoringRule("A", 30, 5));
        engine.addRule(new TestScoringRule("B", 70, 3));

        RiskScoreResult result = engine.evaluateWithScore("tx");

        Map<String, Integer> scores = result.getRuleScores();
        assertEquals(2, scores.size());
        assertEquals(30, scores.get("A"));
        assertEquals(70, scores.get("B"));
    }

    // ===== 18. RiskScoreResult：ruleWeights明细正确 =====
    @Test
    void riskScoreResult_ruleWeights_shouldBeCorrect() {
        engine.addRule(new TestScoringRule("A", 30, 5));
        engine.addRule(new TestScoringRule("B", 70, 3));

        RiskScoreResult result = engine.evaluateWithScore("tx");

        Map<String, Integer> weights = result.getRuleWeights();
        assertEquals(2, weights.size());
        assertEquals(5, weights.get("A"));
        assertEquals(3, weights.get("B"));
    }

    // ===== 辅助：RiskScoreResult 的 evaluatedAt 不为 null =====
    @Test
    void riskScoreResult_evaluatedAt_shouldNotBeNull() {
        RiskScoreResult result = engine.evaluateWithScore("tx");
        assertNotNull(result.getEvaluatedAt());
    }

    // ===== 辅助：构造器注入规则列表 =====
    @Test
    void constructor_withRules_shouldRegisterAll() {
        AmountScoreRule amountRule = new AmountScoreRule();
        BlacklistScoreRule blacklistRule = new BlacklistScoreRule();
        DefaultRiskScoreEngine populated = new DefaultRiskScoreEngine(
                List.of(amountRule, blacklistRule));

        assertEquals(2, populated.getRuleCount());
    }

    // ===== 辅助：addRule 拒绝非 RiskScoringRule =====
    @Test
    void addRule_nonScoringRule_shouldBeIgnored() {
        engine.addRule(new NonScoringRule("R1"));
        assertEquals(0, engine.getRuleCount());
        assertEquals(RiskDecision.APPROVED, engine.evaluate("tx"));
    }

    /**
     * 测试用评分规则桩：固定返回 score 结果和 weight。
     */
    static class TestScoringRule implements RiskScoringRule {
        final String ruleId;
        final int fixedScore;
        final int weight;

        TestScoringRule(String ruleId, int fixedScore, int weight) {
            this.ruleId = ruleId;
            this.fixedScore = fixedScore;
            this.weight = weight;
        }

        @Override
        public String getRuleId() {
            return ruleId;
        }

        @Override
        public int getWeight() {
            return weight;
        }

        @Override
        public int score(Object transaction) {
            return fixedScore;
        }

        @Override
        public boolean check(Object transaction) {
            return score(transaction) >= 80;
        }
    }

    /**
     * 非 RiskScoringRule 的普通 RiskRule 实现，用于验证 addRule 的类型过滤。
     */
    static class NonScoringRule implements RiskRule {
        final String ruleId;

        NonScoringRule(String ruleId) {
            this.ruleId = ruleId;
        }

        @Override
        public String getRuleId() {
            return ruleId;
        }

        @Override
        public boolean check(Object transaction) {
            return true;
        }
    }
}