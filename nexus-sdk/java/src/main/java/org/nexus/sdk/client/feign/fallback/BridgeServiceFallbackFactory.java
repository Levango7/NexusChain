package org.nexus.sdk.client.feign.fallback;

import org.nexus.sdk.client.feign.BridgeServiceFeignClient;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * BridgeServiceFeignClient 的 FallbackFactory 占位接口。
 *
 * <p>本接口在 nexus-sdk 定义（Phase 3 fallback 绑定方案 G，设计文档 §4.4.1 / §4.4.4），
 * 实现类由各消费方模块提供，按服务定制降级语义：
 * <ul>
 *   <li>gateway：{@code org.nexus.gateway.fallback.GatewayBridgeServiceFallbackFactory}
 *       （跨链桥降级，复用 BridgeServiceFallback，跨链操作返回 FAILED Map）</li>
 * </ul></p>
 *
 * <p>设计原则：
 * <ul>
 *   <li>本接口为空接口（仅继承 {@code FallbackFactory<T>}），不引入 gateway
 *       特定依赖，保持 nexus-sdk 作为底层 SDK 的依赖纯洁性</li>
 *   <li>{@code FallbackFactory<T>} 比 {@code fallback = T.class} 更灵活：可在
 *       {@code create(Throwable cause)} 中获取触发降级的异常，区分限流/熔断/服务不可用</li>
 *   <li>nexus-sdk 的 {@code @FeignClient} 通过 {@code fallbackFactory} 属性指向本接口，
 *       Spring Cloud OpenFeign 在运行时从 Spring 容器中查找本接口的实现 Bean</li>
 * </ul></p>
 *
 * @see BridgeServiceFeignClient
 * @see org.springframework.cloud.openfeign.FallbackFactory
 */
public interface BridgeServiceFallbackFactory extends FallbackFactory<BridgeServiceFeignClient> {
}