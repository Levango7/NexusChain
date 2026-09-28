package org.nexus.gateway.orchestration.routing.monitor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.config.RoutingWave16Properties;
import org.nexus.gateway.orchestration.routing.ai.MetricsCollector;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RoutingMonitorService} 单元测试：EMA 基线、成功率下降/延迟尖峰检测、计数。
 */
class RoutingMonitorServiceTest {

    private RoutingWave16Properties properties;
    private MetricsCollector collector;
    private RoutingMonitorService service;

    @BeforeEach
    void setUp() {
        properties = new RoutingWave16Properties();
        collector = new MetricsCollector(10, Duration.ofSeconds(3600), java.time.Clock.systemUTC());
        service = new RoutingMonitorService(properties, collector, null);
    }

    @Test
    @DisplayName("checkAnomalies: 成功率骤降 + 延迟尖峰被检测；冷启动期不告警")
    void anomalyDetection() {
        // 建立基线：100% 成功率、100ms 延迟
        for (int i = 0; i < 20; i++) {
            collector.record("mock", true, 100, 10);
        }
        service.checkAnomalies();
        assertTrue(service.recentAnomalies().isEmpty(), "基线建立期不应有异常");

        // 窗口内混入大量失败 + 高延迟：成功率 0.5（降 0.5 > 0.2），平均延迟 550ms（尖峰 4.5x）
        for (int i = 0; i < 20; i++) {
            collector.record("mock", false, 1000, 10);
        }
        service.checkAnomalies();

        List<RoutingMonitorService.Anomaly> anomalies = service.recentAnomalies();
        assertTrue(anomalies.stream().anyMatch(a -> a.type() == RoutingMonitorService.AnomalyType.SUCCESS_RATE_DROP));
        assertTrue(anomalies.stream().anyMatch(a -> a.type() == RoutingMonitorService.AnomalyType.LATENCY_SPIKE));
    }

    @Test
    @DisplayName("checkAnomalies: monitor.enabled=false 时不检测")
    void disabledNoCheck() {
        properties.getMonitor().setEnabled(false);
        for (int i = 0; i < 20; i++) {
            collector.record("mock", true, 100, 10);
        }
        service.checkAnomalies();
        assertTrue(service.recentAnomalies().isEmpty());
    }

    @Test
    @DisplayName("EMA 基线逐次收敛：数据不变时基线追平后不再产生新异常")
    void baselineConverges() {
        for (int i = 0; i < 20; i++) {
            collector.record("mock", true, 100, 10);
        }
        service.checkAnomalies();

        for (int i = 0; i < 20; i++) {
            collector.record("mock", false, 1000, 10);
        }
        service.checkAnomalies();
        assertTrue(service.recentAnomalies().size() >= 2);

        // EMA α=0.2：基线成功率 1.0 → 0.9 → 0.82 → … 约第 5 次检测后与当前 0.5 的差距 ≤ 0.2
        for (int round = 0; round < 6; round++) {
            service.checkAnomalies();
        }
        int afterConvergence = service.recentAnomalies().size();
        service.checkAnomalies();
        assertEquals(afterConvergence, service.recentAnomalies().size(),
                "基线追平后不应再产生新异常");
    }

    @Test
    @DisplayName("incrementDecisions: 无 MeterRegistry 时静默 no-op")
    void counterNoRegistryNoOp() {
        assertDoesNotThrow(() -> service.incrementDecisions("PRIORITY"));
    }

    @Test
    @DisplayName("resetBaselines: 基线清空后重新从当前观测建立")
    void resetBaselines() {
        for (int i = 0; i < 20; i++) {
            collector.record("mock", true, 100, 10);
        }
        service.checkAnomalies();
        service.resetBaselines();
        // 重置后无基线，不再触发下降告警
        for (int i = 0; i < 20; i++) {
            collector.record("mock", false, 1000, 10);
        }
        service.checkAnomalies();
        assertTrue(service.recentAnomalies().isEmpty(), "重置后首检仅建立基线，不告警");
    }
}
