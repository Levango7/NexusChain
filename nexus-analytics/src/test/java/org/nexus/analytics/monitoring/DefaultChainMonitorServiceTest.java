package org.nexus.analytics.monitoring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultChainMonitorService} 补充测试。
 *
 * <p>覆盖 startAll/stopAll 生命周期、registerRule 动态扩展、
 * ruleCount/ruleNames 审计方法与异常吞没路径。
 */
class DefaultChainMonitorServiceTest {

    private DefaultAlertService alertService;
    private InMemoryChainMetricsProvider metricsProvider;
    private DefaultChainMonitorService monitorService;

    @BeforeEach
    void setUp() {
        alertService = new DefaultAlertService();
        metricsProvider = new InMemoryChainMetricsProvider();
        monitorService = new DefaultChainMonitorService(metricsProvider, alertService);
    }

    @AfterEach
    void tearDown() {
        // 确保不泄漏线程
        monitorService.stopAll();
    }

    @Test
    void startAll_thenStopAll_shouldLifecycleWork() throws Exception {
        monitorService.startAll();
        // 重复启动应幂等（不抛异常）
        monitorService.startAll();
        // 让定时任务至少执行一次
        Thread.sleep(100);
        monitorService.stopAll();
        // 重复停止应幂等
        monitorService.stopAll();
    }

    @Test
    void registerRule_shouldIncreaseRuleCount() {
        int initial = monitorService.ruleCount();
        AlertRule customRule = new AlertRule() {
            @Override
            public String name() {
                return "custom.rule";
            }

            @Override
            public Optional<Alert> evaluate(Map<String, Object> metric) {
                return Optional.empty();
            }
        };
        monitorService.registerRule(customRule);
        assertEquals(initial + 1, monitorService.ruleCount());
        assertTrue(monitorService.ruleNames().contains("custom.rule"));
    }

    @Test
    void registerRule_null_shouldBeIgnored() {
        int initial = monitorService.ruleCount();
        monitorService.registerRule(null);
        assertEquals(initial, monitorService.ruleCount());
    }

    @Test
    void ruleNames_shouldContainDefaultRules() {
        List<String> names = monitorService.ruleNames();
        assertTrue(names.contains("node.sync.lag"));
        assertTrue(names.contains("node.peer.count.low"));
        assertTrue(names.contains("block.propagation.slow"));
        assertTrue(names.contains("mempool.congestion"));
        assertEquals(4, names.size());
    }

    @Test
    void ruleCount_shouldReturnFourByDefault() {
        assertEquals(4, monitorService.ruleCount());
    }

    @Test
    void monitorBlockPropagation_slow_shouldRaiseAlert() {
        metricsProvider.setBlockPropagation(Map.of("propagationP95Ms", 5000, "lastBlockHeight", 100L));

        monitorService.monitorBlockPropagation();

        List<Alert> alerts = alertService.getActiveAlerts();
        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).getContent().contains("propagationP95Ms"));
    }

    @Test
    void monitorMempool_congested_shouldRaiseAlert() {
        metricsProvider.setMempool(Map.of("pendingCount", 50000, "feeP50", 10.0));

        monitorService.monitorMempool();

        assertFalse(alertService.getActiveAlerts().isEmpty());
    }

    @Test
    void monitorNodeHealth_healthy_shouldRaiseNoAlert() {
        metricsProvider.setNodeHealth(Map.of("online", true, "syncLag", 0, "peerCount", 50));

        monitorService.monitorNodeHealth();

        assertEquals(0, alertService.getActiveAlerts().size());
    }

    @Test
    void registerRule_customRuleThatFires_shouldRaiseAlert() {
        AlertRule alwaysFire = new AlertRule() {
            @Override
            public String name() {
                return "always.fire";
            }

            @Override
            public Optional<Alert> evaluate(Map<String, Object> metric) {
                return Optional.of(Alert.builder()
                        .level(Alert.Level.CRITICAL)
                        .content("always fires")
                        .state(Alert.State.OPEN)
                        .build());
            }
        };
        monitorService.registerRule(alwaysFire);

        monitorService.monitorNodeHealth();

        assertTrue(alertService.getActiveAlerts().stream()
                .anyMatch(a -> "always fires".equals(a.getContent())));
    }

    @Test
    void monitor_withRuleThatSetsSource_shouldKeepSource() {
        AlertRule withSource = new AlertRule() {
            @Override
            public String name() {
                return "with.source";
            }

            @Override
            public Optional<Alert> evaluate(Map<String, Object> metric) {
                return Optional.of(Alert.builder()
                        .level(Alert.Level.WARN)
                        .source("CUSTOM_SOURCE")
                        .content("has source")
                        .state(Alert.State.OPEN)
                        .build());
            }
        };
        monitorService.registerRule(withSource);

        monitorService.monitorMempool();

        assertTrue(alertService.getActiveAlerts().stream()
                .anyMatch(a -> "CUSTOM_SOURCE".equals(a.getSource())));
    }
}