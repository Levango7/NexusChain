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
 *   <li>地址工具（{@code addressToPubkeyHash}）：返回 {@code null}，调用方
 *       拒绝该地址相关请求（fail-closed）</li>
 *   <li>地址校验（{@code verifyAddress}）：fail-closed 返回 {@code false}，
 *       即地址不合法，拒绝请求</li>
 *   <li>提现审批（{@code requestWithdrawal} 等）：返回 {@code null}，调用方
 *       标记提现失败并告警</li>
 *   <li>托管查询（{@code getCustodyTier}）：返回 {@code null}，调用方按
 *       未知层级处理（保守策略）</li>
 *   <li>冷钱包提取（{@code withdrawFromCold}）：返回 {@code null}，fail-closed，
 *       拒绝提取请求</li>
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
 */
@Component
public class WalletMgmtFallback implements WalletMgmtFeignClient {

    private static final Logger log = LoggerFactory.getLogger(WalletMgmtFallback.class);

    // === 地址工具 ===

    @Override
    public String addressToPubkeyHash(String address) {
        log.error("addressToPubkeyHash Feign 降级触发: wallet-service 不可用, address={}", address);
        return null;
    }

    @Override
    public boolean verifyAddress(String address) {
        log.warn("verifyAddress Feign 降级触发: wallet-service 不可用, address={}, fail-closed 返回 false",
                address);
        return false;
    }

    // === 提现审批 ===

    @Override
    public WithdrawalRequest requestWithdrawal(String to, BigDecimal amount, String currency) {
        log.error("requestWithdrawal Feign 降级触发: wallet-service 不可用, to={}, amount={}, currency={}",
                to, amount, currency);
        // 已有 ERROR 级别日志告警；Prometheus counter + 外部告警通道接入为后续任务
        return null;
    }

    @Override
    public WithdrawalRequest approveWithdrawal(String requestId, String approverId) {
        log.error("approveWithdrawal Feign 降级触发: wallet-service 不可用, requestId={}, approverId={}",
                requestId, approverId);
        return null;
    }

    @Override
    public WithdrawalRequest rejectWithdrawal(String requestId, String approverId, String reason) {
        log.error("rejectWithdrawal Feign 降级触发: wallet-service 不可用, requestId={}, approverId={}",
                requestId, approverId);
        return null;
    }

    @Override
    public WithdrawalRequest executeWithdrawal(String requestId) {
        log.error("executeWithdrawal Feign 降级触发: wallet-service 不可用, requestId={}", requestId);
        // 已有 ERROR 级别日志告警；Prometheus counter + 外部告警通道接入为后续任务
        return null;
    }

    @Override
    public WithdrawalRequest getWithdrawal(String requestId) {
        log.warn("getWithdrawal Feign 降级触发: wallet-service 不可用, requestId={}", requestId);
        return null;
    }

    @Override
    public WithdrawalRequest compensateWithdrawal(String requestId) {
        log.error("compensateWithdrawal Feign 降级触发: wallet-service 不可用, requestId={}, " +
                "补偿失败（等待下次对账重试）", requestId);
        // 已有 ERROR 级别日志告警；Prometheus counter + 外部告警通道接入为后续任务
        return null;
    }

    // === 托管 ===

    @Override
    public String getCustodyTier(String walletId) {
        log.warn("getCustodyTier Feign 降级触发: wallet-service 不可用, walletId={}", walletId);
        return null;
    }

    @Override
    public String depositToCold(String address, BigDecimal amount) {
        log.error("depositToCold Feign 降级触发: wallet-service 不可用, address={}, amount={}",
                address, amount);
        return null;
    }

    @Override
    public String withdrawFromCold(String address, BigDecimal amount, String approvalId) {
        log.error("withdrawFromCold Feign 降级触发: wallet-service 不可用, address={}, amount={}, " +
                "approvalId={}, fail-closed 拒绝提取", address, amount, approvalId);
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