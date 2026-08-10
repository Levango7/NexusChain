package org.nexus.gateway.orchestration.routing.ai;

/**
 * Connector 历史指标模型：滑动窗口内聚合的可观测指标。
 *
 * <p>由 {@link MetricsCollector} 在支付事件流上维护，作为 {@link ModelFeatures}
 * 的构造原料。所有字段均为窗口内聚合值，不可变；每次窗口滚动由
 * {@link MetricsCollector} 重新计算并产出新实例。</p>
 *
 * <p><b>字段语义</b>：</p>
 * <ul>
 *   <li>{@code successRate} — 成功次数 / 样本数，范围 [0.0, 1.0]；样本数为 0 时返回 0.0</li>
 *   <li>{@code avgLatencyMs} — 平均延迟（毫秒），样本数为 0 时返回 0</li>
 *   <li>{@code avgCostBps} — 平均成本（basis points），样本数为 0 时返回 0</li>
 *   <li>{@code recentFailures} — 最近连续失败次数（窗口内尾部连续失败）</li>
 *   <li>{@code samples} — 窗口内样本数</li>
 * </ul>
 */
public final class ConnectorMetrics {

    private final String connectorId;
    private final double successRate;
    private final long avgLatencyMs;
    private final int avgCostBps;
    private final int recentFailures;
    private final int samples;

    public ConnectorMetrics(String connectorId, double successRate, long avgLatencyMs,
                            int avgCostBps, int recentFailures, int samples) {
        this.connectorId = connectorId;
        this.successRate = successRate;
        this.avgLatencyMs = avgLatencyMs;
        this.avgCostBps = avgCostBps;
        this.recentFailures = recentFailures;
        this.samples = samples;
    }

    /** 样本数是否足够支撑模型推理（避免冷启动偏差）。 */
    public boolean hasEnoughSamples(int minSamples) {
        return samples >= minSamples;
    }

    public String connectorId() { return connectorId; }
    public double successRate() { return successRate; }
    public long avgLatencyMs() { return avgLatencyMs; }
    public int avgCostBps() { return avgCostBps; }
    public int recentFailures() { return recentFailures; }
    public int samples() { return samples; }

    @Override
    public String toString() {
        return "ConnectorMetrics{connectorId='" + connectorId + "', successRate=" + successRate
                + ", avgLatencyMs=" + avgLatencyMs + ", avgCostBps=" + avgCostBps
                + ", recentFailures=" + recentFailures + ", samples=" + samples + "}";
    }
}