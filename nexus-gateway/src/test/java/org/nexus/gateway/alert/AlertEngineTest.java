package org.nexus.gateway.alert;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link AlertEngine} 单元测试：覆盖规则匹配、冷却逻辑、指标读取、通知分发。
 */
class AlertEngineTest {

    private AlertRuleRepository ruleRepository;
    private AlertEventRepository eventRepository;
    private MeterRegistry meterRegistry;
    private AlertNotifier notifier;
    private AlertEngine engine;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(AlertRuleRepository.class);
        eventRepository = mock(AlertEventRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        notifier = mock(AlertNotifier.class);
        when(notifier.channel()).thenReturn("test");

        engine = new AlertEngine(ruleRepository, eventRepository, meterRegistry, List.of(notifier));
    }

    // === 规则匹配 ===

    @Test
    @DisplayName("checkRule: GT 条件且值超过阈值 -> 触发告警")
    void checkRule_gtCondition_triggered() {
        Counter counter = Counter.builder("nexus.payments.failed").register(meterRegistry);
        counter.increment(15);

        AlertRule rule = createRule("failed-payments", "nexus.payments.failed",
                AlertRule.Condition.GT, 10, AlertRule.Severity.CRITICAL);

        engine.checkRule(rule);

        verify(eventRepository).save(any(AlertEvent.class));
        verify(notifier).notify(any(AlertEvent.class));
    }

    @Test
    @DisplayName("checkRule: GT 条件但值未超过阈值 -> 不触发")
    void checkRule_gtCondition_notTriggered() {
        Counter counter = Counter.builder("nexus.payments.failed").register(meterRegistry);
        counter.increment(5);

        AlertRule rule = createRule("failed-payments", "nexus.payments.failed",
                AlertRule.Condition.GT, 10, AlertRule.Severity.WARN);

        engine.checkRule(rule);

        verify(eventRepository, never()).save(any());
        verify(notifier, never()).notify(any());
    }

    @Test
    @DisplayName("checkRule: LT 条件且值低于阈值 -> 触发告警")
    void checkRule_ltCondition_triggered() {
        Counter counter = Counter.builder("nexus.payments.confirmed").register(meterRegistry);
        counter.increment(3);

        AlertRule rule = createRule("low-confirmations", "nexus.payments.confirmed",
                AlertRule.Condition.LT, 5, AlertRule.Severity.WARN);

        engine.checkRule(rule);

        verify(eventRepository).save(any(AlertEvent.class));
    }

    @Test
    @DisplayName("checkRule: EQ 条件且值等于阈值 -> 触发告警")
    void checkRule_eqCondition_triggered() {
        Counter counter = Counter.builder("nexus.orders.created").register(meterRegistry);
        counter.increment(10);

        AlertRule rule = createRule("exact-orders", "nexus.orders.created",
                AlertRule.Condition.EQ, 10, AlertRule.Severity.INFO);

        engine.checkRule(rule);

        verify(eventRepository).save(any(AlertEvent.class));
    }

    @Test
    @DisplayName("checkRule: 指标不存在 -> 不触发，不抛异常")
    void checkRule_metricNotFound_notTriggered() {
        AlertRule rule = createRule("missing-metric", "nexus.nonexistent.metric",
                AlertRule.Condition.GT, 1, AlertRule.Severity.WARN);

        engine.checkRule(rule);

        verify(eventRepository, never()).save(any());
        verify(notifier, never()).notify(any());
    }

    // === 冷却逻辑 ===

    @Test
    @DisplayName("checkRule: 冷却期内 -> 不重复触发")
    void checkRule_inCooldown_notTriggered() {
        Counter counter = Counter.builder("nexus.payments.failed").register(meterRegistry);
        counter.increment(15);

        AlertRule rule = createRule("failed-payments", "nexus.payments.failed",
                AlertRule.Condition.GT, 10, AlertRule.Severity.CRITICAL);
        rule.setCooldownMinutes(30);

        // 第一次触发
        engine.checkRule(rule);
        verify(eventRepository, times(1)).save(any(AlertEvent.class));

        // 第二次（冷却期内）不应触发
        engine.checkRule(rule);
        verify(eventRepository, times(1)).save(any(AlertEvent.class));
    }

    @Test
    @DisplayName("checkRule: 冷却期过后 -> 可再次触发")
    void checkRule_afterCooldown_triggered() {
        Counter counter = Counter.builder("nexus.payments.failed").register(meterRegistry);
        counter.increment(15);

        AlertRule rule = createRule("failed-payments", "nexus.payments.failed",
                AlertRule.Condition.GT, 10, AlertRule.Severity.CRITICAL);
        rule.setCooldownMinutes(0); // 冷却期为 0 分钟

        // 第一次触发
        engine.checkRule(rule);
        verify(eventRepository, times(1)).save(any(AlertEvent.class));

        // 冷却期为 0，立即可以再次触发
        engine.checkRule(rule);
        verify(eventRepository, times(2)).save(any(AlertEvent.class));
    }

