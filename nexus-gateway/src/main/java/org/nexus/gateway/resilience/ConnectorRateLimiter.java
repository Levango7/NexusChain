package org.nexus.gateway.resilience;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.internal.AtomicRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connector 出口限流管理组件（PSP 侧速率护栏，2026-11-17）。
 *
 * <p>为每个 connector 实例（按 connector id 分桶）维护独立的 Resilience4j
 * {@link RateLimiter}，形态与 {@link ConnectorCircuitBreaker} 对齐：
 * 分桶键 = connectorId（实例级独立），配置键 = connectorType（类型级覆盖）。</p>
 *
 * <p><b>默认关闭</b>：{@code nexus.resilience.connector-rate-limit.enabled=false}
 * （默认）时 {@link #tryAcquire} 恒返回 true，零行为变更。只有在运维确认渠道
 * 有真实出口配额时才打开——见 {@link ConnectorRateLimiterConfig} 的说明。</p>
 *
 * <p><b>消费语义（关键）</b>：限流拒绝通过 {@link #tryAcquire} 的 false 返回，
 * 由编排层<b>跳过该 connector 走 failover</b>，不抛异常、不进熔断器、不喂 AI 路由
 * ——本地节流不代表渠道健康状态，绝不应当作 {@code UNAVAILABLE} 计入熔断失败
 * （{@code FailureKind} 的资金路径硬规则：只有渠道侧不可用才熔断）。</p>
 */
@Component
public class ConnectorRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ConnectorRateLimiter.class);

    private final ConnectorRateLimiterConfig config;
    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    public ConnectorRateLimiter(ConnectorRateLimiterConfig config) {
        this.config = config;
    }

    /**
     * 尝试为一次对指定 connector 的调用获取许可。
     *
     * <p>语义：{@code true} = 放行（本次调用消耗一个许可）；{@code false} =
     * 本周期配额已用尽，调用方应跳过该 connector 走 failover。</p>
     *
     * <p>总开关关闭时恒返回 true（零开销路径：不查 map、不建 limiter）。</p>
     *
     * @param connectorId   connector 实例标识（分桶键）
     * @param connectorType connector 类型标识（配置键，如 "chain", "stripe"）
     * @return 是否允许本次调用
     */
    public boolean tryAcquire(String connectorId, String connectorType) {
        if (!config.isEnabled()) {
            return true;
        }
        RateLimiter limiter = limiters.computeIfAbsent(connectorId,
                id -> createLimiter(id, connectorType));
        boolean acquired = limiter.acquirePermission();
        if (!acquired) {
            log.warn("Rate limiter REJECTED call: connector='{}' (type='{}'), limit={}/{}",
                    connectorId, connectorType,
                    limiter.getRateLimiterConfig().getLimitForPeriod(),
                    limiter.getRateLimiterConfig().getLimitRefreshPeriod());
        }
        return acquired;
    }

    /**
     * 获取（或按需创建）指定 connector 实例的限流器。供测试与监控读取。
     */
    public RateLimiter getOrCreate(String connectorId, String connectorType) {
        return limiters.computeIfAbsent(connectorId, id -> createLimiter(id, connectorType));
    }

    /**
     * 指定 connector 是否已被限流器管理（ever-acquired）。
     */
    public boolean isTracked(String connectorId) {
        return limiters.containsKey(connectorId);
    }

    private RateLimiter createLimiter(String connectorId, String connectorType) {
        ConnectorRateLimiterConfig.RateLimitProperties props = config.getEffectiveConfig(connectorType);

        RateLimiterConfig rlConfig = RateLimiterConfig.custom()
                .limitForPeriod(props.getLimitForPeriod())
                .limitRefreshPeriod(props.getLimitRefreshPeriod())
                // 0 = tryAcquire 语义：拿不到许可立即返回 false，绝不排队等待——
                // 支付主路径不能被限流器阻塞（阻塞会拖垮整个编排线程池）。
                .timeoutDuration(Duration.ZERO)
                .build();

        RateLimiter limiter = new AtomicRateLimiter(connectorId, rlConfig);
        log.info("Created rate limiter for connector '{}' (type '{}'): limitForPeriod={}, limitRefreshPeriod={}, timeout=0(tryAcquire)",
                connectorId, connectorType, props.getLimitForPeriod(), props.getLimitRefreshPeriod());
        return limiter;
    }
}
