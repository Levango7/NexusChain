package org.nexus.settlement.risk;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 默认风控引擎骨架实现。
 * <p>
 * 采用规则链模式：依次执行所有注册规则，命中任意拦截规则即返回 {@link RiskDecision#REJECTED}。
 * 当前为骨架实现，规则链装配与决策策略留待后续填充。
 * </p>
 */
@Service
public class DefaultRiskEngine implements RiskEngine {

    /** 规则注册表（按 ruleId 索引，保持插入顺序） */
    private final Map<String, RiskRule> rules = new LinkedHashMap<>();

    @Override
    public RiskDecision evaluate(Object transaction) {
        // TODO: 实现规则链评估策略（短路拦截 / 累积评分 / 人工复核阈值等）
        for (RiskRule rule : rules.values()) {
            if (rule.check(transaction)) {
                return RiskDecision.REJECTED;
            }
        }
        return RiskDecision.APPROVED;
    }

    @Override
    public void addRule(RiskRule rule) {
        // TODO: 校验规则合法性、冲突检测、热加载通知
        if (Objects.nonNull(rule) && Objects.nonNull(rule.getRuleId())) {
            rules.put(rule.getRuleId(), rule);
        }
    }

    @Override
    public void removeRule(String ruleId) {
        // TODO: 移除后触发规则链重排与审计日志
        if (Objects.nonNull(ruleId)) {
            rules.remove(ruleId);
        }
    }
}