package org.nexus.gateway.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link AlertSuppressionService} 单元测试：覆盖抑制激活、抑制检查、自动解除、手动解除。
 */
class AlertSuppressionServiceTest {

    private AlertSuppressionRuleRepository suppressionRuleRepository;
    private AlertSuppressionService service;

    @BeforeEach
    void setUp() {
        suppressionRuleRepository = mock(AlertSuppressionRuleRepository.class);
        service = new AlertSuppressionService(suppressionRuleRepository);
    }

    // === 抑制激活 ===

    @Test
    @DisplayName("activateSuppression: 父告警触发 -> 激活子告警抑制")
    void activateSuppression_activatesChildSuppression() {
        AlertSuppressionRule rule = createSuppressionRule(1L, "db-connection-failure",
                "payment-service-error", 30);

        when(suppressionRuleRepository.findByParentRuleNameAndEnabledTrue("db-connection-failure"))
                .thenReturn(List.of(rule));

        service.activateSuppression("db-connection-failure");

        assertTrue(service.isSuppressed("payment-service-error"));
    }

    @Test
    @DisplayName("activateSuppression: 无匹配抑制规则 -> 不激活抑制")
    void activateSuppression_noRules() {
        when(suppressionRuleRepository.findByParentRuleNameAndEnabledTrue("nonexistent-rule"))
                .thenReturn(List.of());

        service.activateSuppression("nonexistent-rule");

        assertFalse(service.isSuppressed("any-child-rule"));
    }

    @Test
    @DisplayName("activateSuppression: 多个子规则 -> 全部激活")
    void activateSuppression_multipleChildren() {
        AlertSuppressionRule rule1 = createSuppressionRule(1L, "db-connection-failure",
                "payment-service-error", 30);
        AlertSuppressionRule rule2 = createSuppressionRule(2L, "db-connection-failure",
                "order-service-error", 30);

        when(suppressionRuleRepository.findByParentRuleNameAndEnabledTrue("db-connection-failure"))
                .thenReturn(List.of(rule1, rule2));

        service.activateSuppression("db-connection-failure");

        assertTrue(service.isSuppressed("payment-service-error"));
        assertTrue(service.isSuppressed("order-service-error"));
    }

    // === 抑制检查 ===

    @Test
    @DisplayName("isSuppressed: 未被抑制 -> false")
    void isSuppressed_notSuppressed() {
        assertFalse(service.isSuppressed("any-rule"));
    }

    @Test
    @DisplayName("isSuppressed: 被抑制 -> true")
    void isSuppressed_isSuppressed() {
        AlertSuppressionRule rule = createSuppressionRule(1L, "parent-rule",
                "child-rule", 30);

        when(suppressionRuleRepository.findByParentRuleNameAndEnabledTrue("parent-rule"))
                .thenReturn(List.of(rule));

        service.activateSuppression("parent-rule");

        assertTrue(service.isSuppressed("child-rule"));
    }

    // === 自动解除 ===

    @Test
    @DisplayName("isSuppressed: 抑制超时 -> 自动解除，返回 false")
    void isSuppressed_expiredAutoRemoves() {
        AlertSuppressionRule rule = createSuppressionRule(1L, "parent-rule",
                "child-rule", 0); // 0分钟 = 立即过期

        when(suppressionRuleRepository.findByParentRuleNameAndEnabledTrue("parent-rule"))
                .thenReturn(List.of(rule));

        service.activateSuppression("parent-rule");

        // suppressDurationMinutes=0，抑制立即过期
        assertFalse(service.isSuppressed("child-rule"));
    }

    @Test
    @DisplayName("cleanupExpiredSuppressions: 清理过期抑制记录")
    void cleanupExpiredSuppressions_removesExpired() {
        AlertSuppressionRule rule = createSuppressionRule(1L, "parent-rule",
                "child-rule", 0); // 0分钟 = 立即过期

        when(suppressionRuleRepository.findByParentRuleNameAndEnabledTrue("parent-rule"))
                .thenReturn(List.of(rule));

        service.activateSuppression("parent-rule");

        // 抑制已过期但尚未被检查
        assertEquals(1, service.getActiveSuppressions().size());

        service.cleanupExpiredSuppressions();

        assertEquals(0, service.getActiveSuppressions().size());
    }

    // === 手动解除 ===

    @Test
    @DisplayName("removeSuppression: 手动解除指定子规则抑制")
    void removeSuppression_manualRemove() {
        AlertSuppressionRule rule = createSuppressionRule(1L, "parent-rule",
                "child-rule", 30);

        when(suppressionRuleRepository.findByParentRuleNameAndEnabledTrue("parent-rule"))
                .thenReturn(List.of(rule));

        service.activateSuppression("parent-rule");
        assertTrue(service.isSuppressed("child-rule"));

        service.removeSuppression("child-rule");
        assertFalse(service.isSuppressed("child-rule"));
    }

    @Test
    @DisplayName("resetAllSuppressions: 重置所有抑制状态")
    void resetAllSuppressions() {
        AlertSuppressionRule rule1 = createSuppressionRule(1L, "parent-a", "child-a", 30);
        AlertSuppressionRule rule2 = createSuppressionRule(2L, "parent-b", "child-b", 30);

        when(suppressionRuleRepository.findByParentRuleNameAndEnabledTrue("parent-a"))
                .thenReturn(List.of(rule1));
        when(suppressionRuleRepository.findByParentRuleNameAndEnabledTrue("parent-b"))
                .thenReturn(List.of(rule2));

        service.activateSuppression("parent-a");
        service.activateSuppression("parent-b");

        assertEquals(2, service.getActiveSuppressions().size());

        service.resetAllSuppressions();

        assertEquals(0, service.getActiveSuppressions().size());
        assertFalse(service.isSuppressed("child-a"));
        assertFalse(service.isSuppressed("child-b"));
    }

    // === Helper ===

    private AlertSuppressionRule createSuppressionRule(Long id, String parentRuleName,
                                                         String childRuleName, int suppressDurationMinutes) {
        AlertSuppressionRule rule = new AlertSuppressionRule();
        rule.setId(id);
        rule.setParentRuleName(parentRuleName);
        rule.setChildRuleName(childRuleName);
        rule.setSuppressDurationMinutes(suppressDurationMinutes);
        rule.setEnabled(true);
        return rule;
    }
}