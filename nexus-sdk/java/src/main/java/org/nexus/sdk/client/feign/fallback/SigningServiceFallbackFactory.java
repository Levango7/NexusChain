package org.nexus.sdk.client.feign.fallback;

import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.math.BigDecimal;

/**
 * SigningServiceFeignClient 的 FallbackFactory 基类。
 *
 * <p>本类在 nexus-sdk 定义（Phase 3 fallback 绑定方案 G，设计文档 §4.4.1），
 * 消费方模块继承本类并按服务定制降级语义：
 * <ul>
 *   <li>gateway：{@code org.nexus.gateway.fallback.GatewaySigningServiceFallbackFactory}
 *       （退款/支付降级，复用 SigningServiceFallback，返回 null 标记 FAILED）</li>
 *   <li>wallet-service：{@code org.nexus.walletsvc.fallback.WalletSvcSigningServiceFallbackFactory}
 *       （提现降级，提现签名失败标记 PENDING_RETRY/FAILED）</li>
 * </ul></p>
 *
 * <p>设计原则：
 * <ul>
 *   <li>本类为具体类（非接口），提供默认 fail-closed 降级实现。Spring Cloud OpenFeign
 *       在 {@code @FeignClient(fallbackFactory = ...)} 验证阶段会实例化本类并调用
 *       {@code create(Throwable)} 校验返回类型，因此必须是可实例化的具体类</li>
 *   <li>消费方通过 {@code @Component} 注册子类 Bean，Spring 容器按类型注入子类，
 *       运行时使用消费方定制的降级语义；本类默认实现仅在无子类 Bean 时兜底</li>
 *   <li>{@code FallbackFactory<T>} 比 {@code fallback = T.class} 更灵活：可在
 *       {@code create(Throwable cause)} 中获取触发降级的异常，区分限流/熔断/服务不可用</li>
 * </ul></p>
 *
 * <p>端点对齐修复（任务 #317）：移除 {@code transfer} / {@code canSignViaMpc} /
 * {@code getNoncePool} 的默认 fallback 实现，与 SigningServiceFeignClient 接口对齐。</p>
 *
 * @see SigningServiceFeignClient
 * @see org.springframework.cloud.openfeign.FallbackFactory
 */
public class SigningServiceFallbackFactory implements FallbackFactory<SigningServiceFeignClient> {

    /**
     * 默认 fail-closed 降级实现：所有方法返回 null/false，调用方按失败处理。
     *
     * <p>消费方应覆盖本方法以注入定制降级逻辑（如记录日志、区分异常类型）。</p>
     *
     * @param cause 触发降级的异常（限流/熔断/服务不可用）
     * @return fail-closed 的 SigningServiceFeignClient 代理
     */
    @Override
    public SigningServiceFeignClient create(Throwable cause) {
        return new SigningServiceFeignClient() {
            @Override
            public String signTransfer(String fromPubkey, String toPubkeyHash, BigDecimal amount) {
                return null;
            }
        };
    }
}
