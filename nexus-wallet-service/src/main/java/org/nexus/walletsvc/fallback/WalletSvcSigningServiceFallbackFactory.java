package org.nexus.walletsvc.fallback;

import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.SigningResponses;
import org.nexus.sdk.client.feign.fallback.SigningServiceFallbackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

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
 * <p>业务失败信封透传（A2 §7.5 决策一，2026-10-29）：signing 以 HTTP 非 2xx 携 JSON
 * 信封返回业务失败，经熔断器转成降级时，{@code signTransfer} /
 * {@code signTransferWithBusinessApproval} 从 FeignException 响应体解析出已定义
 * statusCode 并把信封返回给调用方，使「明确未动账」码能被判 FAILED（与异常路径
 * 共用 {@link SigningResponses} 同一套解析，两路径收敛同一语义）；无响应体或
 * 未知码仍返回 null，A2 腿判 EXECUTED_UNVERIFIED（fail-closed 不双花）。</p>
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
            public Map<String, Object> signTransfer(String fromPubkey, String toPubkeyHash, BigDecimal amount) {
                Map<String, Object> failureEnvelope = translateFailureEnvelope(cause);
                if (failureEnvelope != null) {
                    // 透传 signing 的业务失败信封：classify 侧按已定义未动账码判 FAILED
                    return new HashMap<>(failureEnvelope);
                }
                log.error("提现签名降级: signing-service 不可用, from={}, to={}, amount={}",
                        fromPubkey, toPubkeyHash, amount);
                // DefaultWithdrawalApprovalService 经 SigningResponses.classify(null)
                // 提取失败，标记提现 FAILED
                return null;
            }

            @Override
            public Map<String, Object> signTransferWithBusinessApproval(
                    String fromPubkey, String toPubkeyHash, BigDecimal amount, String businessApprovalId) {
                Map<String, Object> failureEnvelope = translateFailureEnvelope(cause);
                if (failureEnvelope != null) {
                    // 透传信封：明确未动账码 ⇒ classify 侧判 FAILED；5003 ⇒ UNVERIFIED
                    return new HashMap<>(failureEnvelope);
                }
                // A2 凭据签名降级 = 结果未知（凭据可能已消费并广播）：
                // 返回 null 由 A2 腿判 EXECUTED_UNVERIFIED，绝不回落重试（双花）
                log.error("A2 凭据签名降级: signing-service 不可用, businessApprovalId={}, to={}, amount={}",
                        businessApprovalId, toPubkeyHash, amount);
                return null;
            }

            @Override
            public java.util.Map<String, Object> registerBusinessApproval(
                    String businessApprovalId, String payloadHash, BigDecimal amount,
                    String currency, String fromPubkey, String toPubkeyHash,
                    String expiresAt, String approversCsv) {
                // A2 登记降级 = 未登记；A2 腿据此回落既有审批流（安全，无资金动作）
                log.error("A2 凭据登记降级: signing-service 不可用, businessApprovalId={}",
                        businessApprovalId);
                return null;
            }

            @Override
            public java.util.Map<String, Object> getCapability() {
                // 审计修复：capability 探针降级返回 null，健康指示器按 DOWN 处理
                return null;
            }

            @Override
            public java.util.Map<String, Object> getApprovalStatus(String requestId) {
                // fail-closed：查询降级 = 状态未知，调用方不得据此执行
                return null;
            }

            @Override
            public java.util.Map<String, Object> executeApproved(String approvalId) {
                // fail-closed：执行降级 = 结果未知，调用方不得当作已执行
                return null;
            }
        };
    }

    /**
     * 把 FeignException 响应体里的业务信封翻译成可透传的 Map
     * （A2 §7.5 决策一，P0-2 配套，2026-10-29）。
     *
     * <p>signing 以 HTTP 非 2xx 携带 JSON 信封返回业务失败；当 Feign 把该异常交给
     * fallback 时（本模块 {@code spring.cloud.openfeign.circuitbreaker.enabled=true}
     * 会把 FeignException 转成降级），原实现一律返回 null，使「明确未动账
     * （5000/5001/4000/4030）」与「凭据已消费（5003）」都塌缩成「结果不明」，
     * A2 腿的 FAILED 分支在降级路径上不可达。</p>
     *
     * <p>本方法只放行信封里带<b>已定义 statusCode</b> 的响应，与
     * {@code DefaultWithdrawalApprovalService} 异常路径共用
     * {@link SigningResponses} 的同一套解析与判据，保证两条路径收敛到同一语义；
     * 未定义码 / 无响应体 / 熔断打开 / 非 Feign 异常仍返回 null，由调用方按
     * fail-closed 处理（A2 腿判 EXECUTED_UNVERIFIED）。</p>
     *
     * @param cause 触发降级的异常
     * @return 可透传的业务失败信封；null 表示不可翻译
     */
    private static Map<String, Object> translateFailureEnvelope(Throwable cause) {
        if (!(cause instanceof feign.FeignException)) {
            return null;
        }
        byte[] body = ((feign.FeignException) cause).content();
        if (body == null) {
            return null;
        }
        Map<String, Object> envelope = SigningResponses.envelope(new String(body, StandardCharsets.UTF_8));
        if (envelope == null) {
            return null;
        }
        Integer statusCode = SigningResponses.statusCode(envelope);
        if (statusCode == null) {
            return null;
        }
        return (SigningResponses.declaresNotExecuted(statusCode)
                || statusCode == SigningResponses.STATUS_BUSINESS_APPROVAL_CONSUMED)
                ? envelope : null;
    }
}
