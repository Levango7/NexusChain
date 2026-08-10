package org.nexus.gateway.orchestration.routing.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MetricsCollector} 单元测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>滑动窗口：超过 windowSize 后旧样本被丢弃</li>
 *   <li>成功率计算：成功/失败/混合</li>
 *   <li>平均延迟/成本计算</li>
 *   <li>recentFailures：尾部连续失败计数</li>
 *   <li>多 connector 独立窗口</li>
 *   <li>空窗口/未观测 connector</li>
 * </ul>
 */
class MetricsCollectorTest {

    private MetricsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new MetricsCollector(5); // 小窗口便于测试
    }

    // === 滑动窗口 ===

    @Test
    @DisplayName("record: 超过 windowSize 后旧样本被丢弃")
    void slidingWindow_evictsOldSamples() {
        // 窗口大小 5，记入 7 条，应只保留最后 5 条
        for (int i = 0; i < 7; i++) {
            collector.record("c1", true, 100 + i, 10);
        }
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(5, m.samples());
        // 最后 5 条延迟：102,103,104,105,106 -> 平均 104
        assertEquals(104, m.avgLatencyMs());
    }

    @Test
    @DisplayName("record: 窗口未满时保留全部样本")
    void slidingWindow_notFull() {
        collector.record("c1", true, 100, 10);
        collector.record("c1", false, 200, 20);
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(2, m.samples());
    }

    // === 成功率 ===

    @Test
    @DisplayName("metrics: 全部成功 -> successRate=1.0")
    void successRate_allSuccess() {
        collector.record("c1", true, 100, 10);
        collector.record("c1", true, 200, 20);
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(1.0, m.successRate(), 0.001);
    }

    @Test
    @DisplayName("metrics: 全部失败 -> successRate=0.0")
    void successRate_allFailure() {
        collector.record("c1", false, 100, 10);
        collector.record("c1", false, 200, 20);
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(0.0, m.successRate(), 0.001);
    }

    @Test
    @DisplayName("metrics: 混合 -> successRate=成功数/总数")
    void successRate_mixed() {
        collector.record("c1", true, 100, 10);
        collector.record("c1", false, 200, 20);
        collector.record("c1", true, 150, 15);
        collector.record("c1", false, 250, 25);
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(0.5, m.successRate(), 0.001);
    }

    // === 平均延迟/成本 ===

    @Test
    @DisplayName("metrics: avgLatencyMs 为窗口内平均")
    void avgLatency() {
        collector.record("c1", true, 100, 10);
        collector.record("c1", true, 200, 20);
        collector.record("c1", true, 300, 30);
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(200, m.avgLatencyMs()); // (100+200+300)/3
        assertEquals(20, m.avgCostBps());    // (10+20+30)/3
    }

    // === recentFailures ===

    @Test
    @DisplayName("metrics: recentFailures 为尾部连续失败数")
    void recentFailures_trailingConsecutive() {
        collector.record("c1", true, 100, 10);
        collector.record("c1", false, 100, 10);
        collector.record("c1", false, 100, 10);
        collector.record("c1", false, 100, 10);
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(3, m.recentFailures());
    }

    @Test
    @DisplayName("metrics: 尾部成功时 recentFailures=0")
    void recentFailures_trailingSuccess() {
        collector.record("c1", false, 100, 10);
        collector.record("c1", false, 100, 10);
        collector.record("c1", true, 100, 10);
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(0, m.recentFailures());
    }

    @Test
    @DisplayName("metrics: 全部失败时 recentFailures=samples")
    void recentFailures_allFailure() {
        collector.record("c1", false, 100, 10);
        collector.record("c1", false, 100, 10);
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(2, m.recentFailures());
    }

    // === 多 connector 独立窗口 ===

    @Test
    @DisplayName("metrics: 多 connector 独立维护窗口")
    void multipleConnectors_independent() {
        collector.record("c1", true, 100, 10);
        collector.record("c1", true, 200, 20);
        collector.record("c2", false, 500, 50);
        collector.record("c2", true, 600, 60);

        ConnectorMetrics m1 = collector.metrics("c1");
        ConnectorMetrics m2 = collector.metrics("c2");

        assertEquals(2, m1.samples());
        assertEquals(2, m2.samples());
        assertEquals(1.0, m1.successRate(), 0.001);
        assertEquals(0.5, m2.successRate(), 0.001);
        assertEquals(2, collector.observedConnectors());
    }

    // === 空窗口/未观测 ===

    @Test
    @DisplayName("metrics: 未观测 connector 返回空指标 (samples=0)")
    void unobserved_returnsEmpty() {
        ConnectorMetrics m = collector.metrics("ghost");
        assertEquals(0, m.samples());
        assertEquals(0.0, m.successRate(), 0.001);
        assertEquals(0L, m.avgLatencyMs());
        assertEquals(0, m.avgCostBps());
        assertEquals(0, m.recentFailures());
    }

    @Test
    @DisplayName("metricsAll: 空时返回空列表")
    void metricsAll_empty() {
        assertTrue(collector.metricsAll().isEmpty());
    }

    @Test
    @DisplayName("metricsAll: 返回所有已观测 connector 快照")
    void metricsAll_returnsAll() {
        collector.record("c1", true, 100, 10);
        collector.record("c2", true, 200, 20);
        List<ConnectorMetrics> all = collector.metricsAll();
        assertEquals(2, all.size());
    }

    // === hasEnoughSamples ===

    @Test
    @DisplayName("hasEnoughSamples: 样本数达标返回 true")
    void hasEnoughSamples() {
        collector.record("c1", true, 100, 10);
        collector.record("c1", true, 200, 20);
        ConnectorMetrics m = collector.metrics("c1");
        assertTrue(m.hasEnoughSamples(2));
        assertFalse(m.hasEnoughSamples(3));
    }

    // === 边界 ===

    @Test
    @DisplayName("record: null/blank connectorId 被忽略")
    void record_nullIdIgnored() {
        collector.record(null, true, 100, 10);
        collector.record("", true, 100, 10);
        collector.record("  ", true, 100, 10);
        assertEquals(0, collector.observedConnectors());
    }

    @Test
    @DisplayName("clear: 清空所有指标")
    void clear() {
        collector.record("c1", true, 100, 10);
        collector.record("c2", true, 200, 20);
        assertEquals(2, collector.observedConnectors());
        collector.clear();
        assertEquals(0, collector.observedConnectors());
    }

    @Test
    @DisplayName("构造: windowSize <= 0 抛异常")
    void constructor_invalidWindowSize() {
        assertThrows(IllegalArgumentException.class, () -> new MetricsCollector(0));
        assertThrows(IllegalArgumentException.class, () -> new MetricsCollector(-1));
    }
}