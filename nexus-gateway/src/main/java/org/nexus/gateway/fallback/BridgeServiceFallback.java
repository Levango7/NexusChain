package org.nexus.gateway.fallback;

import org.nexus.sdk.client.feign.BridgeServiceFeignClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

/**
 * 跨链桥服务 Feign 调用降级处理（gateway → nexus-bridge）。
 *
 * <p>对应设计文档 §4.3.2 降级 fallback 类设计 + §4.4.1 Feign 接口清单。
 * 当 Feign 调用 nexus-bridge 失败（服务不可用 / 超时 / 熔断 / 限流）
 * 时，Spring Cloud OpenFeign 自动路由到本类对应方法，返回安全默认值。</p>
 *
 * <p>降级策略：
 * <ul>
 *   <li>跨链操作（{@code lock} / {@code mint} / {@code burn} / {@code unlock}）：
 *       返回包含 {@code status=FAILED} 的错误 Map，调用方按跨链失败处理
 *       （触发对账/告警，不静默放行）</li>
 *   <li>查询操作（{@code getTransaction} / {@code getBySourceHash} / {@code status}）：
 *       返回 {@code null}，调用方按查询失败处理（展示错误或重试）</li>
 * </ul></p>
 *
 * <p>设计原则 D10：返回 null/错误 Map，不抛异常，调用方无需额外 try-catch。
 * 跨链操作涉及资产锁定/铸造，降级时必须明确失败，避免重复锁定或资产丢失。</p>
 *
 * <p>本类保留 {@code @Component} 注解作为 Spring Bean，但 Phase 3 fallback 绑定后
 * 通过 {@code GatewayBridgeServiceFallbackFactory.create(Throwable)} 实例化，
 * 不再由 Spring 容器直接注入到 Feign 调用链。SCA Sentinel-Feign 集成后，
 * Sentinel 熔断/限流同样路由到本类（经 FallbackFactory 包装）。</p>
 */
@Component
public class BridgeServiceFallback implements BridgeServiceFeignClient {

    private static final Logger log = LoggerFactory.getLogger(BridgeServiceFallback.class);

    private static final String STATUS_KEY = "status";
    private static final String STATUS_FAILED = "FAILED";
    private static final String REASON_KEY = "reason";
    private static final String REASON_VALUE = "BRIDGE_SERVICE_UNAVAILABLE";

    @Override
    public Map<String, Object> lock(Map<String, Object> request) {
        log.error("lock Feign 降级触发: bridge-service 不可用, request={}", request);
        // TODO: 上报 Prometheus + 告警（跨链锁定失败需人工对账）
        return failedResult();
    }

    @Override
    public Map<String, Object> mint(Map<String, Object> request) {
        log.error("mint Feign 降级触发: bridge-service 不可用, request={}", request);
        // TODO: 上报 Prometheus + 告警（跨链铸造失败需人工对账）
        return failedResult();
    }

    @Override
    public Map<String, Object> burn(Map<String, Object> request) {
        log.error("burn Feign 降级触发: bridge-service 不可用, request={}", request);
        // TODO: 上报 Prometheus + 告警（跨链销毁失败需人工对账）
        return failedResult();
    }

    @Override
    public Map<String, Object> unlock(Map<String, Object> request) {
        log.error("unlock Feign 降级触发: bridge-service 不可用, request={}", request);
        // TODO: 上报 Prometheus + 告警（跨链解锁失败需人工对账）
        return failedResult();
    }

    @Override
    public Map<String, Object> getTransaction(String txId) {
        log.warn("getTransaction Feign 降级触发: bridge-service 不可用, txId={}", txId);
        return null;
    }

    @Override
    public Map<String, Object> getBySourceHash(String sourceTxHash) {
        log.warn("getBySourceHash Feign 降级触发: bridge-service 不可用, sourceTxHash={}", sourceTxHash);
        return null;
    }

    @Override
    public Map<String, Object> status() {
        log.warn("status Feign 降级触发: bridge-service 不可用, 返回 null");
        return null;
    }

    /**
     * 构造跨链操作失败响应 Map。
     *
     * <p>返回不可变单例 Map，包含 {@code status=FAILED} 与
     * {@code reason=BRIDGE_SERVICE_UNAVAILABLE}，供调用方判断降级。</p>
     *
     * @return 失败响应 Map
     */
    private static Map<String, Object> failedResult() {
        return Map.of(
                STATUS_KEY, STATUS_FAILED,
                REASON_KEY, REASON_VALUE
        );
    }
}