package org.nexus.gateway.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 告警聚合服务。
 *
 * <p>将相似告警按规则名 + 严重级别 + 时间窗口（默认5分钟）分组聚合，
 * 减少告警噪音。同一规则 + 同一严重级别 + 时间窗口内的多个告警合并为一条聚合告警。</p>
 *
 * <p>聚合逻辑：
 * <ul>
 *   <li>每条新告警事件到来时，计算其聚合键（ruleName:severity:windowStart）</li>
 *   <li>若聚合键已存在，则更新聚合记录（count++, lastEventId, aggregatedAt）</li>
 *   <li>若聚合键不存在，则创建新的聚合记录</li>
 *   <li>聚合窗口结束后，发送聚合通知</li>
 * </ul>
 * </p>
 */
@Service
@ConditionalOnProperty(name = "nexus.alert.enabled", havingValue = "true", matchIfMissing = true)
public class AlertAggregationService {

    private static final Logger log = LoggerFactory.getLogger(AlertAggregationService.class);

    private final AlertAggregationRepository aggregationRepository;
    private final AlertEventRepository eventRepository;
    private final List<AlertNotifier> notifiers;

    @Value("${nexus.alert.aggregation.window-minutes:5}")
    private int windowMinutes;

    public AlertAggregationService(AlertAggregationRepository aggregationRepository,
                                    AlertEventRepository eventRepository,
                                    List<AlertNotifier> notifiers) {
        this.aggregationRepository = aggregationRepository;
        this.eventRepository = eventRepository;
        this.notifiers = notifiers;
    }

    /**
     * 处理新的告警事件，将其聚合到对应的聚合组。
     *
     * @param event 新的告警事件
     */
    @Transactional
    public void aggregate(AlertEvent event) {
        String aggregationKey = buildAggregationKey(event);
        LocalDateTime windowStart = calculateWindowStart(event.getTimestamp());
        LocalDateTime windowEnd = windowStart.plusMinutes(windowMinutes);

        AlertAggregation aggregation = aggregationRepository.findByAggregationKey(aggregationKey)
                .orElseGet(() -> {
                    AlertAggregation newAgg = new AlertAggregation();
                    newAgg.setAggregationKey(aggregationKey);
                    newAgg.setRuleName(event.getRuleName());
                    newAgg.setSeverity(event.getSeverity());
                    newAgg.setWindowStart(windowStart);
                    newAgg.setWindowEnd(windowEnd);
                    newAgg.setCount(0);
                    newAgg.setFirstEventId(event.getId());
                    return newAgg;
                });

        aggregation.setCount(aggregation.getCount() + 1);
        aggregation.setLastEventId(event.getId());
        aggregation.setAggregatedAt(LocalDateTime.now());

        aggregationRepository.save(aggregation);

        log.debug("Aggregated alert event: key={} count={} rule={} severity={}",
                aggregationKey, aggregation.getCount(), event.getRuleName(), event.getSeverity());
    }

    /**
     * 检查并通知已完成的聚合窗口（窗口已结束但尚未通知的聚合记录）。
     */
    @Transactional
    public void notifyCompletedAggregations() {
        LocalDateTime now = LocalDateTime.now();
        List<AlertAggregation> pending = aggregationRepository
                .findByNotifiedFalseAndWindowEndBefore(now);

        for (AlertAggregation aggregation : pending) {
            notifyAggregation(aggregation);
            aggregation.setNotified(true);
            aggregationRepository.save(aggregation);
        }

        if (!pending.isEmpty()) {
            log.info("Notified {} completed alert aggregations", pending.size());
        }
    }

    /**
     * 发送聚合告警通知。
     *
     * @param aggregation 聚合记录
     */
    private void notifyAggregation(AlertAggregation aggregation) {
        // 构造聚合告警事件用于通知
        AlertEvent aggregatedEvent = new AlertEvent();
        aggregatedEvent.setRuleName(aggregation.getRuleName());
        aggregatedEvent.setSeverity(aggregation.getSeverity());
        aggregatedEvent.setMessage(String.format(
                "Aggregated alert: rule='%s' severity=%s count=%d window=%s~%s",
                aggregation.getRuleName(),
                aggregation.getSeverity(),
                aggregation.getCount(),
                aggregation.getWindowStart(),
                aggregation.getWindowEnd()));
        aggregatedEvent.setTimestamp(aggregation.getAggregatedAt());

        for (AlertNotifier notifier : notifiers) {
            try {
                notifier.notify(aggregatedEvent);
            } catch (Exception e) {
                log.error("Notifier '{}' failed for aggregated alert '{}': {}",
                        notifier.channel(), aggregation.getRuleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 构建聚合键：ruleName:severity:windowStart
     *
     * @param event 告警事件
     * @return 聚合键字符串
     */
    String buildAggregationKey(AlertEvent event) {
        LocalDateTime windowStart = calculateWindowStart(event.getTimestamp());
        return event.getRuleName() + ":" + event.getSeverity() + ":" + windowStart;
    }

    /**
     * 计算事件所属聚合窗口的开始时间。
     * 窗口按 windowMinutes 对齐到整分钟边界。
     *
     * @param timestamp 事件时间戳
     * @return 窗口开始时间
     */
    LocalDateTime calculateWindowStart(LocalDateTime timestamp) {
        int minuteOfHour = timestamp.getMinute();
        int windowAlignedMinute = (minuteOfHour / windowMinutes) * windowMinutes;
        return timestamp.truncatedTo(ChronoUnit.HOURS).plusMinutes(windowAlignedMinute);
    }

    /**
     * 获取聚合窗口大小（分钟）。
     *
     * @return 窗口分钟数
     */
    public int getWindowMinutes() {
        return windowMinutes;
    }

    /**
     * 设置聚合窗口大小（分钟），主要用于测试。
     *
     * @param windowMinutes 窗口分钟数
     */
    public void setWindowMinutes(int windowMinutes) {
        this.windowMinutes = windowMinutes;
    }
}