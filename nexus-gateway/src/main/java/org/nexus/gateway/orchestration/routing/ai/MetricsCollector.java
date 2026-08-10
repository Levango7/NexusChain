package org.nexus.gateway.orchestration.routing.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标收集器：从支付事件流中收集每个 connector 的历史数据（成功率/延迟/成本），
 * 使用滑动窗口聚合。
 *
 * <p><b>滑动窗口语义</b>：每个 connector 维护一个容量为 {@code windowSize} 的
 * 环形缓冲区，仅保留最近 {@code windowSize} 条支付事件。窗口满后新事件挤掉最旧
 * 事件，保证指标反映近期状态，避免陈旧数据污染路由决策。</p>
 *
 * <p><b>线程安全</b>：每个 connector 拥有独立的 {@link SampleWindow}，其内部
 * 操作通过 {@code synchronized} 串行化。{@link #record} 与 {@link #metrics}
 * 可并发调用，{@link #metricsAll} 返回快照列表。</p>
 *
 * <p><b>事件字段</b>：单条支付事件包含 connectorId / success（布尔）/ latencyMs /
 * costBps。{@link #record} 由 OrchestrationService 在连接器调用完成后回调。</p>
 */

public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    private final Map<String, SampleWindow> windows = new ConcurrentHashMap<>();
    private final int windowSize;

    public MetricsCollector() {
        this(1000);
    }

    public MetricsCollector(int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be positive, got " + windowSize);
        }
        this.windowSize = windowSize;
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
        windows.computeIfAbsent(connectorId, k -> new SampleWindow(windowSize))
                .record(success, latencyMs, costBps);
    }

    /**
     * 获取单个 connector 的当前指标快照。无样本时返回 samples=0 的空指标。
     */
    public ConnectorMetrics metrics(String connectorId) {
        SampleWindow w = windows.get(connectorId);
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
        for (Map.Entry<String, SampleWindow> e : windows.entrySet()) {
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
     * 单个 connector 的滑动窗口。
     *
     * <p>使用 {@link ArrayDeque} 存储最近 {@code windowSize} 条样本，
     * 容量超限时丢弃头部最旧样本。聚合时遍历窗口计算成功率/平均延迟/平均成本，
     * 并从尾部反向扫描计算最近连续失败次数。</p>
     */
    static final class SampleWindow {

        private final int capacity;
        private final Deque<Sample> samples;

        SampleWindow(int capacity) {
            this.capacity = capacity;
            this.samples = new ArrayDeque<>(capacity);
        }

        synchronized void record(boolean success, long latencyMs, int costBps) {
            if (samples.size() == capacity) {
                samples.pollFirst();
            }
            samples.addLast(new Sample(success, latencyMs, costBps));
        }

        synchronized ConnectorMetrics snapshot(String connectorId) {
            int n = samples.size();
            if (n == 0) {
                return new ConnectorMetrics(connectorId, 0.0, 0L, 0, 0, 0);
            }
            int successCount = 0;
            long totalLatency = 0L;
            long totalCost = 0L;
            for (Sample s : samples) {
                if (s.success) successCount++;
                totalLatency += s.latencyMs;
                totalCost += s.costBps;
            }
            double successRate = (double) successCount / n;
            long avgLatency = totalLatency / n;
            int avgCost = (int) (totalCost / n);

            // 最近连续失败次数：从尾部反向扫描
            int recentFailures = 0;
            for (Sample s : reverseView(samples)) {
                if (!s.success) {
                    recentFailures++;
                } else {
                    break;
                }
            }
            return new ConnectorMetrics(connectorId, successRate, avgLatency, avgCost,
                    recentFailures, n);
        }

        private static List<Sample> reverseView(Deque<Sample> deque) {
            List<Sample> reversed = new ArrayList<>(deque);
            Collections.reverse(reversed);
            return reversed;
        }
    }

    /** 单条支付样本。 */
    private record Sample(boolean success, long latencyMs, int costBps) {}
}