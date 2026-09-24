package org.nexus.settlement.risk.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 风控规则配置服务 — 加载 DB 规则配置、动态启用/禁用规则、CRUD 管理。
 *
 * <p>作为 DB 驱动规则配置的核心入口，支持：
 * <ul>
 *   <li>加载所有已启用的规则配置（按优先级排序）</li>
 *   <li>动态启用/禁用规则（无需重启）</li>
 *   <li>规则的增删改查管理</li>
 *   <li>按类型筛选规则配置</li>
 * </ul>
 * </p>
 */
@Service
public class RiskRuleConfigService {

    private static final Logger log = LoggerFactory.getLogger(RiskRuleConfigService.class);

    private final RiskRuleConfigRepository repository;

    public RiskRuleConfigService(RiskRuleConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * 加载所有已启用的规则配置，按优先级排序。
     *
     * @return 已启用的规则配置列表
     */
    public List<RiskRuleConfig> loadEnabledRules() {
        List<RiskRuleConfig> rules = repository.findByEnabledTrueOrderByPriorityAsc();
        log.debug("Loaded {} enabled risk rule configs", rules.size());
        return rules;
    }

    /**
     * 加载指定类型的已启用规则配置，按优先级排序。
     *
     * @param ruleType 规则类型
     * @return 已启用的规则配置列表
     */
    public List<RiskRuleConfig> loadEnabledRulesByType(RiskRuleConfig.RuleType ruleType) {
        List<RiskRuleConfig> rules = repository.findByEnabledTrueAndRuleTypeOrderByPriorityAsc(ruleType);
        log.debug("Loaded {} enabled risk rule configs of type {}", rules.size(), ruleType);
        return rules;
    }

    /**
     * 根据规则名称查找配置。
     *
     * @param ruleName 规则名称
     * @return 规则配置（可选）
     */
    public Optional<RiskRuleConfig> findByRuleName(String ruleName) {
        return repository.findByRuleName(ruleName);
    }

    /**
     * 创建新的规则配置。
     *
     * @param config 规则配置
     * @return 保存后的规则配置
     */
    @Transactional
    public RiskRuleConfig createRule(RiskRuleConfig config) {
        if (repository.existsByRuleName(config.getRuleName())) {
            throw new IllegalArgumentException("Rule name already exists: " + config.getRuleName());
        }
        RiskRuleConfig saved = repository.save(config);
        log.info("Created risk rule config: ruleName={}, type={}, priority={}",
                saved.getRuleName(), saved.getRuleType(), saved.getPriority());
        return saved;
    }

    /**
     * 更新规则配置。
     *
     * @param id     规则配置 ID
     * @param config 更新内容
     * @return 更新后的规则配置
     */
    @Transactional
    public RiskRuleConfig updateRule(Long id, RiskRuleConfig config) {
        RiskRuleConfig existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Rule config not found: id=" + id));

        if (config.getRuleName() != null) {
            existing.setRuleName(config.getRuleName());
        }
        if (config.getRuleType() != null) {
            existing.setRuleType(config.getRuleType());
        }
        if (config.getEnabled() != null) {
            existing.setEnabled(config.getEnabled());
        }
        if (config.getPriority() != null) {
            existing.setPriority(config.getPriority());
        }
        if (config.getThreshold() != null) {
            existing.setThreshold(config.getThreshold());
        }
        if (config.getAction() != null) {
            existing.setAction(config.getAction());
        }
        if (config.getConfigJson() != null) {
            existing.setConfigJson(config.getConfigJson());
        }

        RiskRuleConfig saved = repository.save(existing);
        log.info("Updated risk rule config: id={}, ruleName={}", saved.getId(), saved.getRuleName());
        return saved;
    }

    /**
     * 删除规则配置。
     *
     * @param id 规则配置 ID
     */
    @Transactional
    public void deleteRule(Long id) {
        repository.deleteById(id);
        log.info("Deleted risk rule config: id={}", id);
    }

    /**
     * 动态启用规则。
     *
     * @param ruleName 规则名称
     * @return 更新后的规则配置
     */
    @Transactional
    public RiskRuleConfig enableRule(String ruleName) {
        RiskRuleConfig config = repository.findByRuleName(ruleName)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleName));
        config.setEnabled(true);
        RiskRuleConfig saved = repository.save(config);
        log.info("Enabled risk rule: ruleName={}", ruleName);
        return saved;
    }

    /**
     * 动态禁用规则。
     *
     * @param ruleName 规则名称
     * @return 更新后的规则配置
     */
    @Transactional
    public RiskRuleConfig disableRule(String ruleName) {
        RiskRuleConfig config = repository.findByRuleName(ruleName)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleName));
        config.setEnabled(false);
        RiskRuleConfig saved = repository.save(config);
        log.info("Disabled risk rule: ruleName={}", ruleName);
        return saved;
    }

    /**
     * 获取所有规则配置。
     *
     * @return 所有规则配置列表
     */
    public List<RiskRuleConfig> findAllRules() {
        return repository.findAll();
    }
}