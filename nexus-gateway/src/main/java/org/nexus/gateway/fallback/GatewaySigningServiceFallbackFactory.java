package org.nexus.gateway.fallback;

import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.fallback.SigningServiceFallbackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * gateway 侧 SigningServiceFeignClient 的 FallbackFactory 实现。
 *
 * <p>对应设计文档 §4.4.2（Phase 3 fallback 绑定方案 G）。复用现有
 * {@link SigningServiceFallback} 的降级逻辑，包装为 FallbackFactory
 * 以获取触发降级的异常（cause），区分限流/熔断/服务不可用。</p>
 *
 * <p>降级语义（gateway 侧）：
 * <ul>
 *   <li>{@code signTransfer} / {@code transfer}：返回 {@code null}，调用方按
 *       签名失败处理（支付/退款流程标记 FAILED，触发对账/告警）</li>
 *   <li>{@code canSignViaMpc}：fail-closed 返回 {@code false}，退化为平台密钥库签名</li>
 *   <li>{@code getNoncePool}：返回 {@code null}，调用方回退到链节点 RPC 查询</li>
 * </ul></p>
 *
 * <p>本类通过 {@code @Component} 注册为 Spring Bean，Spring Cloud OpenFeign 在
 * Feign 调用失败时自动调用 {@link #create(Throwable)} 获取降级实例。
 * SCA Sentinel-Feign 集成后，Sentinel 熔断/限流同样路由到本类。</p>
 *
 * @see SigningServiceFallback
 * @see SigningServiceFallbackFactory
 */
@Component
public class GatewaySigningServiceFallbackFactory extends SigningServiceFallbackFactory {

    private static final Logger log = LoggerFactory.getLogger(GatewaySigningServiceFallbackFactory.class);

    @Override
    public SigningServiceFeignClient create(Throwable cause) {
        log.error("SigningServiceFeignClient 降级触发, cause={}: {}",
                cause.getClass().getSimpleName(), cause.getMessage(), cause);
        return new SigningServiceFallback();  // 复用现有 fallback 类
    }
}