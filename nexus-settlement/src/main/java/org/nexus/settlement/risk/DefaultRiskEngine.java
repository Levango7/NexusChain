package org.nexus.settlement.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 默认风控引擎实现。
 * <p>
 * 采用规则链模式：依次执行所有注册规则，命中任意拦截规则即返回 {@link RiskDecision#REJECTED}。
 * 规则通过构造器注入（Spring 容器中所有 {@link RiskRule} Bean），并支持运行期动态增删。
 * </p>
 */
@Service
public class DefaultRiskEngine implements RiskEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultRiskEngine.class);

    /** 规则注册表（按 ruleId 索引，保持插入顺序） */
    private final Map<String, RiskRule> rules = new LinkedHashMap<>();

    /**
     * 构造时注入容器中所有风控规则 Bean。
     *
     * @param registeredRules Spring 容器中扫描到的规则列表（可为空）
     */
    public DefaultRiskEngine(List<RiskRule> registeredRules) {
        if (registeredRules != null) {
            for (RiskRule rule : registeredRules) {
                addRule(rule);
            }
        }
        log.info("RiskEngine initialized with {} rules: {}", rules.size(), rules.keySet());
    }

    @Override
    public RiskDecision evaluate(Object transaction) {
        // 短路拦截：命中任意规则即拒绝；累积评分 / 人工复核阈值策略留待后续扩展
        for (RiskRule rule : rules.values()) {
            if (rule.check(transaction)) {
                log.warn("Risk rule {} rejected transaction: {}", rule.getRuleId(), transaction);
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
