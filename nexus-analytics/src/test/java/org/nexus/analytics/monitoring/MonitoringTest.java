package org.nexus.analytics.monitoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultAlertService} 与 {@link DefaultChainMonitorService} 单元测试。
 */
class MonitoringTest {

    private DefaultAlertService alertService;
    private InMemoryChainMetricsProvider metricsProvider;
    private DefaultChainMonitorService monitorService;

    @BeforeEach
    void setUp() {
        alertService = new DefaultAlertService();
        metricsProvider = new InMemoryChainMetricsProvider();
        monitorService = new DefaultChainMonitorService(metricsProvider, alertService);
    }

    @Test
    void raiseAlert_shouldAssignIdAndOpenState() {
        Alert alert = Alert.builder()
                .level(Alert.Level.WARN).source("TEST").content("test alert").build();

        Alert raised = alertService.raiseAlert(alert);

        assertTrue(raised.getAlertId().startsWith("ALERT-"));
        assertEquals(Alert.State.OPEN, raised.getState());
    }

    @Test
    void acknowledgeAlert_open_shouldSucceed() {
        Alert raised = alertService.raiseAlert(
                Alert.builder().level(Alert.Level.WARN).source("TEST").content("x").build());

        assertTrue(alertService.acknowledgeAlert(raised.getAlertId()));
        // 二次确认应失败（已 ACKNOWLEDGED）
        assertFalse(alertService.acknowledgeAlert(raised.getAlertId()));
    }

    @Test
    void getActiveAlerts_shouldExcludeResolved() {
        alertService.raiseAlert(Alert.builder().level(Alert.Level.INFO).source("A").content("open").state(Alert.State.OPEN).build());
        alertService.raiseAlert(Alert.builder().level(Alert.Level.INFO).source("B").content("resolved").state(Alert.State.RESOLVED).build());

        List<Alert> active = alertService.getActiveAlerts();

        assertEquals(1, active.size());
        assertEquals("A", active.get(0).getSource());
    }

    @Test
    void getActiveAlertsByLevel_shouldFilter() {
        alertService.raiseAlert(Alert.builder().level(Alert.Level.CRITICAL).source("A").content("crit").build());
        alertService.raiseAlert(Alert.builder().level(Alert.Level.INFO).source("B").content("info").build());

        assertEquals(1, alertService.getActiveAlertsByLevel(Alert.Level.CRITICAL).size());
    }

    @Test
    void monitor_healthyMetrics_shouldRaiseNoAlert() {
        // 默认指标为健康值，不应产生告警
        monitorService.monitorNodeHealth();
        monitorService.monitorBlockPropagation();
        monitorService.monitorMempool();

        assertEquals(0, alertService.getActiveAlerts().size());
    }

    @Test
    void monitor_highSyncLag_shouldRaiseAlert() {
        metricsProvider.setNodeHealth(Map.of("online", true, "syncLag", 500, "peerCount", 12));

        monitorService.monitorNodeHealth();

        List<Alert> alerts = alertService.getActiveAlerts();
        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).getContent().contains("syncLag"));
    }

    @Test
    void monitor_lowPeerCount_shouldRaiseCriticalAlert() {
        metricsProvider.setNodeHealth(Map.of("online", true, "syncLag", 0, "peerCount", 1));

        monitorService.monitorNodeHealth();

        List<Alert> alerts = alertService.getActiveAlertsByLevel(Alert.Level.CRITICAL);
        assertEquals(1, alerts.size());
    }

    @Test
    void thresholdRule_aboveDirection() {
        ThresholdAlertRule rule = new ThresholdAlertRule(
                "test.above", "m", 10, ThresholdAlertRule.Direction.ABOVE, Alert.Level.WARN, "SRC");

        assertTrue(rule.evaluate(Map.of("m", 15)).isPresent());
        assertTrue(rule.evaluate(Map.of("m", 5)).isEmpty());
    }

    @Test
    void thresholdRule_belowDirection() {
        ThresholdAlertRule rule = new ThresholdAlertRule(
                "test.below", "m", 10, ThresholdAlertRule.Direction.BELOW, Alert.Level.WARN, "SRC");

        assertTrue(rule.evaluate(Map.of("m", 5)).isPresent());
        assertTrue(rule.evaluate(Map.of("m", 15)).isEmpty());
    }

    @Test
    void thresholdRule_nonNumeric_shouldBeEmpty() {
        ThresholdAlertRule rule = new ThresholdAlertRule(
                "test", "m", 10, ThresholdAlertRule.Direction.ABOVE, Alert.Level.WARN, "SRC");

        assertTrue(rule.evaluate(Map.of("m", "not-a-number")).isEmpty());
    }
}
