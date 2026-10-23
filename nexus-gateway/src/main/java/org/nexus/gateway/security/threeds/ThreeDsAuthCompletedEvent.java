package org.nexus.gateway.security.threeds;

import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * 3DS 认证完成事件 — 认证流程结束时发布。
 *
 * <p>设计意图（Wave 12 §2.2.5）是"接收方（如风控服务）根据 {@code transStatus} 与
 * {@code authStatus} 调整订单风险等级"，但仓内<b>没有</b>这样的接收方：目前唯一监听者是
 * {@link ThreeDsAuthCompletedObserver}，它只计数、记延迟与告警，不改动任何订单/资金状态。
 * 风控联动需要先有裁定（谁有权改风险等级、改完资金路径上发生什么），见 Issue #34。</p>
 *
 * <p>历史上还存在过一个同名 POJO（{@code security.audit.event.ThreeDsAuthCompletedEvent}）
 * 与一个零调用方的 {@code SecurityEventPublisher}，两者从未被任何生产代码引用，现已删除。
 * 本类是生产路径上唯一被发布与被消费的那个。</p>
 *
 * <p>{@code startedAt} / {@code completedAt} 直接取自 {@link ThreeDsAuthRecord} 已持久化的两列
 * （不新增列），是为了让监听方能算出 {@code nexus_3ds_auth_latency_seconds}。
 * {@code completedAt} <b>可以是 null</b>：{@code ThreeDsService.updateAuthRecord} 只在
 * {@code result.getCompletedAt() != null} 时才写这一列，所以"流程结束但完成时刻缺失"是真实存在
 * 的形态——监听方必须跳过采样，绝不能拿 0 当延迟（见该类的说明）。</p>
 */
public class ThreeDsAuthCompletedEvent extends ApplicationEvent {

    private final Long paymentOrderId;
    private final Long merchantId;
    private final String tenantId;
    private final TransStatus transStatus;
    private final AuthStatus authStatus;
    private final Integer riskScore;
    private final boolean frictionlessFlow;
    private final boolean challengeFlow;
    private final LocalDateTime startedAt;
    private final LocalDateTime completedAt;

    public ThreeDsAuthCompletedEvent(Object source, Long paymentOrderId, Long merchantId,
                                     String tenantId, TransStatus transStatus,
                                     AuthStatus authStatus, Integer riskScore,
                                     boolean frictionlessFlow, boolean challengeFlow,
                                     LocalDateTime startedAt, LocalDateTime completedAt) {
        super(source);
        this.paymentOrderId = paymentOrderId;
        this.merchantId = merchantId;
        this.tenantId = tenantId;
        this.transStatus = transStatus;
        this.authStatus = authStatus;
        this.riskScore = riskScore;
        this.frictionlessFlow = frictionlessFlow;
        this.challengeFlow = challengeFlow;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public Long getPaymentOrderId() { return paymentOrderId; }
    public Long getMerchantId() { return merchantId; }
    public String getTenantId() { return tenantId; }
    public TransStatus getTransStatus() { return transStatus; }
    public AuthStatus getAuthStatus() { return authStatus; }
    public Integer getRiskScore() { return riskScore; }
    public boolean isFrictionlessFlow() { return frictionlessFlow; }
    public boolean isChallengeFlow() { return challengeFlow; }

    /** 认证发起时刻（{@code three_ds_auth_record.initiated_at}），缺失时为 null。 */
    public LocalDateTime getStartedAt() { return startedAt; }

    /** 认证完成时刻（{@code three_ds_auth_record.completed_at}），未落时刻时为 null。 */
    public LocalDateTime getCompletedAt() { return completedAt; }
}