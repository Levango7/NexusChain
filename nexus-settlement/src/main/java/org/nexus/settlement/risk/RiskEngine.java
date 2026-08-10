package org.nexus.settlement.risk;

/**
 * 风控引擎接口。
 * <p>
 * 基于规则链对交易进行实时风险评估，并支持动态增删规则。
 * </p>
 */
public interface RiskEngine {

    /**
     * 对交易进行风险评估。
     *
     * @param transaction 待评估交易
     * @return 风控决策
     */
    RiskDecision evaluate(Object transaction);

    /**
     * 新增风控规则。
     *
     * @param rule 风控规则
     */
    void addRule(RiskRule rule);

    /**
     * 移除指定规则。
     *
     * @param ruleId 规则 ID
     */
    void removeRule(String ruleId);
}