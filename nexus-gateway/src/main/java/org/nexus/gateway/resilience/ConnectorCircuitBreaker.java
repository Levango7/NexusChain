package org.nexus.gateway.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.nexus.gateway.orchestration.connector.FailureKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Connector 级熔断器管理组件。
 *
 * <p>为每个 Connector 实例（按 connector id 分桶）维护独立的 Resilience4j
 * {@link CircuitBreaker} 实例，配置按 connector 类型继承
 * （{@link ConnectorCircuitBreakerConfig#getEffectiveConfig(String)}）。
 * 分桶键与配置键分离：同类型的多个实例（如动态 PSP）各自独立熔断，互不牵连。
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
     * 获取（或按需创建）指定 connector 实例的熔断器。
     *
     * <p><b>两键分离</b>：分桶键 = {@code connectorId}（每个 connector 实例独立分桶，
     * 同类型的多个实例互不牵连），配置键 = {@code connectorType}（类型级覆盖配置继续生效，
     * 见 {@link ConnectorCircuitBreakerConfig#getEffectiveConfig(String)}）。
     * 首次请求时构建 Resilience4j {@link CircuitBreakerConfig}，
     * 以 connectorId 为名注册到 {@link CircuitBreakerRegistry}。</p>
     *
     * @param connectorId   connector 实例标识（分桶键，如 PSP 实例 id）
     * @param connectorType connector 类型标识（配置键，如 "chain", "stripe"）
     * @return 该 connector 实例的熔断器实例
     */
    public CircuitBreaker getOrCreate(String connectorId, String connectorType) {
        return circuitBreakers.computeIfAbsent(connectorId, id -> createCircuitBreaker(id, connectorType));
    }

    /**
     * 创建指定 connector 实例的熔断器（配置按类型继承）。
     */
    private CircuitBreaker createCircuitBreaker(String connectorId, String connectorType) {
        ConnectorCircuitBreakerConfig.CircuitBreakerProperties props = config.getEffectiveConfig(connectorType);

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(props.getFailureRateThreshold())
                .waitDurationInOpenState(props.getWaitDurationInOpenState())
                .slidingWindowSize(props.getSlidingWindowSize())
                .minimumNumberOfCalls(props.getMinimumNumberOfCalls())
                .build();

        CircuitBreaker cb = registry.circuitBreaker(connectorId, cbConfig);
        log.info("Created circuit breaker for connector '{}' (type '{}'): failureRateThreshold={}, waitDuration={}, slidingWindowSize={}, minCalls={}",
                connectorId, connectorType, props.getFailureRateThreshold(), props.getWaitDurationInOpenState(),
                props.getSlidingWindowSize(), props.getMinimumNumberOfCalls());
        return cb;
    }

    /**
     * 查询指定 connector 的熔断器状态。
     *
     * @param connectorId connector 实例标识（分桶键）
     * @return 熔断器状态枚举（CLOSED / OPEN / HALF_OPEN / DISABLED / FORCED_OPEN）
     */
    public CircuitBreaker.State getConnectorState(String connectorId) {
        CircuitBreaker cb = circuitBreakers.get(connectorId);
        if (cb == null) {
            return CircuitBreaker.State.DISABLED;
        }
        return cb.getState();
    }

    /**
     * 判断指定 connector 是否处于熔断状态（OPEN 或 FORCED_OPEN）。
     *
     * @param connectorId connector 实例标识（分桶键）
     * @return true 表示当前不允许调用该 connector
     */
    public boolean isCircuitOpen(String connectorId) {
        CircuitBreaker.State state = getConnectorState(connectorId);
        return state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
    }

    /**
     * 记录成功调用，通知熔断器。
     *
     * <p>耗时传真实值（纳秒）：Resilience4j 据此统计慢调用占比，传 0 会使
     * 慢调用阈值永不可达。</p>
     *
     * @param connectorId   connector 实例标识（分桶键）
     * @param durationNanos 本次调用真实耗时（纳秒）
     */
    public void recordSuccess(String connectorId, long durationNanos) {
        CircuitBreaker cb = circuitBreakers.get(connectorId);
        if (cb == null) {
            log.debug("No circuit breaker for connector '{}' yet; success not recorded", connectorId);
            return;
        }
        cb.onSuccess(durationNanos, TimeUnit.NANOSECONDS);
        log.debug("Circuit breaker '{}' recorded success in {} ns, state={}", connectorId, durationNanos, cb.getState());
    }

    /**
     * 记录失败调用，通知熔断器。
     *
     * <p>耗时与异常都传真实值：耗时用于慢调用统计；异常类型必须保留，
     * 否则业务拒绝（如 PSP decline）会被计成熔断失败而误熔健康通道。</p>
     *
     * @param connectorId   connector 实例标识（分桶键）
     * @param durationNanos 本次调用真实耗时（纳秒）
     * @param cause         本次失败的真实异常（不得为 null）
     */
    public void recordFailure(String connectorId, long durationNanos, Throwable cause) {
        CircuitBreaker cb = circuitBreakers.get(connectorId);
        if (cb == null) {
            log.debug("No circuit breaker for connector '{}' yet; failure not recorded", connectorId);
            return;
        }
        Objects.requireNonNull(cause, "cause must not be null");
        cb.onError(durationNanos, TimeUnit.NANOSECONDS, cause);
        log.debug("Circuit breaker '{}' recorded failure in {} ns, state={}", connectorId, durationNanos, cb.getState());
    }

    /**
     * 记录一次已分类的失败结果（支付路径专用，板 20261110-1511 裁决 ③ 接线）。
     *
     * <p>仅 {@link FailureKind#UNAVAILABLE} 计入熔断失败；DECLINE / CONFIG / null
     * 一律不计（见 {@link FailureKind} 的资金路径硬规则：连续业务拒绝不得熔掉
     * 健康通道）。支付结果路径没有真实异常对象，Resilience4j 事件流要求非 null
     * throwable，故以分类合成 cause。</p>
     */
    public void recordClassifiedFailure(String connectorId, long durationNanos, FailureKind kind) {
        if (!FailureKind.countsAsBreakerFailure(kind)) {
            return;
        }
        recordFailure(connectorId, durationNanos, new ClassifiedFailureException(kind));
    }

    /**
     * 重置指定 connector 的熔断器到 CLOSED 状态（强制恢复）。
     *
     * @param connectorId connector 实例标识（分桶键）
     */
    public void reset(String connectorId) {
        CircuitBreaker cb = circuitBreakers.get(connectorId);
        if (cb != null) {
            cb.reset();
            log.info("Circuit breaker '{}' reset to CLOSED", connectorId);
        }
    }

    /**
     * 获取所有已注册的 connector 实例标识（分桶键）。
     *
     * @return connector 实例标识集合
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
     * @param connectorId connector 实例标识（分桶键）
     * @return 熔断器实例，不存在则返回 null
     */
    public CircuitBreaker getCircuitBreaker(String connectorId) {
        return circuitBreakers.get(connectorId);
    }

    /**
     * 支付结果路径已分类失败的合成 cause（该路径无真实异常对象）。
     * 仅进入 Resilience4j 事件流，供运维追溯熔断触发原因，不作他用。
     */
    public static final class ClassifiedFailureException extends RuntimeException {
        private final FailureKind kind;

        ClassifiedFailureException(FailureKind kind) {
            super("Connector call failed (classified " + kind + ")");
            this.kind = kind;
        }

        public FailureKind kind() {
            return kind;
        }
    }
}