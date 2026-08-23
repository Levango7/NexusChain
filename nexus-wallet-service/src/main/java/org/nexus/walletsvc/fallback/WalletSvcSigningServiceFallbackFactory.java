package org.nexus.walletsvc.fallback;

import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.fallback.SigningServiceFallbackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * wallet-service 侧 SigningServiceFeignClient 的 FallbackFactory 实现。
 *
 * <p>对应设计文档 §4.4.3（Phase 3 fallback 绑定方案 G）。降级语义与 gateway 不同：
 * gateway 侧退款/支付降级返回 null 标记 FAILED；wallet-service 侧提现签名降级
 * 返回 null 标记提现执行失败，由 {@code DefaultWithdrawalApprovalService} 收到 null
 * 后将提现请求标记为 FAILED 并触发人工介入/重试流程。</p>
 *
 * <p>降级语义（wallet-service 侧）：
 * <ul>
 *   <li>{@code signTransfer}：返回 {@code null}，{@code DefaultWithdrawalApprovalService}
 *       收到 null 标记提现 FAILED（提现执行失败需人工介入）</li>
 * </ul></p>
 *
 * <p>设计原则 D10：返回 null/false，不抛异常，调用方无需额外 try-catch。
 * 涉及资金安全的提现操作降级时必须明确失败，避免静默放行导致资产丢失。</p>
 *
 * <p>本类通过 {@code @Component} 注册为 Spring Bean。注意：本类与
 * {@code org.nexus.gateway.fallback.GatewaySigningServiceFallbackFactory} 同实现
 * {@link SigningServiceFallbackFactory}，但二者位于不同 Spring 容器（wallet-service
 * 与 gateway 独立部署），运行时各自只扫描本模块的 Bean，不会冲突。</p>
 *
 * <p>端点对齐修复（任务 #317）：移除 {@code transfer} / {@code canSignViaMpc} /
 * {@code getNoncePool} 的 fallback 实现，与 SigningServiceFeignClient 接口对齐。</p>
 *
 * @see SigningServiceFallbackFactory
 */
@Component
public class WalletSvcSigningServiceFallbackFactory extends SigningServiceFallbackFactory {

    private static final Logger log = LoggerFactory.getLogger(WalletSvcSigningServiceFallbackFactory.class);

    @Override
    public SigningServiceFeignClient create(Throwable cause) {
        log.error("wallet-service → signing-service 降级触发, cause={}: {}",
                cause.getClass().getSimpleName(), cause.getMessage(), cause);
        return new SigningServiceFeignClient() {
            @Override
            public String signTransfer(String fromPubkey, String toPubkeyHash, BigDecimal amount) {
                log.error("提现签名降级: signing-service 不可用, from={}, to={}, amount={}",
                        fromPubkey, toPubkeyHash, amount);
                // DefaultWithdrawalApprovalService 收到 null 标记提现 FAILED
                return null;
            }
        };
    }
}
