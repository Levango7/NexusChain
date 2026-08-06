package org.nexus.sdk.client.feign.fallback;

import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * SigningServiceFeignClient 的 FallbackFactory 占位接口。
 *
 * <p>本接口在 nexus-sdk 定义（Phase 3 fallback 绑定方案 G，设计文档 §4.4.1），
 * 实现类由各消费方模块提供，按服务定制降级语义：
 * <ul>
 *   <li>gateway：{@code org.nexus.gateway.fallback.GatewaySigningServiceFallbackFactory}
 *       （退款/支付降级，复用 SigningServiceFallback，返回 null 标记 FAILED）</li>
 *   <li>wallet-service：{@code org.nexus.walletsvc.fallback.WalletSvcSigningServiceFallbackFactory}
 *       （提现降级，提现签名失败标记 PENDING_RETRY/FAILED）</li>
 * </ul></p>
 *
 * <p>设计原则：
 * <ul>
 *   <li>本接口为空接口（仅继承 {@code FallbackFactory<T>}），不引入 gateway/wallet-service
 *       特定依赖，保持 nexus-sdk 作为底层 SDK 的依赖纯洁性</li>
 *   <li>{@code FallbackFactory<T>} 比 {@code fallback = T.class} 更灵活：可在
 *       {@code create(Throwable cause)} 中获取触发降级的异常，区分限流/熔断/服务不可用</li>
 *   <li>nexus-sdk 的 {@code @FeignClient} 通过 {@code fallbackFactory} 属性指向本接口，
 *       Spring Cloud OpenFeign 在运行时从 Spring 容器中查找本接口的实现 Bean</li>
 * </ul></p>
 *
 * @see SigningServiceFeignClient
 * @see org.springframework.cloud.openfeign.FallbackFactory
 */
public interface SigningServiceFallbackFactory extends FallbackFactory<SigningServiceFeignClient> {
}