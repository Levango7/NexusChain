package org.nexus.gateway.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link AlertEscalationService} 单元测试：覆盖升级条件判断、升级执行、通知分发。
 */
class AlertEscalationServiceTest {

    private AlertEscalationRuleRepository escalationRuleRepository;
    private AlertEventRepository eventRepository;
    private AlertNotifier notifier;
    private AlertEscalationService service;

    @BeforeEach
    void setUp() {
        escalationRuleRepository = mock(AlertEscalationRuleRepository.class);
        eventRepository = mock(AlertEventRepository.class);
        notifier = mock(AlertNotifier.class);
        when(notifier.channel()).thenReturn("test");

        service = new AlertEscalationService(escalationRuleRepository, eventRepository, List.of(notifier));
    }

    // === 升级条件判断 ===

    @Test
    @DisplayName("shouldEscalate: 规则名匹配 + 严重级别匹配 + 超时 -> true")
    void shouldEscalate_allMatch_true() {
        AlertEvent event = createEvent(1L, "failed-payments", AlertRule.Severity.WARN,
                LocalDateTime.now().minusMinutes(35));
        AlertEscalationRule rule = createEscalationRule(1L, "failed-payments", 30,
                AlertRule.Severity.WARN, AlertRule.Severity.CRITICAL, "webhook");
        LocalDateTime now = LocalDateTime.now();

        assertTrue(service.shouldEscalate(event, rule, now));
    }

    @Test
    @DisplayName("shouldEscalate: 规则名不匹配 -> false")
    void shouldEscalate_ruleNameMismatch_false() {
        AlertEvent event = createEvent(1L, "failed-payments", AlertRule.Severity.WARN,
                LocalDateTime.now().minusMinutes(35));
        AlertEscalationRule rule = createEscalationRule(1L, "other-rule", 30,
                AlertRule.Severity.WARN, AlertRule.Severity.CRITICAL, "webhook");
        LocalDateTime now = LocalDateTime.now();

        assertFalse(service.shouldEscalate(event, rule, now));
    }

    @Test
    @DisplayName("shouldEscalate: 严重级别不匹配 -> false")
    void shouldEscalate_severityMismatch_false() {
        AlertEvent event = createEvent(1L, "failed-payments", AlertRule.Severity.CRITICAL,
                LocalDateTime.now().minusMinutes(35));
        AlertEscalationRule rule = createEscalationRule(1L, "failed-payments", 30,
                AlertRule.Severity.WARN, AlertRule.Severity.CRITICAL, "webhook");
        LocalDateTime now = LocalDateTime.now();

        assertFalse(service.shouldEscalate(event, rule, now));
    }

    @Test
    @DisplayName("shouldEscalate: 未超时 -> false")
    void shouldEscalate_notTimedOut_false() {
        AlertEvent event = createEvent(1L, "failed-payments", AlertRule.Severity.WARN,
                LocalDateTime.now().minusMinutes(10));
        AlertEscalationRule rule = createEscalationRule(1L, "failed-payments", 30,
                AlertRule.Severity.WARN, AlertRule.Severity.CRITICAL, "webhook");
        LocalDateTime now = LocalDateTime.now();

        assertFalse(service.shouldEscalate(event, rule, now));
    }

    @Test
    @DisplayName("shouldEscalate: 刚好超时（等于 escalateAfterMinutes） -> false（需严格大于）")
    void shouldEscalate_exactTimeout_false() {
        LocalDateTime now = LocalDateTime.now();
        AlertEvent event = createEvent(1L, "failed-payments", AlertRule.Severity.WARN,
                now.minusMinutes(30));
        AlertEscalationRule rule = createEscalationRule(1L, "failed-payments", 30,
                AlertRule.Severity.WARN, AlertRule.Severity.CRITICAL, "webhook");

        // event.timestamp + 30min == now，now.isAfter(escalateAfter) 为 false（需严格大于）
        assertFalse(service.shouldEscalate(event, rule, now));
    }

    // === 升级执行 ===

