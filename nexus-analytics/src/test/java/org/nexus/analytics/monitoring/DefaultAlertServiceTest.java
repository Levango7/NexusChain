package org.nexus.analytics.monitoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultAlertService} 补充测试。
 *
 * <p>覆盖 null 参数、acknowledge 不存在告警、getActiveAlertsByLevel null 等边界。
 */
class DefaultAlertServiceTest {

    private DefaultAlertService alertService;

    @BeforeEach
    void setUp() {
        alertService = new DefaultAlertService();
    }

    @Test
    void raiseAlert_null_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> alertService.raiseAlert(null));
    }

    @Test
    void raiseAlert_withExistingId_shouldKeepId() {
        Alert alert = Alert.builder()
                .alertId("CUSTOM-ID-1")
                .level(Alert.Level.WARN).source("S").content("c").build();

        Alert raised = alertService.raiseAlert(alert);

        assertEquals("CUSTOM-ID-1", raised.getAlertId());
    }

    @Test
    void raiseAlert_withExistingTimestamp_shouldKeepTimestamp() {
        Instant ts = Instant.parse("2026-01-01T00:00:00Z");
        Alert alert = Alert.builder()
                .level(Alert.Level.WARN).source("S").content("c")
                .timestamp(ts).build();

        Alert raised = alertService.raiseAlert(alert);

        assertEquals(ts, raised.getTimestamp());
    }

    @Test
    void raiseAlert_withExistingState_shouldKeepState() {
        Alert alert = Alert.builder()
                .level(Alert.Level.WARN).source("S").content("c")
                .state(Alert.State.RESOLVED).build();

        Alert raised = alertService.raiseAlert(alert);

        assertEquals(Alert.State.RESOLVED, raised.getState());
    }

    @Test
    void acknowledgeAlert_nullOrBlank_shouldReturnFalse() {
        assertFalse(alertService.acknowledgeAlert(null));
        assertFalse(alertService.acknowledgeAlert(""));
        assertFalse(alertService.acknowledgeAlert("   "));
    }

    @Test
    void acknowledgeAlert_nonExistent_shouldReturnFalse() {
        assertFalse(alertService.acknowledgeAlert("NO-SUCH-ID"));
    }

    @Test
    void acknowledgeAlert_alreadyAcknowledged_shouldReturnFalse() {
        Alert raised = alertService.raiseAlert(
                Alert.builder().level(Alert.Level.WARN).source("S").content("c").build());
        assertTrue(alertService.acknowledgeAlert(raised.getAlertId()));
        assertFalse(alertService.acknowledgeAlert(raised.getAlertId()));
    }

    @Test
    void acknowledgeAlert_resolved_shouldReturnFalse() {
        Alert raised = alertService.raiseAlert(
                Alert.builder().level(Alert.Level.WARN).source("S").content("c")
                        .state(Alert.State.RESOLVED).build());
        assertFalse(alertService.acknowledgeAlert(raised.getAlertId()));
    }

    @Test
    void getActiveAlerts_shouldIncludeAcknowledged() {
        Alert a1 = alertService.raiseAlert(
                Alert.builder().level(Alert.Level.WARN).source("A").content("open").build());
        alertService.raiseAlert(
                Alert.builder().level(Alert.Level.WARN).source("B").content("open2").build());
        alertService.acknowledgeAlert(a1.getAlertId());

        List<Alert> active = alertService.getActiveAlerts();
        assertEquals(2, active.size());
    }

    @Test
    void getActiveAlertsByLevel_null_shouldReturnEmpty() {
        alertService.raiseAlert(
                Alert.builder().level(Alert.Level.WARN).source("A").content("c").build());
        assertEquals(0, alertService.getActiveAlertsByLevel(null).size());
    }

    @Test
    void getAllAlerts_shouldIncludeResolved() {
        alertService.raiseAlert(
                Alert.builder().level(Alert.Level.WARN).source("A").content("c")
                        .state(Alert.State.RESOLVED).build());
        alertService.raiseAlert(
                Alert.builder().level(Alert.Level.WARN).source("B").content("c2").build());

        assertEquals(2, alertService.getAllAlerts().size());
    }
}