    @Test
    @DisplayName("resetCooldown: 重置后可立即触发")
    void resetCooldown_allowsImmediateTrigger() {
        Counter counter = Counter.builder("nexus.payments.failed").register(meterRegistry);
        counter.increment(15);

        AlertRule rule = createRule("failed-payments", "nexus.payments.failed",
                AlertRule.Condition.GT, 10, AlertRule.Severity.CRITICAL);
        rule.setCooldownMinutes(60);

        // 第一次触发
        engine.checkRule(rule);
        verify(eventRepository, times(1)).save(any(AlertEvent.class));

        // 冷却期内不触发
        engine.checkRule(rule);
        verify(eventRepository, times(1)).save(any(AlertEvent.class));

        // 重置冷却后可触发
        engine.resetCooldown("failed-payments");
        engine.checkRule(rule);
        verify(eventRepository, times(2)).save(any(AlertEvent.class));
    }

    // === AlertRule.matches() 直接测试 ===

    @Test
    @DisplayName("AlertRule.matches: GT 条件判断")
    void ruleMatches_gt() {
        AlertRule rule = createRule("test", "metric", AlertRule.Condition.GT, 10, AlertRule.Severity.WARN);
        assertTrue(rule.matches(11));
        assertTrue(rule.matches(10.1));
        assertFalse(rule.matches(10));
        assertFalse(rule.matches(9));
    }

    @Test
    @DisplayName("AlertRule.matches: GTE 条件判断")
    void ruleMatches_gte() {
        AlertRule rule = createRule("test", "metric", AlertRule.Condition.GTE, 10, AlertRule.Severity.WARN);
        assertTrue(rule.matches(11));
        assertTrue(rule.matches(10));
        assertFalse(rule.matches(9.9));
    }

    @Test
    @DisplayName("AlertRule.matches: LTE 条件判断")
    void ruleMatches_lte() {
        AlertRule rule = createRule("test", "metric", AlertRule.Condition.LTE, 10, AlertRule.Severity.WARN);
        assertTrue(rule.matches(9));
        assertTrue(rule.matches(10));
        assertFalse(rule.matches(10.1));
    }

    @Test
    @DisplayName("AlertRule.matches: LT 条件判断")
    void ruleMatches_lt() {
        AlertRule rule = createRule("test", "metric", AlertRule.Condition.LT, 10, AlertRule.Severity.WARN);
        assertTrue(rule.matches(9));
        assertFalse(rule.matches(10));
        assertFalse(rule.matches(11));
    }

    @Test
    @DisplayName("AlertRule.matches: EQ 条件判断")
    void ruleMatches_eq() {
        AlertRule rule = createRule("test", "metric", AlertRule.Condition.EQ, 10, AlertRule.Severity.WARN);
        assertTrue(rule.matches(10));
        assertFalse(rule.matches(9));
        assertFalse(rule.matches(11));
    }

    // === 指标值读取 ===

    @Test
    @DisplayName("getMetricValue: Counter 类型 -> 返回 count 值")
    void getMetricValue_counter() {
        Counter counter = Counter.builder("nexus.test.counter").register(meterRegistry);
        counter.increment(5);
        counter.increment(3);

        Double value = engine.getMetricValue("nexus.test.counter");
        assertNotNull(value);
        assertEquals(8.0, value);
    }

    @Test
    @DisplayName("getMetricValue: 指标不存在 -> 返回 null")
    void getMetricValue_notFound() {
        Double value = engine.getMetricValue("nexus.nonexistent");
        assertNull(value);
    }

    // === 通知异常处理 ===

    @Test
    @DisplayName("triggerAlert: 单个 Notifier 抛异常 -> 不影响其他 Notifier")
    void triggerAlert_notifierException_doesNotPropagate() {
        AlertNotifier failingNotifier = mock(AlertNotifier.class);
        when(failingNotifier.channel()).thenReturn("failing");
        doThrow(new RuntimeException("send failed")).when(failingNotifier).notify(any());

        AlertNotifier goodNotifier = mock(AlertNotifier.class);
        when(goodNotifier.channel()).thenReturn("good");

        AlertEngine engineWithTwoNotifiers = new AlertEngine(
                ruleRepository, eventRepository, meterRegistry, List.of(failingNotifier, goodNotifier));

        Counter counter = Counter.builder("nexus.payments.failed").register(meterRegistry);
        counter.increment(15);

        AlertRule rule = createRule("failed-payments", "nexus.payments.failed",
                AlertRule.Condition.GT, 10, AlertRule.Severity.CRITICAL);

        engineWithTwoNotifiers.checkRule(rule);

        // 即使 failingNotifier 抛异常，goodNotifier 仍应被调用
        verify(goodNotifier).notify(any(AlertEvent.class));
        verify(eventRepository).save(any(AlertEvent.class));
    }

    // === Helper ===

    private AlertRule createRule(String name, String metricName,
                                  AlertRule.Condition condition, double threshold,
                                  AlertRule.Severity severity) {
        AlertRule rule = new AlertRule();
        rule.setName(name);
        rule.setMetricName(metricName);
        rule.setCondition(condition);
        rule.setThreshold(threshold);
        rule.setSeverity(severity);
        rule.setWindowMinutes(5);
        rule.setCooldownMinutes(10);
        rule.setEnabled(true);
        return rule;
    }
}