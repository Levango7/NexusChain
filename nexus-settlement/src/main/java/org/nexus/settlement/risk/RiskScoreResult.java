package org.nexus.settlement.risk;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 风控评分结果。
 * <p>
 * 不可变值对象，封装加权总分、决策、各规则评分明细和权重明细。
 * 通过静态工厂方法 {@link #of} 创建实例。
 * </p>
 */
public final class RiskScoreResult {

    /** 加权总分（0-100） */
    private final int totalScore;

    /** 基于总分映射的风控决策 */
    private final RiskDecision decision;

    /** 各规则评分明细（ruleId -> score） */
    private final Map<String, Integer> ruleScores;

    /** 各规则权重明细（ruleId -> weight） */
    private final Map<String, Integer> ruleWeights;

    /** 评估时间戳 */
    private final Instant evaluatedAt;

    private RiskScoreResult(int totalScore, RiskDecision decision,
                            Map<String, Integer> ruleScores,
                            Map<String, Integer> ruleWeights,
                            Instant evaluatedAt) {
        this.totalScore = totalScore;
        this.decision = Objects.requireNonNull(decision, "decision must not be null");
        this.ruleScores = Collections.unmodifiableMap(Objects.requireNonNull(ruleScores, "ruleScores must not be null"));
        this.ruleWeights = Collections.unmodifiableMap(Objects.requireNonNull(ruleWeights, "ruleWeights must not be null"));
        this.evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
    }

    /**
     * 静态工厂方法。
     *
     * @param totalScore 加权总分（0-100）
     * @param decision   风控决策
     * @param scores     各规则评分明细
     * @param weights    各规则权重明细
     * @return 不可变的 RiskScoreResult 实例
     */
    public static RiskScoreResult of(int totalScore, RiskDecision decision,
                                     Map<String, Integer> scores,
                                     Map<String, Integer> weights) {
        return new RiskScoreResult(totalScore, decision, scores, weights, Instant.now());
    }

    /**
     * 静态工厂方法（带评估时间戳，便于测试）。
     *
     * @param totalScore  加权总分（0-100）
     * @param decision    风控决策
     * @param scores      各规则评分明细
     * @param weights     各规则权重明细
     * @param evaluatedAt 评估时间戳
     * @return 不可变的 RiskScoreResult 实例
     */
    public static RiskScoreResult of(int totalScore, RiskDecision decision,
                                     Map<String, Integer> scores,
                                     Map<String, Integer> weights,
                                     Instant evaluatedAt) {
        return new RiskScoreResult(totalScore, decision, scores, weights, evaluatedAt);
    }

    public int getTotalScore() {
        return totalScore;
    }

    public RiskDecision getDecision() {
        return decision;
    }

    public Map<String, Integer> getRuleScores() {
        return ruleScores;
    }

    public Map<String, Integer> getRuleWeights() {
        return ruleWeights;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    @Override
    public String toString() {
        return "RiskScoreResult{totalScore=" + totalScore
                + ", decision=" + decision
                + ", ruleScores=" + ruleScores
                + ", ruleWeights=" + ruleWeights
                + ", evaluatedAt=" + evaluatedAt + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RiskScoreResult that = (RiskScoreResult) o;
        return totalScore == that.totalScore
                && decision == that.decision
                && ruleScores.equals(that.ruleScores)
                && ruleWeights.equals(that.ruleWeights)
                && evaluatedAt.equals(that.evaluatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(totalScore, decision, ruleScores, ruleWeights, evaluatedAt);
    }
}