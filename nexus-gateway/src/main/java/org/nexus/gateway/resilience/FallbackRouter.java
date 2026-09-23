package org.nexus.gateway.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 降级路由器 — 当主 Connector 熔断时自动路由到备选 Connector。
 *
 * <p>降级路由策略通过配置定义，每个 connector 类型可配置一组有序的备选 connector 列表。
 * 路由时按列表顺序选择第一个未熔断的备选 connector，并发布 {@link FallbackEvent} 事件。</p>
 *
 * <p>配置示例（application.yml）：
 * <pre>
 * nexus:
 *   resilience:
 *     fallback:
 *       chain: ["consortium", "mock"]
 *       stripe: ["adyen", "http_psp", "mock"]
 * </pre></p>
 */
@Service
public class FallbackRouter {

    private static final Logger log = LoggerFactory.getLogger(FallbackRouter.class);

    private final ConnectorCircuitBreaker circuitBreaker;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, List<String>> fallbackRoutes = new ConcurrentHashMap<>();

    public FallbackRouter(ConnectorCircuitBreaker circuitBreaker,
                          ApplicationEventPublisher eventPublisher) {
        this.circuitBreaker = circuitBreaker;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 配置降级路由映射。
     *
     * @param routes 降级路由配置 map（key = 主 connector，value = 备选 connector 列表）
     */
    public void configureRoutes(Map<String, List<String>> routes) {
        fallbackRoutes.clear();
        fallbackRoutes.putAll(routes);
        log.info("Fallback routes configured: {}", fallbackRoutes);
    }

    /**
     * 更新单个 connector 的降级路由配置。
     *
     * @param connectorType 主 connector 类型
     * @param fallbacks     备选 connector 列表
     */
    public void updateRoute(String connectorType, List<String> fallbacks) {
        fallbackRoutes.put(connectorType, new ArrayList<>(fallbacks));
        log.info("Updated fallback route for '{}': {}", connectorType, fallbacks);
    }

    /**
     * 获取指定 connector 的降级路由配置。
     *
     * @param connectorType 主 connector 类型
     * @return 备选 connector 列表（不可变），无配置时返回空列表
     */
    public List<String> getFallbacks(String connectorType) {
        return Collections.unmodifiableList(fallbackRoutes.getOrDefault(connectorType, List.of()));
    }

    /**
     * 获取所有降级路由配置。
     *
     * @return 降级路由配置 map（不可变视图）
     */
    public Map<String, List<String>> getAllRoutes() {
        return Collections.unmodifiableMap(fallbackRoutes);
    }

    /**
     * 当主 Connector 熔断时，选择第一个可用的备选 Connector。
     *
     * <p>按备选列表顺序遍历，跳过同样处于熔断状态的备选 connector，
     * 返回第一个未熔断的备选。选择成功后发布 {@link FallbackEvent}。</p>
     *
     * @param connectorType 发生熔断的主 connector 类型
     * @return 第一个可用的备选 connector 类型，无可用备选时返回 null
     */
    public String routeToFallback(String connectorType) {
        List<String> fallbacks = fallbackRoutes.get(connectorType);
        if (fallbacks == null || fallbacks.isEmpty()) {
            log.warn("No fallback routes configured for connector '{}'", connectorType);
            return null;
        }

        for (String fallback : fallbacks) {
            if (!circuitBreaker.isCircuitOpen(fallback)) {
                String reason = "CIRCUIT_OPEN";
                log.info("Fallback routing: {} -> {} (reason={})", connectorType, fallback, reason);
                publishFallbackEvent(connectorType, fallback, reason);
                return fallback;
            }
            log.debug("Fallback connector '{}' also has open circuit, skipping", fallback);
        }

        log.error("All fallback connectors for '{}' are also in open circuit state", connectorType);
        return null;
    }

    /**
     * 判断指定 connector 是否配置了降级路由。
     *
     * @param connectorType 主 connector 类型
     * @return true 表示有备选路由配置
     */
    public boolean hasFallback(String connectorType) {
        List<String> fallbacks = fallbackRoutes.get(connectorType);
        return fallbacks != null && !fallbacks.isEmpty();
    }

    /**
     * 发布降级事件。
     */
    private void publishFallbackEvent(String fromConnector, String toConnector, String reason) {
        FallbackEvent event = new FallbackEvent(this, fromConnector, toConnector, reason);
        eventPublisher.publishEvent(event);
    }
}