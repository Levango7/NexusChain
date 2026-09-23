package org.nexus.gateway.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 熔断指标组件 — 使用 Micrometer 暴露熔断器和降级路由的运行指标。
 *
 * <p>暴露的指标：</p>
 * <ul>
 *   <li>{@code nexus.circuitbreaker.state{connector=xxx}} — Gauge (0=CLOSED, 1=OPEN, 2=HALF_OPEN, 3=DISABLED, 4=FORCED_OPEN)</li>
 *   <li>{@code nexus.circuitbreaker.failure.rate{connector=xxx}} — Gauge (失败率百分比)</li>
 *   <li>{@code nexus.fallback.count{from=xxx,to=xxx}} — Counter (降级次数)</li>
 *   <li>{@code nexus.circuitbreaker.calls{connector=xxx,type=success/failure}} — Counter (调用次数)</li>
 * </ul>
 *
 * <p>降级计数器通过监听 {@link FallbackEvent} 自动更新。</p>
 */
@Component
public class ResilienceMetrics {

    private static final Logger log = LoggerFactory.getLogger(ResilienceMetrics.class);

    private final MeterRegistry meterRegistry;
    private final ConnectorCircuitBreaker circuitBreaker;
    private final ConcurrentHashMap<String, AtomicInteger> stateGauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> failureRateGauges = new ConcurrentHashMap<>();

    public ResilienceMetrics(MeterRegistry meterRegistry, ConnectorCircuitBreaker circuitBreaker) {
        this.meterRegistry = meterRegistry;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * 注册指定 connector 的熔断器指标。
     *
     * <p>为每个 connector 创建 state Gauge 和 failure rate Gauge，
     * 以及 success/failure 两个 Counter。Gauge 值通过 {@link AtomicInteger} 动态更新。</p>
     *
     * @param connectorType connector 类型标识
     */
    public void registerConnectorMetrics(String connectorType) {
        // State gauge: 0=CLOSED, 1=OPEN, 2=HALF_OPEN, 3=DISABLED, 4=FORCED_OPEN
        AtomicInteger stateGauge = stateGauges.computeIfAbsent(connectorType, k -> {
            AtomicInteger gauge = new AtomicInteger(0);
            meterRegistry.gauge("nexus.circuitbreaker.state",
                    io.micrometer.core.instrument.Tags.of("connector", k),
                    gauge);
            return gauge;
        });
        updateStateGauge(connectorType, stateGauge);

        // Failure rate gauge
        AtomicInteger failureRateGauge = failureRateGauges.computeIfAbsent(connectorType, k -> {
            AtomicInteger gauge = new AtomicInteger(0);
            meterRegistry.gauge("nexus.circuitbreaker.failure.rate",
                    io.micrometer.core.instrument.Tags.of("connector", k),
                    gauge);
            return gauge;
        });
        updateFailureRateGauge(connectorType, failureRateGauge);

        // Success/failure call counters
        Counter.builder("nexus.circuitbreaker.calls")
                .tag("connector", connectorType)
                .tag("type", "success")
                .register(meterRegistry);

        Counter.builder("nexus.circuitbreaker.calls")
                .tag("connector", connectorType)
                .tag("type", "failure")
                .register(meterRegistry);

        log.debug("Registered metrics for connector '{}'", connectorType);
    }

    /**
     * 更新指定 connector 的状态 Gauge 值。
     */
    private void updateStateGauge(String connectorType, AtomicInteger stateGauge) {
        CircuitBreaker.State state = circuitBreaker.getConnectorState(connectorType);
        int stateValue = mapStateToValue(state);
        stateGauge.set(stateValue);
    }

    /**
     * 更新指定 connector 的失败率 Gauge 值。
     */
    private void updateFailureRateGauge(String connectorType, AtomicInteger failureRateGauge) {
        CircuitBreaker cb = circuitBreaker.getCircuitBreaker(connectorType);
        if (cb != null) {
            float failureRate = cb.getMetrics().getFailureRate();
            failureRateGauge.set((int) failureRate);
        } else {
            failureRateGauge.set(0);
        }
    }

    /**
     * 刷新所有已注册 connector 的 Gauge 指标值。
     * 建议在定时任务或 API 查询时调用以获取最新值。
     */
    public void refreshAllGauges() {
        for (Map.Entry<String, AtomicInteger> entry : stateGauges.entrySet()) {
            updateStateGauge(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, AtomicInteger> entry : failureRateGauges.entrySet()) {
            updateFailureRateGauge(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 记录调用成功，更新 Counter。
     *
     * @param connectorType connector 类型标识
     */
    public void recordCallSuccess(String connectorType) {
        Counter.builder("nexus.circuitbreaker.calls")
                .tag("connector", connectorType)
                .tag("type", "success")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录调用失败，更新 Counter。
     *
     * @param connectorType connector 类型标识
     */
    public void recordCallFailure(String connectorType) {
        Counter.builder("nexus.circuitbreaker.calls")
                .tag("connector", connectorType)
                .tag("type", "failure")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 监听降级事件，自动递增降级计数器。
     *
     * @param event 降级事件
     */
    @EventListener
    public void onFallbackEvent(FallbackEvent event) {
        Counter.builder("nexus.fallback.count")
                .tag("from", event.getFromConnector())
                .tag("to", event.getToConnector())
                .register(meterRegistry)
                .increment();
        log.debug("Fallback counter incremented: {} -> {}", event.getFromConnector(), event.getToConnector());
    }

    /**
     * 将 Resilience4j 状态枚举映射为指标数值。
     *
     * @param state 熔断器状态
     * @return 数值表示 (0=CLOSED, 1=OPEN, 2=HALF_OPEN, 3=DISABLED, 4=FORCED_OPEN)
     */
    public static int mapStateToValue(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> 0;
            case OPEN -> 1;
            case HALF_OPEN -> 2;
            case DISABLED -> 3;
            case FORCED_OPEN -> 4;
            case METRICS_ONLY -> 5;
            default -> -1;
        };
    }
}