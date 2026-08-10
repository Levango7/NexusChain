package org.nexus.gateway.orchestration.routing.ai;

import java.util.Objects;

/**
 * 特征向量：路由模型推理输入。
 *
 * <p>封装单个 connector 在当前支付上下文下的可观测特征，供 {@link RoutingModel}
 * 进行打分/排序。所有字段为原始类型或 String，避免装箱开销，保证推理延迟 &lt; 50ms。</p>
 *
 * <p><b>字段语义</b>：</p>
 * <ul>
 *   <li>{@code connectorId} — 候选 connector 标识</li>
 *   <li>{@code successRate} — 滑动窗口内成功率，范围 [0.0, 1.0]</li>
 *   <li>{@code avgLatencyMs} — 滑动窗口内平均延迟（毫秒）</li>
 *   <li>{@code avgCostBps} — 滑动窗口内平均成本（basis points）</li>
 *   <li>{@code recentFailures} — 最近连续失败次数</li>
 *   <li>{@code amount} — 当前支付金额（最小单位）</li>
 *   <li>{@code currency} — 当前支付币种</li>
 * </ul>
 */
public final class ModelFeatures {

    private final String connectorId;
    private final double successRate;
    private final long avgLatencyMs;
    private final int avgCostBps;
    private final int recentFailures;
    private final long amount;
    private final String currency;

    public ModelFeatures(String connectorId, double successRate, long avgLatencyMs,
                         int avgCostBps, int recentFailures, long amount, String currency) {
        this.connectorId = connectorId;
        this.successRate = successRate;
        this.avgLatencyMs = avgLatencyMs;
        this.avgCostBps = avgCostBps;
        this.recentFailures = recentFailures;
        this.amount = amount;
        this.currency = currency;
    }

    public String connectorId() { return connectorId; }
    public double successRate() { return successRate; }
    public long avgLatencyMs() { return avgLatencyMs; }
    public int avgCostBps() { return avgCostBps; }
    public int recentFailures() { return recentFailures; }
    public long amount() { return amount; }
    public String currency() { return currency; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModelFeatures that)) return false;
        return Double.compare(that.successRate, successRate) == 0
                && avgLatencyMs == that.avgLatencyMs
                && avgCostBps == that.avgCostBps
                && recentFailures == that.recentFailures
                && amount == that.amount
                && Objects.equals(connectorId, that.connectorId)
                && Objects.equals(currency, that.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(connectorId, successRate, avgLatencyMs, avgCostBps,
                recentFailures, amount, currency);
    }

    @Override
    public String toString() {
        return "ModelFeatures{connectorId='" + connectorId + "', successRate=" + successRate
                + ", avgLatencyMs=" + avgLatencyMs + ", avgCostBps=" + avgCostBps
                + ", recentFailures=" + recentFailures + ", amount=" + amount
                + ", currency='" + currency + "'}";
    }
}