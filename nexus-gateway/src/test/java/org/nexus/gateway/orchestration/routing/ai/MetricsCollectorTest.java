package org.nexus.gateway.orchestration.routing.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MetricsCollector} 单元测试（时间桶实现）。
 *
 * <p>使用 {@link MutableClock} 注入可前进的时钟，模拟时间流逝触发桶滚动。</p>
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>时间桶聚合：桶内事件累加到同一桶</li>
 *   <li>桶滚动：跨过 bucketSize 后进入新桶</li>
 *   <li>窗口淘汰：跨过 windowSize 个桶后最旧桶被淘汰</li>
 *   <li>成功率计算：成功/失败/混合</li>
 *   <li>平均延迟/成本计算</li>
 *   <li>recentFailures：尾部连续失败桶数</li>
 *   <li>多 connector 独立窗口</li>
 *   <li>空窗口/未观测 connector</li>
 *   <li>构造器参数校验</li>
 * </ul>
 */
class MetricsCollectorTest {

    private static final Duration BUCKET = Duration.ofSeconds(60);
    private MutableClock clock;
    private MetricsCollector collector;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        collector = new MetricsCollector(5, BUCKET, clock);
    }

    // === 桶内聚合 ===

    @Test
    @DisplayName("record: 同一桶内累加，单桶保留所有样本")
    void sameBucket_aggregates() {
        clock.advance(Duration.ofSeconds(10));
        collector.record("c1", true, 100, 10);
        collector.record("c1", true, 200, 20);
        collector.record("c1", false, 300, 30);
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(3, m.samples());
        assertEquals(200, m.avgLatencyMs());
        assertEquals(20, m.avgCostBps());
        assertEquals(2.0 / 3.0, m.successRate(), 0.001);
    }

    // === 桶滚动 ===

    @Test
    @DisplayName("record: 跨 bucketSize 后进入新桶")
    void crossBucket_rolls() {
        collector.record("c1", true, 100, 10);
        clock.advance(Duration.ofSeconds(61));
        collector.record("c1", true, 200, 20);
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(2, m.samples());
        assertEquals(150, m.avgLatencyMs());
    }

    // === 窗口淘汰 ===

    @Test
    @DisplayName("record: 跨过 windowSize 个桶后最旧桶被淘汰")
    void windowEviction_dropsOldestBucket() {
        // 桶 0 (t=0)
        collector.record("c1", true, 100, 10);
        collector.record("c1", true, 110, 11);
        // 推进到桶 5 (t=300s)，需要依次跨 5 个桶
        for (int i = 1; i <= 5; i++) {
            clock.advance(BUCKET);
            collector.record("c1", true, 200 + i * 10, 20 + i);
        }
        ConnectorMetrics m = collector.metrics("c1");
        // windowSize=5，桶 0 应被淘汰；保留桶 1..5（每桶 1 个样本，共 5）
        assertEquals(5, m.samples());
        // 桶 1..5 的延迟：210, 220, 230, 240, 250 → 平均 230
        assertEquals(230, m.avgLatencyMs());
        assertEquals(1.0, m.successRate(), 0.001);
    }

    @Test
    @DisplayName("record: 大跨度（> windowSize 桶）直接清空，避免栈式弹出")
    void windowEviction_largeGap_clearsAll() {
        collector.record("c1", true, 100, 10);
        clock.advance(BUCKET.multipliedBy(20));
        collector.record("c1", true, 999, 99);
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(1, m.samples());
        assertEquals(999, m.avgLatencyMs());
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
    @DisplayName("metrics: avgLatencyMs / avgCostBps 为窗口内平均")
    void avgLatencyAndCost() {
        collector.record("c1", true, 100, 10);
        collector.record("c1", true, 200, 20);
        collector.record("c1", true, 300, 30);
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(200, m.avgLatencyMs());
        assertEquals(20, m.avgCostBps());
    }

    // === recentFailures（按桶判定） ===

    @Test
    @DisplayName("metrics: recentFailures = 尾部连续失败桶数")
    void recentFailures_trailingConsecutiveBuckets() {
        // 桶 0 成功
        collector.record("c1", true, 100, 10);
        // 桶 1,2,3 全部失败
        for (int i = 1; i <= 3; i++) {
            clock.advance(BUCKET);
            collector.record("c1", false, 100, 10);
        }
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(3, m.recentFailures());
    }

    @Test
    @DisplayName("metrics: 尾部成功桶切断 recentFailures 计数")
    void recentFailures_trailingSuccessResets() {
        for (int i = 0; i < 2; i++) {
            clock.advance(BUCKET);
            collector.record("c1", false, 100, 10);
        }
        clock.advance(BUCKET);
        collector.record("c1", true, 100, 10);
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(0, m.recentFailures());
    }

    @Test
    @DisplayName("metrics: 单桶全部失败时 recentFailures=1")
    void recentFailures_singleBucketAllFailure() {
        collector.record("c1", false, 100, 10);
        collector.record("c1", false, 200, 20);
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(1, m.recentFailures());
    }

    @Test
    @DisplayName("metrics: 桶内混合（有成功）则该桶不计入 recentFailures")
    void recentFailures_bucketWithSuccessBreaks() {
        // 桶 0 全部失败
        collector.record("c1", false, 100, 10);
        // 桶 1 混合（1 成功 1 失败）
        clock.advance(BUCKET);
        collector.record("c1", false, 100, 10);
        collector.record("c1", true, 100, 10);
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(0, m.recentFailures());
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
        assertThrows(IllegalArgumentException.class, () -> new MetricsCollector(0, BUCKET, clock));
        assertThrows(IllegalArgumentException.class, () -> new MetricsCollector(-1, BUCKET, clock));
    }

    @Test
    @DisplayName("构造: bucketSize 非法抛异常")
    void constructor_invalidBucketSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new MetricsCollector(5, Duration.ZERO, clock));
        assertThrows(IllegalArgumentException.class,
                () -> new MetricsCollector(5, Duration.ofSeconds(-1), clock));
        assertThrows(IllegalArgumentException.class,
                () -> new MetricsCollector(5, null, clock));
    }

    @Test
    @DisplayName("构造: clock 为 null 抛异常")
    void constructor_nullClock() {
        assertThrows(IllegalArgumentException.class,
                () -> new MetricsCollector(5, BUCKET, null));
    }

    @Test
    @DisplayName("构造: 向后兼容的单参构造仍可用")
    void constructor_singleArg_backwardCompatible() {
        MetricsCollector c = new MetricsCollector(10);
        c.record("c1", true, 100, 10);
        ConnectorMetrics m = c.metrics("c1");
        assertEquals(1, m.samples());
    }

    @Test
    @DisplayName("snapshot: 长时间未调用后过期桶被自动淘汰")
    void snapshot_evictsExpiredBuckets() {
        // 写入桶 0
        collector.record("c1", true, 100, 10);
        // 推进远超窗口跨度
        clock.advance(BUCKET.multipliedBy(100));
        // 不写新数据，直接 snapshot
        ConnectorMetrics m = collector.metrics("c1");
        assertEquals(0, m.samples());
    }

    /** 可前进的 {@link Clock}，用于测试模拟时间流逝。 */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        void advance(Duration d) {
            now.updateAndGet(t -> t.plus(d));
        }

        @Override
        public ZoneId getZone() { return ZoneId.of("UTC"); }

        @Override
        public Clock withZone(ZoneId zone) { return this; }

        @Override
        public Instant instant() { return now.get(); }

        @Override
        public long millis() { return now.get().toEpochMilli(); }
    }
}
