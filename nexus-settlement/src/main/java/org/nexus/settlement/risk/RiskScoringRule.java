package org.nexus.settlement.risk;

/**
 * 评分风控规则接口。
 * <p>
 * 继承 {@link RiskRule}，在保留原有 check() 拦截语义的基础上，
 * 新增评分（score）、权重（weight）和描述（description）能力，
 * 使规则可参与加权评分引擎的量化决策。
 * </p>
 */
public interface RiskScoringRule extends RiskRule {

    /**
     * 获取规则权重（1-10，默认5）。
     * <p>
     * 权重用于加权评分计算：总分 = Σ(ruleScore × weight) / Σ(weight)。
     * 权重越高，该规则对最终评分的影响越大。
     * </p>
     *
     * @return 规则权重
     */
    default int getWeight() {
        return 5;
    }

    /**
     * 对交易进行评分。
     * <p>
     * 返回值范围 0-100：0 表示无风险，100 表示高风险。
     * 评分不直接决定拦截，而是由评分引擎加权汇总后映射为 {@link RiskDecision}。
     * </p>
     *
     * @param transaction 待评分交易
     * @return 风险评分（0-100）
     */
    int score(Object transaction);

    /**
     * 获取规则描述。
     *
     * @return 规则描述文本
     */
    default String getRuleDescription() {
        return getRuleId();
    }
}