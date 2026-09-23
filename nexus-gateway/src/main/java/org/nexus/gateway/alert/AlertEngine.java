package org.nexus.gateway.alert;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警引擎服务。
 *
 * <p>定时（默认每 30 秒）扫描所有 enabled 的 {@link AlertRule}，
 * 从 {@link MeterRegistry} 获取对应指标值，与阈值比较。
 * 满足条件时创建 {@link AlertEvent} 并通过所有已注册的 {@link AlertNotifier} 发送通知。</p>
 *
 * <p>冷却机制：同一规则在 {@code cooldownMinutes} 内不重复触发。
 * 冷却状态维护在内存 {@link ConcurrentHashMap} 中，重启后重置。</p>
 */
@Service
@ConditionalOnProperty(name = "nexus.alert.enabled", havingValue = "true", matchIfMissing = true)
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    private final AlertRuleRepository ruleRepository;
    private final AlertEventRepository eventRepository;
    private final MeterRegistry meterRegistry;
    private final List<AlertNotifier> notifiers;

    /** 规则名 → 上次触发时间（用于冷却判断） */
    private final Map<String, LocalDateTime> lastTriggeredMap = new ConcurrentHashMap<>();

    @Value("${nexus.alert.engine.check-interval-seconds:30}")
    private int checkIntervalSeconds;

    public AlertEngine(AlertRuleRepository ruleRepository,
                       AlertEventRepository eventRepository,
                       MeterRegistry meterRegistry,
                       List<AlertNotifier> notifiers) {
        this.ruleRepository = ruleRepository;
        this.eventRepository = eventRepository;
        this.meterRegistry = meterRegistry;
        this.notifiers = notifiers;
    }

    /**
     * 定时扫描告警规则，检测指标是否满足触发条件。
     *
     * <p>固定延迟执行，间隔由 {@code nexus.alert.engine.check-interval-seconds} 配置（默认 30 秒）。
     * 初始延迟 30 秒，确保应用启动后所有指标已注册。</p>
     */
    @Scheduled(fixedDelayString = "${nexus.alert.engine.check-interval-seconds:30}000",
               initialDelayString = "30000")
    @Transactional
    public void checkAlerts() {
        List<AlertRule> rules = ruleRepository.findByEnabledTrue();
        if (rules.isEmpty()) {
            return;
        }

        log.debug("Checking {} enabled alert rules", rules.size());

        for (AlertRule rule : rules) {
            try {
                checkRule(rule);
            } catch (Exception e) {
                log.error("Error checking alert rule '{}': {}", rule.getName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 检查单条规则是否满足告警条件。
     *
     * @param rule 告警规则
     */
    void checkRule(AlertRule rule) {
        Double metricValue = getMetricValue(rule.getMetricName());
        if (metricValue == null) {
            log.debug("Metric '{}' not found in MeterRegistry, skipping rule '{}'",
                    rule.getMetricName(), rule.getName());
            return;
        }

        if (!rule.matches(metricValue)) {
            log.debug("Rule '{}' not triggered: value={} {} threshold={}",
                    rule.getName(), metricValue, rule.getCondition(), rule.getThreshold());
            return;
        }

        // 冷却判断：同一规则在 cooldownMinutes 内不重复触发
        if (isInCooldown(rule)) {
            log.debug("Rule '{}' in cooldown period, skipping", rule.getName());
            return;
        }

        // 触发告警
        triggerAlert(rule, metricValue);
    }

    /**
     * 从 MeterRegistry 获取指标当前值。
     *
     * <p>支持 Counter（累计值）和 Gauge（瞬时值）。
     * Timer 类型取其 count 值（调用次数）。</p>
     *
     * @param metricName 指标名
     * @return 指标值，找不到时返回 null
     */
    Double getMetricValue(String metricName) {
        // 查找所有匹配的 meter
        List<Meter> meters = meterRegistry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(metricName))
                .toList();

        if (meters.isEmpty()) {
            return null;
        }

        Meter meter = meters.get(0);
        if (meter instanceof Counter counter) {
            return counter.count();
        } else if (meter instanceof Timer timer) {
            return (double) timer.count();
        } else {
            // Gauge 或其他类型：尝试读取 value
            for (Measurement ms : meter.measure()) {
                double val = ms.getValue();
                if (!Double.isNaN(val)) {
                    return val;
                }
            }
            return null;
        }
    }

    /**
     * 判断规则是否在冷却期内。
     *
     * @param rule 告警规则
     * @return true 表示在冷却期内，不应重复触发
     */
    boolean isInCooldown(AlertRule rule) {
        LocalDateTime lastTriggered = lastTriggeredMap.get(rule.getName());
        if (lastTriggered == null) {
            return false;
        }
        LocalDateTime cooldownEnd = lastTriggered.plusMinutes(rule.getCooldownMinutes());
        return LocalDateTime.now().isBefore(cooldownEnd);
    }

    /**
     * 触发告警：创建 AlertEvent，发送通知，记录冷却时间。
     *
     * @param rule 满足条件的规则
     * @param currentValue 当前指标值
     */
    void triggerAlert(AlertRule rule, double currentValue) {
        AlertEvent event = new AlertEvent();
        event.setRuleName(rule.getName());
        event.setMetricName(rule.getMetricName());
        event.setCurrentValue(currentValue);
        event.setThreshold(rule.getThreshold());
        event.setSeverity(rule.getSeverity());
        event.setMessage(String.format("Alert '%s': metric %s = %.2f %s threshold %.2f",
                rule.getName(),
                rule.getMetricName(),
                currentValue,
                rule.getCondition(),
                rule.getThreshold()));
        event.setTimestamp(LocalDateTime.now());

        // 持久化事件
        eventRepository.save(event);

        // 发送通知到所有已注册的 Notifier
        for (AlertNotifier notifier : notifiers) {
            try {
                notifier.notify(event);
            } catch (Exception e) {
                log.error("Notifier '{}' failed for alert '{}': {}",
                        notifier.channel(), rule.getName(), e.getMessage(), e);
            }
        }

        // 记录冷却时间
        lastTriggeredMap.put(rule.getName(), LocalDateTime.now());

        log.info("Alert triggered: rule={} metric={} value={} threshold={} severity={}",
                rule.getName(),
                rule.getMetricName(),
                currentValue,
                rule.getThreshold(),
                rule.getSeverity());
    }

    /**
     * 手动重置某条规则的冷却状态（用于测试或运维干预）。
     *
     * @param ruleName 规则名称
     */
    public void resetCooldown(String ruleName) {
        lastTriggeredMap.remove(ruleName);
    }

    /**
     * 手动重置所有规则的冷却状态。
     */
    public void resetAllCooldowns() {
        lastTriggeredMap.clear();
    }
}