package org.nexus.gateway.fallback;

import org.nexus.sdk.client.feign.BridgeServiceFeignClient;
import org.nexus.sdk.client.feign.fallback.BridgeServiceFallbackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * gateway 侧 BridgeServiceFeignClient 的 FallbackFactory 实现。
 *
 * <p>对应设计文档 §4.4.4（Phase 3 fallback 绑定方案 G，与 §4.4.2 同构）。
 * 复用现有 {@link BridgeServiceFallback} 的降级逻辑，包装为 FallbackFactory
 * 以获取触发降级的异常（cause），区分限流/熔断/服务不可用。</p>
 *
 * <p>降级语义（gateway 侧）：
 * <ul>
 *   <li>跨链操作（{@code lock} / {@code mint} / {@code burn} / {@code unlock}）：
 *       返回包含 {@code status=FAILED} 的错误 Map，调用方按跨链失败处理
 *       （触发对账/告警，不静默放行，避免重复锁定或资产丢失）</li>
 *   <li>查询操作（{@code getTransaction} / {@code getBySourceHash} / {@code status}）：
 *       返回 {@code null}，调用方按查询失败处理（展示错误或重试）</li>
 * </ul></p>
 *
 * <p>本类通过 {@code @Component} 注册为 Spring Bean，Spring Cloud OpenFeign 在
 * Feign 调用失败时自动调用 {@link #create(Throwable)} 获取降级实例。
 * SCA Sentinel-Feign 集成后，Sentinel 熔断/限流同样路由到本类。</p>
 *
 * @see BridgeServiceFallback
 * @see BridgeServiceFallbackFactory
 */
@Component
public class GatewayBridgeServiceFallbackFactory implements BridgeServiceFallbackFactory {

    private static final Logger log = LoggerFactory.getLogger(GatewayBridgeServiceFallbackFactory.class);

    @Override
    public BridgeServiceFeignClient create(Throwable cause) {
        log.error("BridgeServiceFeignClient 降级触发, cause={}: {}",
                cause.getClass().getSimpleName(), cause.getMessage(), cause);
        return new BridgeServiceFallback();  // 复用现有 fallback 类
    }
}