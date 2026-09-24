package org.nexus.gateway.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link AlertAggregationService} 单元测试：覆盖告警聚合、窗口计算、通知逻辑。
 */
class AlertAggregationServiceTest {

    private AlertAggregationRepository aggregationRepository;
    private AlertEventRepository eventRepository;
    private AlertNotifier notifier;
    private AlertAggregationService service;

    @BeforeEach
    void setUp() {
        aggregationRepository = mock(AlertAggregationRepository.class);
        eventRepository = mock(AlertEventRepository.class);
        notifier = mock(AlertNotifier.class);
        when(notifier.channel()).thenReturn("test");

        service = new AlertAggregationService(aggregationRepository, eventRepository, List.of(notifier));
        service.setWindowMinutes(5);
    }

    // === 聚合键计算 ===

    @Test
    @DisplayName("buildAggregationKey: 格式为 ruleName:severity:windowStart")
    void buildAggregationKey_correctFormat() {
        AlertEvent event = createEvent(1L, "failed-payments", AlertRule.Severity.CRITICAL,
                LocalDateTime.of(2026, 9, 25, 10, 3, 30));

        String key = service.buildAggregationKey(event);

        // windowMinutes=5, 10:03 -> windowStart=10:00
        assertEquals("failed-payments:CRITICAL:2026-09-25T10:00", key);
    }

    @Test
    @DisplayName("buildAggregationKey: 不同规则名 -> 不同聚合键")
    void buildAggregationKey_differentRuleName() {
        AlertEvent event1 = createEvent(1L, "rule-a", AlertRule.Severity.WARN,
                LocalDateTime.of(2026, 9, 25, 10, 0, 0));
        AlertEvent event2 = createEvent(2L, "rule-b", AlertRule.Severity.WARN,
                LocalDateTime.of(2026, 9, 25, 10, 0, 0));

        assertNotEquals(service.buildAggregationKey(event1), service.buildAggregationKey(event2));
    }

    @Test
    @DisplayName("buildAggregationKey: 不同严重级别 -> 不同聚合键")
    void buildAggregationKey_differentSeverity() {
        AlertEvent event1 = createEvent(1L, "failed-payments", AlertRule.Severity.WARN,
                LocalDateTime.of(2026, 9, 25, 10, 0, 0));
        AlertEvent event2 = createEvent(2L, "failed-payments", AlertRule.Severity.CRITICAL,
                LocalDateTime.of(2026, 9, 25, 10, 0, 0));

        assertNotEquals(service.buildAggregationKey(event1), service.buildAggregationKey(event2));
    }

    // === 窗口计算 ===

