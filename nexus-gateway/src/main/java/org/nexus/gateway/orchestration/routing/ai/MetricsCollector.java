package org.nexus.gateway.orchestration.routing.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标收集器：从支付事件流中收集每个 connector 的历史数据（成功率/延迟/成本），
 * 使用<b>时间桶</b>（bucketed time window）聚合。
 *
 * <p><b>时间桶语义</b>：每个 connector 维护一个容量为 {@code windowSize}（默认 1000）
 * 的预聚合时间桶环形缓冲区。桶大小由 {@code bucketSize}（默认 60 秒）控制，窗口总跨度
 * = {@code windowSize × bucketSize}（默认 ≈ 16.7 小时）。仅保留最近窗口内的桶，
 * 窗口外桶自动淘汰，保证指标反映近期状态，避免陈旧数据污染路由决策。</p>
 *
 * <p><b>桶内预聚合</b>：每个桶内只保存聚合统计（{@code successCount / totalLatency /
 * totalCost / count}），不保存单条样本。{@link #snapshot} 遍历窗口内桶做 O(桶数)
 * 聚合，避免高流量场景的 O(N) 遍历性能问题。</p>
 *
 * <p><b>时钟注入</b>：默认使用 {@link Clock#systemUTC()}；测试可通过 {@link #MetricsCollector(int, Duration, Clock)}
 * 注入可前进的 {@link Clock}，模拟时间流逝触发桶滚动。</p>
 *
 * <p><b>线程安全</b>：每个 connector 拥有独立的 {@link BucketWindow}，其内部操作
 * 通过 {@code synchronized} 串行化。{@link #record} 与 {@link #metrics} 可并发调用，
 * {@link #metricsAll} 返回快照列表。</p>
 *
 * <p><b>事件字段</b>：单条支付事件包含 connectorId / success（布尔）/ latencyMs /
 * costBps。{@link #record} 由 OrchestrationService 在连接器调用完成后回调。</p>
 */

public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    private static final int DEFAULT_WINDOW_SIZE = 1000;
    private static final Duration DEFAULT_BUCKET_SIZE = Duration.ofSeconds(60);

    private final Map<String, BucketWindow> windows = new ConcurrentHashMap<>();
    private final int windowSize;
    private final Duration bucketSize;
    private final Clock clock;

    public MetricsCollector() {
        this(DEFAULT_WINDOW_SIZE, DEFAULT_BUCKET_SIZE, Clock.systemUTC());
    }

    /**
     * @param windowSize 保留桶数（向后兼容：原"窗口样本数"语义现为"保留桶数"）。
     *                   窗口总时长 = windowSize × bucketSize（默认 ≈ windowSize 分钟）
     */
    public MetricsCollector(int windowSize) {
        this(windowSize, DEFAULT_BUCKET_SIZE, Clock.systemUTC());
    }

    /**
     * 完整构造器（时钟注入，便于测试模拟时间前进）。
     *
     * @param windowSize 保留桶数，必须 &gt; 0
     * @param bucketSize 单桶时长，必须 &gt; 0
     * @param clock      时钟源（生产用 {@link Clock#systemUTC()}，测试用 {@code Clock.fixed} 或可前进 mock）
     */
    public MetricsCollector(int windowSize, Duration bucketSize, Clock clock) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be positive, got " + windowSize);
        }
        if (bucketSize == null || bucketSize.isZero() || bucketSize.isNegative()) {
            throw new IllegalArgumentException("bucketSize must be positive, got " + bucketSize);
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.windowSize = windowSize;
        this.bucketSize = bucketSize;
        this.clock = clock;
    }

    /**
     * 记入一条支付结果事件。
     *
     * @param connectorId connector 标识
     * @param success    本次支付是否成功
     * @param latencyMs  本次支付延迟（毫秒），失败可为 0
     * @param costBps    本次支付成本（basis points）
     */
    public void record(String connectorId, boolean success, long latencyMs, int costBps) {
        if (connectorId == null || connectorId.isBlank()) return;
        windows.computeIfAbsent(connectorId, k -> new BucketWindow(windowSize, bucketSize, clock))
                .record(success, latencyMs, costBps);
    }

    /**
     * 获取单个 connector 的当前指标快照。无样本时返回 samples=0 的空指标。
     */
    public ConnectorMetrics metrics(String connectorId) {
        BucketWindow w = windows.get(connectorId);
        if (w == null) {
            return new ConnectorMetrics(connectorId, 0.0, 0L, 0, 0, 0);
        }
        return w.snapshot(connectorId);
    }

    /**
     * 获取所有已观测 connector 的指标快照列表。
     */
    public List<ConnectorMetrics> metricsAll() {
        List<ConnectorMetrics> result = new ArrayList<>(windows.size());
        for (Map.Entry<String, BucketWindow> e : windows.entrySet()) {
            result.add(e.getValue().snapshot(e.getKey()));
        }
        return Collections.unmodifiableList(result);
    }

    /** 当前已观测的 connector 数量。 */
    public int observedConnectors() {
        return windows.size();
    }

    /** 清空所有指标（测试辅助）。 */
    public void clear() {
        windows.clear();
    }

    /**
     * 单个 connector 的时间桶窗口。
     *
     * <p>使用 {@link ArrayDeque} 存储最近 {@code windowSize} 个<b>预聚合桶</b>。
     * 每次 {@link #record} 落到当前时间桶；若当前时间跨入新桶，则推入新桶并按需
     * 淘汰最旧桶（保持窗口总跨度恒定）。</p>
     *
     * <p>{@link #snapshot} 仅遍历窗口内桶（O(桶数)），从尾部反向扫描连续失败桶
     * 计算 {@code recentFailures}。注意：连续失败改为按<b>桶</b>判定 —— 连续 N 个
     * 桶全部无成功事件即记 N；若最近桶有任何成功事件则清零。</p>
     */
    static final class BucketWindow {

        private final int capacity;
        private final Duration bucketSize;
        private final Clock clock;
        private final Deque<Bucket> buckets = new ArrayDeque<>();

        BucketWindow(int capacity, Duration bucketSize, Clock clock) {
            this.capacity = capacity;
            this.bucketSize = bucketSize;
            this.clock = clock;
        }

        private long currentBucketIndex() {
            return clock.millis() / bucketSize.toMillis();
        }

        synchronized void record(boolean success, long latencyMs, int costBps) {
            long idx = currentBucketIndex();
            Bucket tail = buckets.peekLast();
            if (tail == null || tail.index != idx) {
                // 进入新桶
                if (tail != null && (idx - tail.index) >= capacity) {
                    // 跨度过大（≥ capacity 桶）：直接清空，避免 ArrayDeque 一次性弹出 capacity 元素
                    buckets.clear();
                } else {
                    while (buckets.size() >= capacity) {
                        buckets.pollFirst();
                    }
                }
                Bucket fresh = new Bucket(idx);
                fresh.successCount = success ? 1 : 0;
                fresh.totalLatency = latencyMs;
                fresh.totalCost = costBps;
                fresh.count = 1;
                buckets.addLast(fresh);
                return;
            }
            if (success) tail.successCount++;
            tail.totalLatency += latencyMs;
            tail.totalCost += costBps;
            tail.count++;
        }

        synchronized ConnectorMetrics snapshot(String connectorId) {
            long currentIdx = currentBucketIndex();
            long minIdx = currentIdx - (capacity - 1);
            while (!buckets.isEmpty() && buckets.peekFirst().index < minIdx) {
                buckets.pollFirst();
            }

            int n = 0;
            int successCount = 0;
            long totalLatency = 0L;
            long totalCost = 0L;
            for (Bucket b : buckets) {
                n += b.count;
                successCount += b.successCount;
                totalLatency += b.totalLatency;
                totalCost += b.totalCost;
            }
            if (n == 0) {
                return new ConnectorMetrics(connectorId, 0.0, 0L, 0, 0, 0);
            }
            double successRate = (double) successCount / n;
            long avgLatency = totalLatency / n;
            int avgCost = (int) (totalCost / n);

            int recentFailures = 0;
            Bucket[] arr = buckets.toArray(new Bucket[0]);
            for (int i = arr.length - 1; i >= 0; i--) {
                Bucket b = arr[i];
                if (b.successCount == 0) {
                    recentFailures++;
                } else {
                    break;
                }
            }
            return new ConnectorMetrics(connectorId, successRate, avgLatency, avgCost,
                    recentFailures, n);
        }
    }

    /**
     * 单个时间桶的预聚合统计。
     */
    private static final class Bucket {
        final long index;
        int count;
        int successCount;
        long totalLatency;
        long totalCost;

        Bucket(long index) {
            this.index = index;
        }
    }
}
