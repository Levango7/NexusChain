package org.nexus.settlement.risk.config;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 风控规则配置 Repository。
 */
@Repository
public interface RiskRuleConfigRepository extends JpaRepository<RiskRuleConfig, Long> {

    /**
     * 根据规则名称查找配置。
     *
     * @param ruleName 规则名称
     * @return 规则配置（可选）
     */
    Optional<RiskRuleConfig> findByRuleName(String ruleName);

    /**
     * 查找所有已启用的规则配置，按优先级排序。
     *
     * @return 已启用的规则配置列表
     */
    List<RiskRuleConfig> findByEnabledTrueOrderByPriorityAsc();

    /**
     * 查找指定类型的已启用规则配置，按优先级排序。
     *
     * @param ruleType 规则类型
     * @return 已启用的规则配置列表
     */
    List<RiskRuleConfig> findByEnabledTrueAndRuleTypeOrderByPriorityAsc(RiskRuleConfig.RuleType ruleType);

    /**
     * 检查规则名称是否已存在。
     *
     * @param ruleName 规则名称
     * @return 是否存在
     */
    boolean existsByRuleName(String ruleName);
}