package org.nexus.gateway.fallback;

import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.nexus.sdk.wallet.WithdrawalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 钱包管理服务 Feign 调用降级处理（gateway → nexus-wallet-service）。
 *
 * <p>对应设计文档 §4.3.2 降级 fallback 类设计 + §4.4.1 Feign 接口清单。
 * 当 Feign 调用 nexus-wallet-service 失败（服务不可用 / 超时 / 熔断 / 限流）
 * 时，Spring Cloud OpenFeign 自动路由到本类对应方法，返回安全默认值。</p>
 *
 * <p>降级策略：
 * <ul>
 *   <li>提现审批（{@code requestWithdrawal} 等）：返回 {@code null}，调用方
 *       标记提现失败并告警</li>
 *   <li>白名单查询（{@code isAddressWhitelisted}）：fail-closed 返回 {@code false}，
 *       即不在白名单，拒绝提现</li>
 * </ul></p>
 *
 * <p>设计原则 D10：返回 null/false，不抛异常，调用方无需额外 try-catch。
 * 涉及资金安全的查询一律 fail-closed（返回 false/null），避免服务降级时
 * 误放行敏感操作。</p>
 *
 * <p>本类保留 {@code @Component} 注解作为 Spring Bean，但 Phase 3 fallback 绑定后
 * 通过 {@code GatewayWalletMgmtFallbackFactory.create(Throwable)} 实例化，
 * 不再由 Spring 容器直接注入到 Feign 调用链。SCA Sentinel-Feign 集成后，
 * Sentinel 熔断/限流同样路由到本类（经 FallbackFactory 包装）。</p>
 *
 * <p>端点对齐修复（任务 #317）：移除 {@code addressToPubkeyHash} / {@code verifyAddress} /
 * {@code getWithdrawal} / {@code compensateWithdrawal} / {@code getCustodyTier} /
 * {@code depositToCold} / {@code withdrawFromCold} 的 fallback 实现；
 * 修正 {@code approveWithdrawal} / {@code rejectWithdrawal} / {@code executeWithdrawal}
 * 方法签名（移除 {@code approverId} 参数，{@code requestId} 改为 {@code approvalId}），
 * 与 WalletMgmtFeignClient 接口对齐。</p>
 */
@Component
public class WalletMgmtFallback implements WalletMgmtFeignClient {

    private static final Logger log = LoggerFactory.getLogger(WalletMgmtFallback.class);

    // === 提现审批 ===

    @Override
    public WithdrawalRequest requestWithdrawal(String to, BigDecimal amount, String currency) {
        log.error("requestWithdrawal Feign 降级触发: wallet-service 不可用, to={}, amount={}, currency={}",
                to, amount, currency);
        // 已有 ERROR 级别日志告警；Prometheus counter + 外部告警通道接入为后续任务
        return null;
    }

    @Override
    public WithdrawalRequest approveWithdrawal(String approvalId) {
        log.error("approveWithdrawal Feign 降级触发: wallet-service 不可用, approvalId={}", approvalId);
        return null;
    }

    @Override
    public WithdrawalRequest rejectWithdrawal(String approvalId, String reason) {
        log.error("rejectWithdrawal Feign 降级触发: wallet-service 不可用, approvalId={}, reason={}",
                approvalId, reason);
        return null;
    }

    @Override
    public WithdrawalRequest executeWithdrawal(String approvalId) {
        log.error("executeWithdrawal Feign 降级触发: wallet-service 不可用, approvalId={}", approvalId);
        // 已有 ERROR 级别日志告警；Prometheus counter + 外部告警通道接入为后续任务
        return null;
    }

    // === 白名单 ===

    @Override
    public boolean isAddressWhitelisted(String address) {
        log.warn("isAddressWhitelisted Feign 降级触发: wallet-service 不可用, address={}, " +
                "fail-closed 返回 false", address);
        return false;
    }

    @Override
    public Object addWhitelist(String address, String label, String merchantId) {
        log.error("addWhitelist Feign 降级触发: wallet-service 不可用, address={}, label={}, merchantId={}",
                address, label, merchantId);
        return null;
    }
}
