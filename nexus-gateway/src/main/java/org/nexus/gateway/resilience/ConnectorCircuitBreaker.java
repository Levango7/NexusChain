package org.nexus.gateway.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Connector 级熔断器管理组件。
 *
 * <p>为每个 Connector 类型维护独立的 Resilience4j {@link CircuitBreaker} 实例，
 * 支持状态转换 CLOSED → OPEN → HALF_OPEN → CLOSED。
 * 熔断时由 {@link FallbackRouter} 自动路由到备选 Connector。</p>
 *
 * <p>使用 {@link CircuitBreakerRegistry} 管理多个熔断器实例，
 * 配置来源于 {@link ConnectorCircuitBreakerConfig}，支持按 connector 类型独立覆盖。</p>
 */
@Component
public class ConnectorCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ConnectorCircuitBreaker.class);

    private final ConnectorCircuitBreakerConfig config;
    private final CircuitBreakerRegistry registry;
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    public ConnectorCircuitBreaker(ConnectorCircuitBreakerConfig config) {
        this.config = config;
        this.registry = CircuitBreakerRegistry.ofDefaults();
    }

    /**
     * 获取（或按需创建）指定 connector 类型的熔断器实例。
     *
     * <p>首次请求时根据 {@link ConnectorCircuitBreakerConfig#getEffectiveConfig(String)}
     * 构建 Resilience4j {@link CircuitBreakerConfig}，注册到 {@link CircuitBreakerRegistry}。</p>
     *
     * @param connectorType connector 类型标识（如 "chain", "stripe"）
     * @return 该 connector 的熔断器实例
     */
    public CircuitBreaker getOrCreate(String connectorType) {
        return circuitBreakers.computeIfAbsent(connectorType, this::createCircuitBreaker);
    }

    /**
     * 创建指定 connector 类型的熔断器。
     */
    private CircuitBreaker createCircuitBreaker(String connectorType) {
        ConnectorCircuitBreakerConfig.CircuitBreakerProperties props = config.getEffectiveConfig(connectorType);

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(props.getFailureRateThreshold())
                .waitDurationInOpenState(props.getWaitDurationInOpenState())
                .slidingWindowSize(props.getSlidingWindowSize())
                .minimumNumberOfCalls(props.getMinimumNumberOfCalls())
                .build();

        CircuitBreaker cb = registry.circuitBreaker(connectorType, cbConfig);
        log.info("Created circuit breaker for connector '{}': failureRateThreshold={}, waitDuration={}, slidingWindowSize={}, minCalls={}",
                connectorType, props.getFailureRateThreshold(), props.getWaitDurationInOpenState(),
                props.getSlidingWindowSize(), props.getMinimumNumberOfCalls());
        return cb;
    }

    /**
     * 查询指定 connector 的熔断器状态。
     *
     * @param connectorType connector 类型标识
     * @return 熔断器状态枚举（CLOSED / OPEN / HALF_OPEN / DISABLED / FORCED_OPEN）
     */
    public CircuitBreaker.State getConnectorState(String connectorType) {
        CircuitBreaker cb = circuitBreakers.get(connectorType);
        if (cb == null) {
            return CircuitBreaker.State.DISABLED;
        }
        return cb.getState();
    }

    /**
     * 判断指定 connector 是否处于熔断状态（OPEN 或 FORCED_OPEN）。
     *
     * @param connectorType connector 类型标识
     * @return true 表示当前不允许调用该 connector
     */
    public boolean isCircuitOpen(String connectorType) {
        CircuitBreaker.State state = getConnectorState(connectorType);
        return state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
    }

    /**
     * 记录成功调用，通知熔断器。
     *
     * @param connectorType connector 类型标识
     */
    public void recordSuccess(String connectorType) {
        CircuitBreaker cb = getOrCreate(connectorType);
        cb.onSuccess(0, TimeUnit.NANOSECONDS);
        log.debug("Circuit breaker '{}' recorded success, state={}", connectorType, cb.getState());
    }

    /**
     * 记录失败调用，通知熔断器。
     *
     * @param connectorType connector 类型标识
     */
    public void recordFailure(String connectorType) {
        CircuitBreaker cb = getOrCreate(connectorType);
        cb.onError(0, TimeUnit.NANOSECONDS, new RuntimeException("Connector call failed"));
        log.debug("Circuit breaker '{}' recorded failure, state={}", connectorType, cb.getState());
    }

    /**
     * 重置指定 connector 的熔断器到 CLOSED 状态（强制恢复）。
     *
     * @param connectorType connector 类型标识
     */
    public void reset(String connectorType) {
        CircuitBreaker cb = circuitBreakers.get(connectorType);
        if (cb != null) {
            cb.reset();
            log.info("Circuit breaker '{}' reset to CLOSED", connectorType);
        }
    }

    /**
     * 获取所有已注册的 connector 类型。
     *
     * @return connector 类型集合
     */
    public Set<String> getRegisteredConnectors() {
        return circuitBreakers.keySet();
    }

    /**
     * 获取底层 CircuitBreakerRegistry（供 {@link ResilienceMetrics} 注册指标使用）。
     *
     * @return CircuitBreakerRegistry 实例
     */
    public CircuitBreakerRegistry getRegistry() {
        return registry;
    }

    /**
     * 获取指定 connector 的熔断器实例（可能为 null，如尚未创建）。
     *
     * @param connectorType connector 类型标识
     * @return 熔断器实例，不存在则返回 null
     */
    public CircuitBreaker getCircuitBreaker(String connectorType) {
        return circuitBreakers.get(connectorType);
    }
}