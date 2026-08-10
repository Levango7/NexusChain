package org.nexus.gateway.orchestration.routing.ai;

import java.time.Instant;
import java.util.Objects;

/**
 * A/B 测试结果记录：单次路由决策的归属与后续结果。
 *
 * <p>由 {@link AbTestRouter} 在路由决策时创建（outcome 初始为 {@code PENDING}），
 * 由 OrchestrationService 在支付完成后回填 outcome/latency。聚合由
 * {@link AbTestMetrics} 完成。</p>
 *
 * <p><b>字段语义</b>：</p>
 * <ul>
 *   <li>{@code routingMethod} — 路由方法：{@code "ai"} 或 {@code "rule"}</li>
 *   <li>{@code connectorId} — 实际使用的 connector</li>
 *   <li>{@code outcome} — 结果：{@code PENDING}/{@code SUCCESS}/{@code FAILURE}</li>
 *   <li>{@code latencyMs} — 端到端延迟（毫秒），完成后回填</li>
 *   <li>{@code timestamp} — 路由决策时间戳</li>
 * </ul>
 */
public final class AbTestResult {

    public enum Outcome { PENDING, SUCCESS, FAILURE }

    private final String routingMethod;
    private final String connectorId;
    private final Outcome outcome;
    private final long latencyMs;
    private final Instant timestamp;

    public AbTestResult(String routingMethod, String connectorId, Outcome outcome,
                        long latencyMs, Instant timestamp) {
        this.routingMethod = routingMethod;
        this.connectorId = connectorId;
        this.outcome = outcome;
        this.latencyMs = latencyMs;
        this.timestamp = timestamp;
    }

    /** 创建 PENDING 状态的初始记录（路由决策时）。 */
    public static AbTestResult pending(String routingMethod, String connectorId) {
        return new AbTestResult(routingMethod, connectorId, Outcome.PENDING, 0L, Instant.now());
    }

    /** 回填最终结果（支付完成后）。 */
    public AbTestResult withOutcome(Outcome outcome, long latencyMs) {
        return new AbTestResult(routingMethod, connectorId, outcome, latencyMs, timestamp);
    }

    public String routingMethod() { return routingMethod; }
    public String connectorId() { return connectorId; }
    public Outcome outcome() { return outcome; }
    public long latencyMs() { return latencyMs; }
    public Instant timestamp() { return timestamp; }

    public boolean isAiGroup() { return "ai".equals(routingMethod); }
    public boolean isRuleGroup() { return "rule".equals(routingMethod); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AbTestResult that)) return false;
        return latencyMs == that.latencyMs
                && Objects.equals(routingMethod, that.routingMethod)
                && Objects.equals(connectorId, that.connectorId)
                && outcome == that.outcome
                && Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(routingMethod, connectorId, outcome, latencyMs, timestamp);
    }

    @Override
    public String toString() {
        return "AbTestResult{routingMethod='" + routingMethod + "', connectorId='" + connectorId
                + "', outcome=" + outcome + ", latencyMs=" + latencyMs + ", timestamp=" + timestamp + "}";
    }
}