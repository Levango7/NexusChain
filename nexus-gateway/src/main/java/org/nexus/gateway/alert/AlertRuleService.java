package org.nexus.gateway.alert;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 告警规则服务。
 *
 * <p>P1-1 架构修复：将 {@link AlertRuleRepository} 的数据访问从
 * {@link AlertController} 下沉到本服务，Controller 只负责 HTTP 编排，
 * 不再直接依赖 Repository（符合分层架构约束）。</p>
 *
 * <p>注意：{@link AlertEngine} 仍按原样直接注入 Repository 进行指标扫描，
 * 本服务不影响其行为。</p>
 */
@Service
public class AlertRuleService {

    private final AlertRuleRepository ruleRepository;

    public AlertRuleService(AlertRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    /**
     * 查询全部告警规则。
     *
     * @return 规则列表
     */
    public List<AlertRule> findAll() {
        return ruleRepository.findAll();
    }

    /**
     * 判断规则名称是否已存在。
     *
     * @param name 规则名称
     * @return true 表示已存在
     */
    public boolean existsByName(String name) {
        return ruleRepository.existsByName(name);
    }

    /**
     * 保存（新建或更新）告警规则。
     *
     * @param rule 规则实体
     * @return 保存后的实体
     */
    @Transactional
    public AlertRule save(AlertRule rule) {
        return ruleRepository.save(rule);
    }

    /**
     * 按 ID 查询规则。
     *
     * @param id 规则 ID
     * @return 规则（可能为空）
     */
    public Optional<AlertRule> findById(Long id) {
        return ruleRepository.findById(id);
    }

    /**
     * 判断规则是否存在。
     *
     * @param id 规则 ID
     * @return true 表示存在
     */
    public boolean existsById(Long id) {
        return ruleRepository.existsById(id);
    }

    /**
     * 按 ID 删除规则。
     *
     * @param id 规则 ID
     */
    @Transactional
    public void deleteById(Long id) {
        ruleRepository.deleteById(id);
    }

    /**
     * 部分更新规则：仅覆盖请求中显式提供的字段。
     *
     * @param id      规则 ID
     * @param updates 更新内容
     * @return 更新后的规则；规则不存在时返回空
     */
    @Transactional
    public Optional<AlertRule> update(Long id, AlertRule updates) {
        return ruleRepository.findById(id)
                .map(existing -> {
                    applyUpdates(existing, updates);
                    return ruleRepository.save(existing);
                });
    }

    /**
     * 将请求体中的字段应用到已有实体（部分更新）。
     */
    private void applyUpdates(AlertRule existing, AlertRule updates) {
        if (updates.getName() != null) {
            existing.setName(updates.getName());
        }
        if (updates.getMetricName() != null) {
            existing.setMetricName(updates.getMetricName());
        }
        if (updates.getCondition() != null) {
            existing.setCondition(updates.getCondition());
        }
        existing.setThreshold(updates.getThreshold());
        existing.setWindowMinutes(updates.getWindowMinutes());
        existing.setCooldownMinutes(updates.getCooldownMinutes());
        existing.setEnabled(updates.isEnabled());
        if (updates.getSeverity() != null) {
            existing.setSeverity(updates.getSeverity());
        }
        if (updates.getDescription() != null) {
            existing.setDescription(updates.getDescription());
        }
    }
}