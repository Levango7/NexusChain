package org.nexus.sdk.client.feign.fallback;

import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.nexus.sdk.wallet.WithdrawalRequest;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.math.BigDecimal;

/**
 * WalletMgmtFeignClient 的 FallbackFactory 基类。
 *
 * <p>本类在 nexus-sdk 定义（Phase 3 fallback 绑定方案 G，设计文档 §4.4.1 / §4.4.4），
 * 消费方模块继承本类并按服务定制降级语义：
 * <ul>
 *   <li>gateway：{@code org.nexus.gateway.fallback.GatewayWalletMgmtFallbackFactory}
 *       （钱包管理降级，复用 WalletMgmtFallback，fail-closed 返回 null/false）</li>
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
 * <p>端点对齐修复（任务 #317）：移除 {@code addressToPubkeyHash} / {@code verifyAddress} /
 * {@code getWithdrawal} / {@code compensateWithdrawal} / {@code getCustodyTier} /
 * {@code depositToCold} / {@code withdrawFromCold} 的默认 fallback 实现；
 * 修正 {@code approveWithdrawal} / {@code rejectWithdrawal} / {@code executeWithdrawal}
 * 方法签名，与 WalletMgmtFeignClient 接口对齐。</p>
 *
 * @see WalletMgmtFeignClient
 * @see org.springframework.cloud.openfeign.FallbackFactory
 */
public class WalletMgmtFallbackFactory implements FallbackFactory<WalletMgmtFeignClient> {

    /**
     * 默认 fail-closed 降级实现：查询返回 null/false，调用方按失败处理。
     *
     * <p>消费方应覆盖本方法以注入定制降级逻辑（如记录日志、区分异常类型）。</p>
     *
     * @param cause 触发降级的异常（限流/熔断/服务不可用）
     * @return fail-closed 的 WalletMgmtFeignClient 代理
     */
    @Override
    public WalletMgmtFeignClient create(Throwable cause) {
        return new WalletMgmtFeignClient() {
            @Override
            public WithdrawalRequest requestWithdrawal(String to, BigDecimal amount, String currency) {
                return null;
            }

            @Override
            public WithdrawalRequest approveWithdrawal(String approvalId) {
                return null;
            }

            @Override
            public WithdrawalRequest rejectWithdrawal(String approvalId, String reason) {
                return null;
            }

            @Override
            public WithdrawalRequest executeWithdrawal(String approvalId) {
                return null;
            }

            @Override
            public boolean isAddressWhitelisted(String address) {
                return false;
            }

            @Override
            public Object addWhitelist(String address, String label, String merchantId) {
                return null;
            }
        };
    }
}
