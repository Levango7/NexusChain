package org.nexus.analytics.monitoring;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ThresholdAlertRule} 补充测试。
 *
 * <p>覆盖 null 指标、缺失键、边界值（等于阈值不命中）与告警字段填充。
 */
class ThresholdAlertRuleTest {

    @Test
    void evaluate_nullMetric_shouldReturnEmpty() {
        ThresholdAlertRule rule = new ThresholdAlertRule(
                "r", "m", 10, ThresholdAlertRule.Direction.ABOVE, Alert.Level.WARN, "SRC");

        assertTrue(rule.evaluate(null).isEmpty());
    }

    @Test
    void evaluate_missingKey_shouldReturnEmpty() {
        ThresholdAlertRule rule = new ThresholdAlertRule(
                "r", "missing", 10, ThresholdAlertRule.Direction.ABOVE, Alert.Level.WARN, "SRC");

        assertTrue(rule.evaluate(Map.of("other", 15)).isEmpty());
    }

    @Test
    void evaluate_aboveDirection_atThreshold_shouldNotFire() {
        // 严格大于：等于阈值不命中
        ThresholdAlertRule rule = new ThresholdAlertRule(
                "r", "m", 10, ThresholdAlertRule.Direction.ABOVE, Alert.Level.WARN, "SRC");

        assertTrue(rule.evaluate(Map.of("m", 10)).isEmpty());
    }

    @Test
    void evaluate_belowDirection_atThreshold_shouldNotFire() {
        // 严格小于：等于阈值不命中
        ThresholdAlertRule rule = new ThresholdAlertRule(
                "r", "m", 10, ThresholdAlertRule.Direction.BELOW, Alert.Level.WARN, "SRC");

        assertTrue(rule.evaluate(Map.of("m", 10)).isEmpty());
    }

    @Test
    void evaluate_aboveHit_shouldPopulateAllFields() {
        ThresholdAlertRule rule = new ThresholdAlertRule(
                "rule.x", "m", 10, ThresholdAlertRule.Direction.ABOVE, Alert.Level.CRITICAL, "NODE");

        Optional<Alert> result = rule.evaluate(Map.of("m", 15.5));

        assertTrue(result.isPresent());
        Alert alert = result.get();
        assertEquals(Alert.Level.CRITICAL, alert.getLevel());
        assertEquals("NODE", alert.getSource());
        assertEquals("m", alert.getMetric());
        assertEquals(15.5, alert.getMetricValue());
        assertEquals(Alert.State.OPEN, alert.getState());
        assertTrue(alert.getContent().contains("exceeds"));
        assertTrue(alert.getContent().contains("15.5"));
    }

    @Test
    void evaluate_belowHit_shouldContainBelowKeyword() {
        ThresholdAlertRule rule = new ThresholdAlertRule(
                "rule.y", "m", 10, ThresholdAlertRule.Direction.BELOW, Alert.Level.WARN, "SRC");

        Optional<Alert> result = rule.evaluate(Map.of("m", 5));

        assertTrue(result.isPresent());
        assertTrue(result.get().getContent().contains("below"));
    }

    @Test
    void evaluate_longValue_shouldConvertToDouble() {
        ThresholdAlertRule rule = new ThresholdAlertRule(
                "r", "m", 100, ThresholdAlertRule.Direction.ABOVE, Alert.Level.WARN, "SRC");

        assertTrue(rule.evaluate(Map.of("m", 200L)).isPresent());
    }

    @Test
    void name_shouldReturnRuleName() {
        ThresholdAlertRule rule = new ThresholdAlertRule(
                "my.rule.name", "m", 10, ThresholdAlertRule.Direction.ABOVE, Alert.Level.WARN, "SRC");

        assertEquals("my.rule.name", rule.name());
    }
}