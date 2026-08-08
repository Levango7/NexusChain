package org.nexus.gateway.fallback;

import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 签名服务 Feign 调用降级处理（gateway → nexus-signing-service）。
 *
 * <p>对应设计文档 §4.3.2 降级 fallback 类设计 + §4.4.1 Feign 接口清单。
 * 当 Feign 调用 nexus-signing-service 失败（服务不可用 / 超时 / 熔断 / 限流）
 * 时，Spring Cloud OpenFeign 自动路由到本类对应方法，返回安全默认值。</p>
 *
 * <p>降级策略：
 * <ul>
 *   <li>{@code signTransfer} / {@code transfer}：返回 {@code null}，调用方按
 *       签名失败处理（支付流程标记 FAILED，触发对账/告警）</li>
 *   <li>{@code canSignViaMpc}：fail-closed 返回 {@code false}，退化为
 *       平台密钥库签名，不尝试 MPC 流程</li>
 *   <li>{@code getNoncePool}：返回 {@code null}，调用方回退到链节点 RPC
 *       直接查询当前 nonce</li>
 * </ul></p>
 *
 * <p>设计原则 D10：返回 null/false，不抛异常，调用方无需额外 try-catch。</p>
 *
 * <p>本类保留 {@code @Component} 注解作为 Spring Bean，但 Phase 3 fallback 绑定后
 * 通过 {@code GatewaySigningServiceFallbackFactory.create(Throwable)} 实例化，
 * 不再由 Spring 容器直接注入到 Feign 调用链。SCA Sentinel-Feign 集成后，
 * Sentinel 熔断/限流同样路由到本类（经 FallbackFactory 包装）。</p>
 */
@Component
public class SigningServiceFallback implements SigningServiceFeignClient {

    private static final Logger log = LoggerFactory.getLogger(SigningServiceFallback.class);

    @Override
    public String signTransfer(String fromPubkey, String toPubkeyHash, BigDecimal amount) {
        log.error("signTransfer Feign 降级触发: signing-service 不可用, from={}, to={}, amount={}",
                fromPubkey, toPubkeyHash, amount);
        // 已有 ERROR 级别日志告警；Prometheus counter + 外部告警通道接入为后续任务
        return null;
    }

    @Override
    public String transfer(String fromPubkey, String toPubkeyHash, BigDecimal amount, String privateKey) {
        log.error("transfer Feign 降级触发: signing-service 不可用, from={}, to={}, amount={}",
                fromPubkey, toPubkeyHash, amount);
        // 已有 ERROR 级别日志告警；Prometheus counter + 外部告警通道接入为后续任务
        return null;
    }

    @Override
    public boolean canSignViaMpc(BigDecimal amount) {
        log.warn("canSignViaMpc Feign 降级触发: signing-service 不可用, amount={}, fail-closed 返回 false",
                amount);
        return false;
    }

    @Override
    public Object getNoncePool(String address) {
        log.warn("getNoncePool Feign 降级触发: signing-service 不可用, address={}, 调用方应回退到 RPC 查询",
                address);
        return null;
    }
}