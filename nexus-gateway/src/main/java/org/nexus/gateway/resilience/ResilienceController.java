package org.nexus.gateway.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 熔断状态 REST API — 提供熔断器状态查询、重置、降级路由配置管理。
 *
 * <p>端点：</p>
 * <ul>
 *   <li>GET  /api/v1/resilience/circuit-breakers — 查看所有 Connector 熔断器状态</li>
 *   <li>POST /api/v1/resilience/circuit-breakers/{connector}/reset — 重置熔断器（强制 CLOSED）</li>
 *   <li>GET  /api/v1/resilience/fallbacks — 查看降级路由配置</li>
 *   <li>PUT  /api/v1/resilience/fallbacks/{connector} — 更新降级路由配置</li>
 *   <li>GET  /api/v1/resilience/metrics — 查看熔断指标汇总</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/resilience")
public class ResilienceController {

    private static final Logger log = LoggerFactory.getLogger(ResilienceController.class);

    private final ConnectorCircuitBreaker circuitBreaker;
    private final ConnectorCircuitBreakerConfig config;
    private final FallbackRouter fallbackRouter;
    private final ResilienceMetrics metrics;

    public ResilienceController(ConnectorCircuitBreaker circuitBreaker,
                                 ConnectorCircuitBreakerConfig config,
                                 FallbackRouter fallbackRouter,
                                 ResilienceMetrics metrics) {
        this.circuitBreaker = circuitBreaker;
        this.config = config;
        this.fallbackRouter = fallbackRouter;
        this.metrics = metrics;
    }

    /**
     * 查看所有 Connector 熔断器状态。
     *
     * @return 所有已注册 connector 的熔断器状态列表
     */
    @GetMapping("/circuit-breakers")
    public ResponseEntity<List<CircuitBreakerStateDTO>> getAllCircuitBreakers() {
        metrics.refreshAllGauges();
        List<CircuitBreakerStateDTO> result = new ArrayList<>();

        for (String connectorType : circuitBreaker.getRegisteredConnectors()) {
            result.add(buildStateDTO(connectorType));
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 重置指定 Connector 的熔断器（强制 CLOSED）。
     *
     * @param connector connector 类型标识
     * @return 重置后的熔断器状态
     */
    @PostMapping("/circuit-breakers/{connector}/reset")
    public ResponseEntity<CircuitBreakerStateDTO> resetCircuitBreaker(@PathVariable String connector) {
        log.info("Resetting circuit breaker for connector '{}'", connector);
        circuitBreaker.reset(connector);
        metrics.refreshAllGauges();
        return ResponseEntity.ok(buildStateDTO(connector));
    }

    /**
     * 查看降级路由配置。
     *
     * @return 所有 connector 的降级路由配置列表
     */
    @GetMapping("/fallbacks")
    public ResponseEntity<List<FallbackConfigDTO>> getAllFallbacks() {
        List<FallbackConfigDTO> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : fallbackRouter.getAllRoutes().entrySet()) {
            result.add(new FallbackConfigDTO(entry.getKey(), entry.getValue()));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 更新指定 Connector 的降级路由配置。
     *
     * @param connector connector 类型标识
     * @param dto       新的降级路由配置
     * @return 更新后的降级路由配置
     */
    @PutMapping("/fallbacks/{connector}")
    public ResponseEntity<FallbackConfigDTO> updateFallback(@PathVariable String connector,
                                                             @RequestBody FallbackConfigDTO dto) {
        log.info("Updating fallback route for connector '{}': {}", connector, dto.getFallbacks());
        fallbackRouter.updateRoute(connector, dto.getFallbacks());
        return ResponseEntity.ok(new FallbackConfigDTO(connector, fallbackRouter.getFallbacks(connector)));
    }

    /**
     * 查看熔断指标汇总。
     *
     * @return 所有 connector 的熔断指标汇总 map
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        metrics.refreshAllGauges();
        Map<String, Object> result = new HashMap<>();

        List<CircuitBreakerStateDTO> states = new ArrayList<>();
        for (String connectorType : circuitBreaker.getRegisteredConnectors()) {
            states.add(buildStateDTO(connectorType));
        }
        result.put("circuitBreakers", states);
        result.put("fallbackRoutes", fallbackRouter.getAllRoutes());

        return ResponseEntity.ok(result);
    }

    /**
     * 构建指定 connector 的熔断器状态 DTO。
     */
    private CircuitBreakerStateDTO buildStateDTO(String connectorType) {
        CircuitBreaker.State state = circuitBreaker.getConnectorState(connectorType);
        ConnectorCircuitBreakerConfig.CircuitBreakerProperties props = config.getEffectiveConfig(connectorType);

        CircuitBreakerStateDTO dto = new CircuitBreakerStateDTO();
        dto.setConnector(connectorType);
        dto.setState(state.name());
        dto.setStateValue(ResilienceMetrics.mapStateToValue(state));

        CircuitBreaker cb = circuitBreaker.getCircuitBreaker(connectorType);
        if (cb != null) {
            dto.setFailureRate(cb.getMetrics().getFailureRate());
        } else {
            dto.setFailureRate(0f);
        }

        dto.setFailureRateThreshold(props.getFailureRateThreshold().intValue());
        dto.setWaitDurationInOpenStateSeconds(props.getWaitDurationInOpenState().getSeconds());
        dto.setSlidingWindowSize(props.getSlidingWindowSize());
        dto.setMinimumNumberOfCalls(props.getMinimumNumberOfCalls());

        return dto;
    }
}