package org.nexus.gateway.fallback;

import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.nexus.sdk.client.feign.fallback.WalletMgmtFallbackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * gateway 侧 WalletMgmtFeignClient 的 FallbackFactory 实现。
 *
 * <p>对应设计文档 §4.4.4（Phase 3 fallback 绑定方案 G，与 §4.4.2 同构）。
 * 复用现有 {@link WalletMgmtFallback} 的降级逻辑，包装为 FallbackFactory
 * 以获取触发降级的异常（cause），区分限流/熔断/服务不可用。</p>
 *
 * <p>降级语义（gateway 侧，fail-closed 原则）：
 * <ul>
 *   <li>地址工具（{@code addressToPubkeyHash}）：返回 {@code null}，调用方拒绝请求</li>
 *   <li>地址校验（{@code verifyAddress}）：fail-closed 返回 {@code false}，拒绝请求</li>
 *   <li>提现审批（{@code requestWithdrawal} 等）：返回 {@code null}，调用方标记失败并告警</li>
 *   <li>托管查询（{@code getCustodyTier}）：返回 {@code null}，按未知层级处理</li>
 *   <li>冷钱包提取（{@code withdrawFromCold}）：返回 {@code null}，fail-closed 拒绝提取</li>
 *   <li>白名单查询（{@code isAddressWhitelisted}）：fail-closed 返回 {@code false}，拒绝提现</li>
 * </ul></p>
 *
 * <p>本类通过 {@code @Component} 注册为 Spring Bean，Spring Cloud OpenFeign 在
 * Feign 调用失败时自动调用 {@link #create(Throwable)} 获取降级实例。
 * SCA Sentinel-Feign 集成后，Sentinel 熔断/限流同样路由到本类。</p>
 *
 * @see WalletMgmtFallback
 * @see WalletMgmtFallbackFactory
 */
@Component
public class GatewayWalletMgmtFallbackFactory implements WalletMgmtFallbackFactory {

    private static final Logger log = LoggerFactory.getLogger(GatewayWalletMgmtFallbackFactory.class);

    @Override
    public WalletMgmtFeignClient create(Throwable cause) {
        log.error("WalletMgmtFeignClient 降级触发, cause={}: {}",
                cause.getClass().getSimpleName(), cause.getMessage(), cause);
        return new WalletMgmtFallback();  // 复用现有 fallback 类
    }
}