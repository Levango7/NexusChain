package org.nexus.gateway.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警抑制服务。
 *
 * <p>当父告警激活时自动抑制子告警，抑制超时后自动解除。
 * 抑制状态维护在内存 Map 中（parentRuleName → 抑制到期时间），
 * 每次检查抑制状态时自动清理已过期的抑制记录。</p>
 *
 * <p>工作流程：
 * <ul>
 *   <li>父告警触发时，调用 {@link #activateSuppression(String)} 激活抑制</li>
 *   <li>子告警触发前，调用 {@link #isSuppressed(String)} 检查是否被抑制</li>
 *   <li>抑制持续时间过后，抑制自动解除（到期时间过期）</li>
 * </ul>
 * </p>
 */
@Service
@ConditionalOnProperty(name = "nexus.alert.enabled", havingValue = "true", matchIfMissing = true)
public class AlertSuppressionService {

    private static final Logger log = LoggerFactory.getLogger(AlertSuppressionService.class);

    private final AlertSuppressionRuleRepository suppressionRuleRepository;

    /**
     * 抑制状态：parentRuleName → 抑制到期时间。
     * 当父告警激活时，记录抑制到期时间；子告警检查时，若当前时间在到期时间之前则被抑制。
     */
    private final Map<String, LocalDateTime> activeSuppressions = new ConcurrentHashMap<>();

    public AlertSuppressionService(AlertSuppressionRuleRepository suppressionRuleRepository) {
        this.suppressionRuleRepository = suppressionRuleRepository;
    }

    /**
     * 当父告警触发时激活抑制。
     *
     * <p>查找所有以 parentRuleName 为父规则的启用抑制规则，
     * 为每条规则设置抑制到期时间（当前时间 + suppressDurationMinutes）。</p>
     *
     * @param parentRuleName 父告警规则名称
     */
    @Transactional
    public void activateSuppression(String parentRuleName) {
        List<AlertSuppressionRule> rules = suppressionRuleRepository
                .findByParentRuleNameAndEnabledTrue(parentRuleName);

        if (rules.isEmpty()) {
            log.debug("No suppression rules found for parent rule '{}'", parentRuleName);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (AlertSuppressionRule rule : rules) {
            LocalDateTime suppressUntil = now.plusMinutes(rule.getSuppressDurationMinutes());
            activeSuppressions.put(rule.getChildRuleName(), suppressUntil);
            log.info("Suppression activated: parent='{}' child='{}' until={}",
                    parentRuleName, rule.getChildRuleName(), suppressUntil);
        }
    }

    /**
     * 检查子告警规则是否被抑制。
     *
     * <p>若子规则在 activeSuppressions 中且抑制到期时间尚未过期，则返回 true。
     * 若抑制已过期，自动清理该抑制记录并返回 false。</p>
     *
     * @param childRuleName 子告警规则名称
     * @return true 表示被抑制，不应触发告警通知
     */
    public boolean isSuppressed(String childRuleName) {
        LocalDateTime suppressUntil = activeSuppressions.get(childRuleName);
        if (suppressUntil == null) {
            return false;
        }

        if (!LocalDateTime.now().isBefore(suppressUntil)) {
            // 抑制已过期，自动清理
            activeSuppressions.remove(childRuleName);
            log.debug("Suppression expired for child rule '{}', auto-removed", childRuleName);
            return false;
        }

        log.debug("Child rule '{}' is suppressed until {}", childRuleName, suppressUntil);
        return true;
    }

    /**
     * 手动解除指定子规则的抑制状态。
     *
     * @param childRuleName 子告警规则名称
     */
    public void removeSuppression(String childRuleName) {
        activeSuppressions.remove(childRuleName);
        log.info("Suppression manually removed for child rule '{}'", childRuleName);
    }

    /**
     * 清理所有已过期的抑制记录。
     *
     * <p>P1-6 修复：原实现在遍历 entrySet 时调用 {@code activeSuppressions.remove(key)}，
     * 依赖 ConcurrentHashMap 弱一致性，且删除逻辑与遍历耦合。改用
     * {@code entrySet().removeIf(...)} 原子化移除已过期条目，行为更明确、清理更彻底。</p>
     */
    public void cleanupExpiredSuppressions() {
        LocalDateTime now = LocalDateTime.now();
        // 统计清理前大小，用于计算移除数量（ConcurrentHashMap 的 removeIf 不返回计数）
        int before = activeSuppressions.size();
        activeSuppressions.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
        int removed = before - activeSuppressions.size();
        if (removed > 0) {
            log.info("Cleaned up {} expired suppression records", removed);
        }
    }

    /**
     * 获取当前活跃的抑制状态（主要用于测试和运维查看）。
     *
     * @return 不可变的抑制状态映射
     */
    public Map<String, LocalDateTime> getActiveSuppressions() {
        return Map.copyOf(activeSuppressions);
    }

    /**
     * 重置所有抑制状态（主要用于测试）。
     */
    public void resetAllSuppressions() {
        activeSuppressions.clear();
    }
}