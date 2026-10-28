package org.nexus.gateway.fallback;

import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 签名服务 Feign 调用降级处理（gateway → nexus-signing-service）。
 *
 * <p>对应设计文档 §4.3.2 降级 fallback 类设计 + §4.4.1 Feign 接口清单。
 * 当 Feign 调用 nexus-signing-service 失败（服务不可用 / 超时 / 熔断 / 限流）
 * 时，Spring Cloud OpenFeign 自动路由到本类对应方法，返回安全默认值。</p>
 *
 * <p>降级策略：
 * <ul>
 *   <li>{@code signTransfer}：返回 {@code null}，调用方按
 *       签名失败处理（支付流程标记 FAILED，触发对账/告警）</li>
 * </ul></p>
 *
 * <p>设计原则 D10：返回 null/false，不抛异常，调用方无需额外 try-catch。</p>
 *
 * <p>本类保留 {@code @Component} 注解作为 Spring Bean，但 Phase 3 fallback 绑定后
 * 通过 {@code GatewaySigningServiceFallbackFactory.create(Throwable)} 实例化，
 * 不再由 Spring 容器直接注入到 Feign 调用链。SCA Sentinel-Feign 集成后，
 * Sentinel 熔断/限流同样路由到本类（经 FallbackFactory 包装）。</p>
 *
 * <p>端点对齐修复（任务 #317）：移除 {@code transfer} / {@code canSignViaMpc} /
 * {@code getNoncePool} 的 fallback 实现，与 SigningServiceFeignClient 接口对齐。</p>
 */
@Component
public class SigningServiceFallback implements SigningServiceFeignClient {

    private static final Logger log = LoggerFactory.getLogger(SigningServiceFallback.class);

    @Override
    public Map<String, Object> signTransfer(String fromPubkey, String toPubkeyHash, BigDecimal amount) {
        log.error("signTransfer Feign 降级触发: signing-service 不可用, from={}, to={}, amount={}",
                fromPubkey, toPubkeyHash, amount);
        // 已有 ERROR 级别日志告警；Prometheus counter + 外部告警通道接入为后续任务。
        // 契约修正（2026-09-10）：返回 Map 形态（降级为 null），调用方经
        // SigningResponses.txHash(null) 提取得 null——按签名失败处理，语义不变。
        return null;
    }

    @Override
    public Map<String, Object> signTransferWithBusinessApproval(String fromPubkey, String toPubkeyHash,
                                                                BigDecimal amount, String businessApprovalId) {
        // A2：gateway 五路径不携带业务凭据（ADR-036 §2.2），本方法仅为接口完备性而实现。
        // 若未来有调用方误用，降级返回 null 按失败处理。
        log.error("signTransferWithBusinessApproval Feign 降级触发: signing-service 不可用, businessApprovalId={}",
                businessApprovalId);
        return null;
    }

    @Override
    public Map<String, Object> registerBusinessApproval(String businessApprovalId, String payloadHash,
                                                        BigDecimal amount, String currency,
                                                        String fromPubkey, String toPubkeyHash,
                                                        String expiresAt, String approversCsv) {
        // A2：登记是 wallet 专属动作，gateway 不应调用；降级返回 null 按未登记处理。
        log.error("registerBusinessApproval Feign 降级触发: signing-service 不可用, businessApprovalId={}",
                businessApprovalId);
        return null;
    }

    @Override
    public Map<String, Object> getCapability() {
        // 审计修复：capability 探针降级返回 null，健康指示器按 DOWN 处理
        return null;
    }

    @Override
    public Map<String, Object> getApprovalStatus(String requestId) {
        // fail-closed：查询降级 = 状态未知，调用方不得据此执行
        log.error("getApprovalStatus Feign 降级触发: signing-service 不可用, requestId={}", requestId);
        return null;
    }

    @Override
    public Map<String, Object> executeApproved(String approvalId) {
        // fail-closed：执行降级 = 结果未知，调用方不得当作已执行
        log.error("executeApproved Feign 降级触发: signing-service 不可用, approvalId={}", approvalId);
        return null;
    }
}
