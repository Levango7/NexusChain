package org.nexus.gateway.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警升级服务。
 *
 * <p>定时扫描未解决的告警事件，检查是否满足升级条件。
 * 当告警在指定时间（escalateAfterMinutes）内未解决时，自动将严重级别升级，
 * 并通过升级通知渠道发送通知。</p>
 *
 * <p>升级流程：
 * <ul>
 *   <li>定时扫描所有未解决的告警事件</li>
 *   <li>对每个未解决告警，查找匹配的升级规则（按 ruleName 或 fromSeverity）</li>
 *   <li>若告警触发时间距今超过 escalateAfterMinutes，则执行升级</li>
 *   <li>升级：更新告警事件的 severity 为 toSeverity，发送升级通知</li>
 * </ul>
 * </p>
 */
@Service
@ConditionalOnProperty(name = "nexus.alert.enabled", havingValue = "true", matchIfMissing = true)
public class AlertEscalationService {

    private static final Logger log = LoggerFactory.getLogger(AlertEscalationService.class);

    private final AlertEscalationRuleRepository escalationRuleRepository;
    private final AlertEventRepository eventRepository;
    private final List<AlertNotifier> notifiers;

    public AlertEscalationService(AlertEscalationRuleRepository escalationRuleRepository,
                                    AlertEventRepository eventRepository,
                                    List<AlertNotifier> notifiers) {
        this.escalationRuleRepository = escalationRuleRepository;
        this.eventRepository = eventRepository;
        this.notifiers = notifiers;
    }

    /**
     * 定时扫描未解决的告警事件，检查是否满足升级条件。
     *
     * <p>固定延迟执行，间隔 60 秒，初始延迟 60 秒。</p>
     */
    @Scheduled(fixedDelayString = "60000", initialDelayString = "60000")
    @Transactional
    public void checkEscalations() {
        List<AlertEvent> unresolvedEvents = eventRepository.findByResolvedFalseOrderByTimestampDesc();
        if (unresolvedEvents.isEmpty()) {
            return;
        }

        List<AlertEscalationRule> rules = escalationRuleRepository.findByEnabledTrue();
        if (rules.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int escalatedCount = 0;

        for (AlertEvent event : unresolvedEvents) {
            for (AlertEscalationRule rule : rules) {
                if (shouldEscalate(event, rule, now)) {
                    escalate(event, rule);
                    escalatedCount++;
                    break; // 每个事件只升级一次
                }
            }
        }

        if (escalatedCount > 0) {
            log.info("Escalated {} unresolved alert events", escalatedCount);
        }
    }

    /**
     * 判断告警事件是否满足升级条件。
     *
     * @param event 告警事件
     * @param rule 升级规则
     * @param now 当前时间
     * @return true 表示满足升级条件
     */
    boolean shouldEscalate(AlertEvent event, AlertEscalationRule rule, LocalDateTime now) {
        // 规则名匹配（升级规则的 ruleName 对应告警事件的 ruleName）
        if (!rule.getRuleName().equals(event.getRuleName())) {
            return false;
        }

        // 严重级别匹配
        if (event.getSeverity() != rule.getFromSeverity()) {
            return false;
        }

        // 时间条件：告警触发时间距今超过 escalateAfterMinutes
        LocalDateTime escalateAfter = event.getTimestamp().plusMinutes(rule.getEscalateAfterMinutes());
        return now.isAfter(escalateAfter);
    }

    /**
     * 执行告警升级：更新严重级别，发送升级通知。
     *
     * @param event 告警事件
     * @param rule 升级规则
     */
    @Transactional
    public void escalate(AlertEvent event, AlertEscalationRule rule) {
        log.info("Escalating alert: event={} rule={} from={} to={} channel={}",
                event.getId(), rule.getRuleName(),
                rule.getFromSeverity(), rule.getToSeverity(), rule.getEscalationChannel());

        // 更新告警事件严重级别
        event.setSeverity(rule.getToSeverity());
        event.setMessage(event.getMessage() + " [ESCALATED from " + rule.getFromSeverity() + " to " + rule.getToSeverity() + "]");
        eventRepository.save(event);

        // 发送升级通知
        AlertEvent escalationEvent = new AlertEvent();
        escalationEvent.setRuleName(event.getRuleName());
        escalationEvent.setSeverity(rule.getToSeverity());
        escalationEvent.setMetricName(event.getMetricName());
        escalationEvent.setCurrentValue(event.getCurrentValue());
        escalationEvent.setThreshold(event.getThreshold());
        escalationEvent.setMessage(String.format(
                "ESCALATED alert: rule='%s' escalated from %s to %s after %d minutes, channel=%s",
                rule.getRuleName(), rule.getFromSeverity(), rule.getToSeverity(),
                rule.getEscalateAfterMinutes(), rule.getEscalationChannel()));
        escalationEvent.setTimestamp(LocalDateTime.now());

        for (AlertNotifier notifier : notifiers) {
            try {
                notifier.notify(escalationEvent);
            } catch (Exception e) {
                log.error("Notifier '{}' failed for escalated alert '{}': {}",
                        notifier.channel(), rule.getRuleName(), e.getMessage(), e);
            }
        }
    }
}