    @Test
    @DisplayName("escalate: 更新告警事件严重级别 + 发送通知")
    void escalate_updatesSeverityAndNotifies() {
        AlertEvent event = createEvent(1L, "failed-payments", AlertRule.Severity.WARN,
                LocalDateTime.now().minusMinutes(35));
        AlertEscalationRule rule = createEscalationRule(1L, "failed-payments", 30,
                AlertRule.Severity.WARN, AlertRule.Severity.CRITICAL, "webhook");

        when(eventRepository.save(any(AlertEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        service.escalate(event, rule);

        verify(eventRepository).save(any(AlertEvent.class));
        verify(notifier).notify(any(AlertEvent.class));
        assertEquals(AlertRule.Severity.CRITICAL, event.getSeverity());
        assertTrue(event.getMessage().contains("ESCALATED"));
    }

    @Test
    @DisplayName("escalate: 通知器抛异常 -> 不影响其他通知器")
    void escalate_notifierException() {
        AlertNotifier failingNotifier = mock(AlertNotifier.class);
        when(failingNotifier.channel()).thenReturn("failing");
        doThrow(new RuntimeException("send failed")).when(failingNotifier).notify(any());

        AlertEscalationService serviceWithTwoNotifiers = new AlertEscalationService(
                escalationRuleRepository, eventRepository, List.of(failingNotifier, notifier));

        AlertEvent event = createEvent(1L, "failed-payments", AlertRule.Severity.WARN,
                LocalDateTime.now().minusMinutes(35));
        AlertEscalationRule rule = createEscalationRule(1L, "failed-payments", 30,
                AlertRule.Severity.WARN, AlertRule.Severity.CRITICAL, "webhook");

        when(eventRepository.save(any(AlertEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        serviceWithTwoNotifiers.escalate(event, rule);

        verify(notifier).notify(any(AlertEvent.class));
        verify(eventRepository).save(any(AlertEvent.class));
    }

    // === 定时扫描 ===

    @Test
    @DisplayName("checkEscalations: 无未解决告警 -> 不执行升级")
    void checkEscalations_noUnresolved() {
        when(eventRepository.findByResolvedFalseOrderByTimestampDesc()).thenReturn(List.of());

        service.checkEscalations();

        verify(escalationRuleRepository, never()).findByEnabledTrue();
    }

    @Test
    @DisplayName("checkEscalations: 无升级规则 -> 不执行升级")
    void checkEscalations_noRules() {
        AlertEvent event = createEvent(1L, "failed-payments", AlertRule.Severity.WARN,
                LocalDateTime.now().minusMinutes(35));

        when(eventRepository.findByResolvedFalseOrderByTimestampDesc()).thenReturn(List.of(event));
        when(escalationRuleRepository.findByEnabledTrue()).thenReturn(List.of());

        service.checkEscalations();

        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("checkEscalations: 满足升级条件 -> 执行升级")
    void checkEscalations_escalatesMatchingEvent() {
        AlertEvent event = createEvent(1L, "failed-payments", AlertRule.Severity.WARN,
                LocalDateTime.now().minusMinutes(35));
        AlertEscalationRule rule = createEscalationRule(1L, "failed-payments", 30,
                AlertRule.Severity.WARN, AlertRule.Severity.CRITICAL, "webhook");

        when(eventRepository.findByResolvedFalseOrderByTimestampDesc()).thenReturn(List.of(event));
        when(escalationRuleRepository.findByEnabledTrue()).thenReturn(List.of(rule));
        when(eventRepository.save(any(AlertEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        service.checkEscalations();

        verify(eventRepository).save(any(AlertEvent.class));
        verify(notifier).notify(any(AlertEvent.class));
        assertEquals(AlertRule.Severity.CRITICAL, event.getSeverity());
    }

    @Test
    @DisplayName("checkEscalations: 不满足升级条件 -> 不执行升级")
    void checkEscalations_doesNotEscalateNonMatching() {
        AlertEvent event = createEvent(1L, "failed-payments", AlertRule.Severity.WARN,
                LocalDateTime.now().minusMinutes(10)); // 未超时
        AlertEscalationRule rule = createEscalationRule(1L, "failed-payments", 30,
                AlertRule.Severity.WARN, AlertRule.Severity.CRITICAL, "webhook");

        when(eventRepository.findByResolvedFalseOrderByTimestampDesc()).thenReturn(List.of(event));
        when(escalationRuleRepository.findByEnabledTrue()).thenReturn(List.of(rule));

        service.checkEscalations();

        verify(eventRepository, never()).save(any());
        verify(notifier, never()).notify(any());
    }

    @Test
    @DisplayName("checkEscalations: 多个事件只匹配部分 -> 只升级匹配的")
    void checkEscalations_partialMatch() {
        AlertEvent event1 = createEvent(1L, "failed-payments", AlertRule.Severity.WARN,
                LocalDateTime.now().minusMinutes(35)); // 满足升级
        AlertEvent event2 = createEvent(2L, "low-confirmations", AlertRule.Severity.WARN,
                LocalDateTime.now().minusMinutes(10)); // 未超时

        AlertEscalationRule rule1 = createEscalationRule(1L, "failed-payments", 30,
                AlertRule.Severity.WARN, AlertRule.Severity.CRITICAL, "webhook");
        AlertEscalationRule rule2 = createEscalationRule(2L, "low-confirmations", 30,
                AlertRule.Severity.WARN, AlertRule.Severity.CRITICAL, "webhook");

        when(eventRepository.findByResolvedFalseOrderByTimestampDesc()).thenReturn(List.of(event1, event2));
        when(escalationRuleRepository.findByEnabledTrue()).thenReturn(List.of(rule1, rule2));
        when(eventRepository.save(any(AlertEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        service.checkEscalations();

        // 只有 event1 被升级
        verify(eventRepository, times(1)).save(any(AlertEvent.class));
        assertEquals(AlertRule.Severity.CRITICAL, event1.getSeverity());
        assertEquals(AlertRule.Severity.WARN, event2.getSeverity());
    }

    // === Helper ===

    private AlertEvent createEvent(Long id, String ruleName, AlertRule.Severity severity,
                                     LocalDateTime timestamp) {
        AlertEvent event = new AlertEvent();
        event.setId(id);
        event.setRuleName(ruleName);
        event.setMetricName("nexus.test.metric");
        event.setCurrentValue(15.0);
        event.setThreshold(10.0);
        event.setSeverity(severity);
        event.setMessage("test message");
        event.setTimestamp(timestamp);
        event.setResolved(false);
        return event;
    }

    private AlertEscalationRule createEscalationRule(Long id, String ruleName, int escalateAfterMinutes,
                                                       AlertRule.Severity fromSeverity, AlertRule.Severity toSeverity,
                                                       String escalationChannel) {
        AlertEscalationRule rule = new AlertEscalationRule();
        rule.setId(id);
        rule.setRuleName(ruleName);
        rule.setEscalateAfterMinutes(escalateAfterMinutes);
        rule.setFromSeverity(fromSeverity);
        rule.setToSeverity(toSeverity);
        rule.setEscalationChannel(escalationChannel);
        rule.setEnabled(true);
        return rule;
    }
}