    @Test
    @DisplayName("calculateWindowStart: 10:03 with 5min window -> 10:00")
    void calculateWindowStart_alignedTo5Minutes() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 9, 25, 10, 3, 30);
        LocalDateTime windowStart = service.calculateWindowStart(timestamp);

        assertEquals(LocalDateTime.of(2026, 9, 25, 10, 0, 0), windowStart);
    }

    @Test
    @DisplayName("calculateWindowStart: 10:07 with 5min window -> 10:05")
    void calculateWindowStart_7thMinute() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 9, 25, 10, 7, 45);
        LocalDateTime windowStart = service.calculateWindowStart(timestamp);

        assertEquals(LocalDateTime.of(2026, 9, 25, 10, 5, 0), windowStart);
    }

    @Test
    @DisplayName("calculateWindowStart: 10:00 exactly -> 10:00")
    void calculateWindowStart_exactBoundary() {
        LocalDateTime timestamp = LocalDateTime.of(2026, 9, 25, 10, 0, 0);
        LocalDateTime windowStart = service.calculateWindowStart(timestamp);

        assertEquals(LocalDateTime.of(2026, 9, 25, 10, 0, 0), windowStart);
    }

    @Test
    @DisplayName("calculateWindowStart: 同一窗口内的事件 -> 相同窗口开始时间")
    void calculateWindowStart_sameWindow() {
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 25, 10, 0, 1);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 25, 10, 4, 59);

        assertEquals(service.calculateWindowStart(t1), service.calculateWindowStart(t2));
    }

    @Test
    @DisplayName("calculateWindowStart: 不同窗口的事件 -> 不同窗口开始时间")
    void calculateWindowStart_differentWindow() {
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 25, 10, 4, 59);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 25, 10, 5, 0);

        assertNotEquals(service.calculateWindowStart(t1), service.calculateWindowStart(t2));
    }

    // === 聚合逻辑 ===

    @Test
    @DisplayName("aggregate: 新聚合键 -> 创建新聚合记录")
    void aggregate_newKey_createsNewAggregation() {
        AlertEvent event = createEvent(1L, "failed-payments", AlertRule.Severity.CRITICAL,
                LocalDateTime.of(2026, 9, 25, 10, 0, 0));

        when(aggregationRepository.findByAggregationKey(any())).thenReturn(Optional.empty());
        when(aggregationRepository.save(any(AlertAggregation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.aggregate(event);

        verify(aggregationRepository).save(any(AlertAggregation.class));
    }

    @Test
    @DisplayName("aggregate: 已存在的聚合键 -> 更新聚合记录（count递增）")
    void aggregate_existingKey_updatesAggregation() {
        AlertEvent event1 = createEvent(1L, "failed-payments", AlertRule.Severity.CRITICAL,
                LocalDateTime.of(2026, 9, 25, 10, 0, 0));
        AlertEvent event2 = createEvent(2L, "failed-payments", AlertRule.Severity.CRITICAL,
                LocalDateTime.of(2026, 9, 25, 10, 2, 0));

        AlertAggregation existing = new AlertAggregation();
        existing.setAggregationKey("failed-payments:CRITICAL:2026-09-25T10:00");
        existing.setRuleName("failed-payments");
        existing.setSeverity(AlertRule.Severity.CRITICAL);
        existing.setCount(1);
        existing.setFirstEventId(1L);

        when(aggregationRepository.findByAggregationKey(any())).thenReturn(Optional.of(existing));
        when(aggregationRepository.save(any(AlertAggregation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.aggregate(event2);

        verify(aggregationRepository).save(any(AlertAggregation.class));
        assertEquals(2, existing.getCount());
        assertEquals(2L, existing.getLastEventId());
    }

    @Test
    @DisplayName("aggregate: 不同窗口的相同规则 -> 不同聚合记录")
    void aggregate_differentWindow_separateAggregation() {
        AlertEvent event1 = createEvent(1L, "failed-payments", AlertRule.Severity.CRITICAL,
                LocalDateTime.of(2026, 9, 25, 10, 0, 0));
        AlertEvent event2 = createEvent(2L, "failed-payments", AlertRule.Severity.CRITICAL,
                LocalDateTime.of(2026, 9, 25, 10, 10, 0));

        when(aggregationRepository.findByAggregationKey(any())).thenReturn(Optional.empty());
        when(aggregationRepository.save(any(AlertAggregation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.aggregate(event1);
        service.aggregate(event2);

        // 两个不同的聚合键，应该保存两次
        verify(aggregationRepository, times(2)).save(any(AlertAggregation.class));
    }

    // === 通知逻辑 ===

    @Test
    @DisplayName("notifyCompletedAggregations: 窗口已结束且未通知 -> 发送通知并标记")
    void notifyCompletedAggregations_sendsNotification() {
        AlertAggregation aggregation = new AlertAggregation();
        aggregation.setId(1L);
        aggregation.setAggregationKey("failed-payments:CRITICAL:2026-09-25T10:00");
        aggregation.setRuleName("failed-payments");
        aggregation.setSeverity(AlertRule.Severity.CRITICAL);
        aggregation.setCount(3);
        aggregation.setWindowStart(LocalDateTime.of(2026, 9, 25, 10, 0, 0));
        aggregation.setWindowEnd(LocalDateTime.of(2026, 9, 25, 10, 5, 0));
        aggregation.setNotified(false);

        when(aggregationRepository.findByNotifiedFalseAndWindowEndBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(aggregation));
        when(aggregationRepository.save(any(AlertAggregation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.notifyCompletedAggregations();

        verify(notifier).notify(any(AlertEvent.class));
        verify(aggregationRepository).save(any(AlertAggregation.class));
        assertTrue(aggregation.isNotified());
    }

    @Test
    @DisplayName("notifyCompletedAggregations: 无待通知聚合 -> 不发送通知")
    void notifyCompletedAggregations_noPending() {
        when(aggregationRepository.findByNotifiedFalseAndWindowEndBefore(any(LocalDateTime.class)))
                .thenReturn(List.of());

        service.notifyCompletedAggregations();

        verify(notifier, never()).notify(any());
    }

    @Test
    @DisplayName("notifyCompletedAggregations: 通知器抛异常 -> 不影响其他通知器")
    void notifyCompletedAggregations_notifierException() {
        AlertNotifier failingNotifier = mock(AlertNotifier.class);
        when(failingNotifier.channel()).thenReturn("failing");
        doThrow(new RuntimeException("send failed")).when(failingNotifier).notify(any());

        AlertAggregationService serviceWithTwoNotifiers = new AlertAggregationService(
                aggregationRepository, eventRepository, List.of(failingNotifier, notifier));

        AlertAggregation aggregation = new AlertAggregation();
        aggregation.setId(1L);
        aggregation.setRuleName("failed-payments");
        aggregation.setSeverity(AlertRule.Severity.CRITICAL);
        aggregation.setCount(3);
        aggregation.setWindowStart(LocalDateTime.of(2026, 9, 25, 10, 0, 0));
        aggregation.setWindowEnd(LocalDateTime.of(2026, 9, 25, 10, 5, 0));
        aggregation.setNotified(false);

        when(aggregationRepository.findByNotifiedFalseAndWindowEndBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(aggregation));
        when(aggregationRepository.save(any(AlertAggregation.class))).thenAnswer(inv -> inv.getArgument(0));

        serviceWithTwoNotifiers.notifyCompletedAggregations();

        verify(notifier).notify(any(AlertEvent.class));
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
        return event;
    }
}