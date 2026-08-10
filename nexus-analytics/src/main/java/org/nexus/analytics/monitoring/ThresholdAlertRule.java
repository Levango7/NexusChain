package org.nexus.analytics.monitoring;

import java.util.Map;
import java.util.Optional;

/**
 * 基于数值阈值的告警规则实现。
 *
 * <p>对指标中的指定键求值：根据比较方向判断是否命中——
 * <ul>
 *   <li>{@link Direction#ABOVE}：数值超过阈值即命中（如 syncLag / pendingCount）</li>
 *   <li>{@link Direction#BELOW}：数值低于阈值即命中（如 peerCount 过低）</li>
 * </ul>
 * 命中则产出对应级别告警，交由 {@link ChainMonitorService} 统一驱动求值。
 */
public class ThresholdAlertRule implements AlertRule {

    /** 比较方向枚举 */
    public enum Direction {
        /** 超过阈值命中 */
        ABOVE,
        /** 低于阈值命中 */
        BELOW
    }

    /** 规则名 */
    private final String ruleName;

    /** 指标键 */
    private final String metricKey;

    /** 阈值 */
    private final double threshold;

    /** 比较方向 */
    private final Direction direction;

    /** 命中时告警级别 */
    private final Alert.Level level;

    /** 告警来源标签 */
    private final String source;

    public ThresholdAlertRule(String ruleName, String metricKey, double threshold,
                              Direction direction, Alert.Level level, String source) {
        this.ruleName = ruleName;
        this.metricKey = metricKey;
        this.threshold = threshold;
        this.direction = direction;
        this.level = level;
        this.source = source;
    }

    @Override
    public String name() {
        return ruleName;
    }

    @Override
    public Optional<Alert> evaluate(Map<String, Object> metric) {
        if (metric == null) {
            return Optional.empty();
        }
        Object raw = metric.get(metricKey);
        if (!(raw instanceof Number)) {
            return Optional.empty();
        }
        double value = ((Number) raw).doubleValue();
        boolean hit = direction == Direction.ABOVE ? value > threshold : value < threshold;
        if (!hit) {
            return Optional.empty();
        }
        String comparator = direction == Direction.ABOVE ? "exceeds" : "below";
        Alert alert = Alert.builder()
                .level(level)
                .source(source)
                .content(String.format("%s=%s %s threshold=%s", metricKey, value, comparator, threshold))
                .state(Alert.State.OPEN)
                .metric(metricKey)
                .metricValue(value)
                .build();
        return Optional.of(alert);
    }
}
