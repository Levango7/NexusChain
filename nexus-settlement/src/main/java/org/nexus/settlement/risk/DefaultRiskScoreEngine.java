package org.nexus.settlement.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 默认评分风控引擎。
 * <p>
 * 实现 {@link RiskEngine} 接口，与 {@link DefaultRiskEngine} 并存。
 * 采用加权评分模式：遍历所有 {@link RiskScoringRule}，调用 score() 获取每条规则评分，
 * 加权计算总分 = Σ(ruleScore × weight) / Σ(weight)，根据总分映射为 {@link RiskDecision}。
 * </p>
 *
 * <p>评分→决策映射规则（可通过配置覆盖）：
 * <ul>
 *   <li>0 ~ approveThreshold: APPROVED</li>
 *   <li>approveThreshold+1 ~ reviewThreshold: PENDING_REVIEW</li>
 *   <li>reviewThreshold+1 ~ 100: REJECTED</li>
 * </ul>
 * </p>
 */
@Service
@Primary
public class DefaultRiskScoreEngine implements RiskEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultRiskScoreEngine.class);

    /** 规则注册表（按 ruleId 索引，保持插入顺序） */
    private final Map<String, RiskScoringRule> scoringRules = new LinkedHashMap<>();

    /** APPROVED 阈值：总分 <= 此值时放行（字段初始化确保非 Spring 环境也有默认值） */
    @Value("${nexus.risk.score.approve-threshold:30}")
    private int approveThreshold = 30;

    /** PENDING_REVIEW 阈值：总分 <= 此值时待复核，超过则拒绝（字段初始化确保非 Spring 环境也有默认值） */
    @Value("${nexus.risk.score.review-threshold:60}")
    private int reviewThreshold = 60;

    /**
     * 构造时注入容器中所有评分规则 Bean。
     *
     * @param registeredRules Spring 容器中扫描到的评分规则列表（可为空）
     */
    public DefaultRiskScoreEngine(List<RiskScoringRule> registeredRules) {
        if (registeredRules != null) {
            for (RiskScoringRule rule : registeredRules) {
                addRule(rule);
            }
        }
        log.info("DefaultRiskScoreEngine initialized with {} scoring rules: {}",
                scoringRules.size(), scoringRules.keySet());
    }

    /**
     * 对交易进行加权评分评估。
     *
     * @param transaction 待评估交易
     * @return 评分结果（包含总分、决策、各规则评分明细）
     */
    public RiskScoreResult evaluateWithScore(Object transaction) {
        Map<String, Integer> ruleScores = new LinkedHashMap<>();
        Map<String, Integer> ruleWeights = new LinkedHashMap<>();

        int weightedSum = 0;
        int totalWeight = 0;

        for (RiskScoringRule rule : scoringRules.values()) {
            int weight = rule.getWeight();
            int score = rule.score(transaction);

            ruleScores.put(rule.getRuleId(), score);
            ruleWeights.put(rule.getRuleId(), weight);

            weightedSum += score * weight;
            totalWeight += weight;
        }

        int totalScore = totalWeight > 0 ? weightedSum / totalWeight : 0;
        RiskDecision decision = mapScoreToDecision(totalScore);

        log.debug("Risk score evaluation: totalScore={}, decision={}, ruleScores={}",
                totalScore, decision, ruleScores);

        return RiskScoreResult.of(totalScore, decision, ruleScores, ruleWeights);
    }

    @Override
    public RiskDecision evaluate(Object transaction) {
        return evaluateWithScore(transaction).getDecision();
    }

    @Override
    public void addRule(RiskRule rule) {
        if (rule instanceof RiskScoringRule scoringRule) {
            if (Objects.nonNull(scoringRule.getRuleId())) {
                scoringRules.put(scoringRule.getRuleId(), scoringRule);
                log.info("Scoring rule added: ruleId={}, weight={}, totalRules={}",
                        scoringRule.getRuleId(), scoringRule.getWeight(), scoringRules.size());
            } else {
                log.warn("Rejected scoring rule with null ruleId: {}", scoringRule);
            }
        } else {
            log.warn("Rejected non-scoring rule (not a RiskScoringRule): {}", rule);
        }
    }

    @Override
    public void removeRule(String ruleId) {
        if (Objects.nonNull(ruleId)) {
            RiskScoringRule removed = scoringRules.remove(ruleId);
            if (removed != null) {
                log.info("Scoring rule removed: ruleId={}, totalRules={}", ruleId, scoringRules.size());
            }
        }
    }

    /**
     * 根据总分映射为风控决策。
     *
     * @param totalScore 加权总分（0-100）
     * @return 风控决策
     */
    private RiskDecision mapScoreToDecision(int totalScore) {
        if (totalScore <= approveThreshold) {
            return RiskDecision.APPROVED;
        } else if (totalScore <= reviewThreshold) {
            return RiskDecision.PENDING_REVIEW;
        } else {
            return RiskDecision.REJECTED;
        }
    }

    // --- getter / setter（便于测试和配置覆盖） ---

    public int getApproveThreshold() {
        return approveThreshold;
    }

    public void setApproveThreshold(int approveThreshold) {
        this.approveThreshold = approveThreshold;
    }

    public int getReviewThreshold() {
        return reviewThreshold;
    }

    public void setReviewThreshold(int reviewThreshold) {
        this.reviewThreshold = reviewThreshold;
    }

    /**
     * 获取当前已注册的评分规则数量。
     *
     * @return 规则数量
     */
    public int getRuleCount() {
        return scoringRules.size();
    }
}