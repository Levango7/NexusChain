package org.nexus.gateway.security.threeds;

import org.springframework.context.ApplicationEvent;

/**
 * 3DS 认证完成事件 — 认证流程结束时发布。
 *
 * <p>设计意图（Wave 12 §2.2.5）是"接收方（如风控服务）根据 {@code transStatus} 与
 * {@code authStatus} 调整订单风险等级"，但仓内<b>没有</b>这样的接收方：目前唯一监听者是
 * {@link ThreeDsAuthCompletedObserver}，它只计数与告警，不改动任何订单/资金状态。
 * 风控联动需要先有裁定（谁有权改风险等级、改完资金路径上发生什么），见 Issue #34。</p>
 *
 * <p>历史上还存在过一个同名 POJO（{@code security.audit.event.ThreeDsAuthCompletedEvent}）
 * 与一个零调用方的 {@code SecurityEventPublisher}，两者从未被任何生产代码引用，现已删除。
 * 本类是生产路径上唯一被发布与被消费的那个。</p>
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

    public ThreeDsAuthCompletedEvent(Object source, Long paymentOrderId, Long merchantId,
                                     String tenantId, TransStatus transStatus,
                                     AuthStatus authStatus, Integer riskScore,
                                     boolean frictionlessFlow, boolean challengeFlow) {
        super(source);
        this.paymentOrderId = paymentOrderId;
        this.merchantId = merchantId;
        this.tenantId = tenantId;
        this.transStatus = transStatus;
        this.authStatus = authStatus;
        this.riskScore = riskScore;
        this.frictionlessFlow = frictionlessFlow;
        this.challengeFlow = challengeFlow;
    }

    public Long getPaymentOrderId() { return paymentOrderId; }
    public Long getMerchantId() { return merchantId; }
    public String getTenantId() { return tenantId; }
    public TransStatus getTransStatus() { return transStatus; }
    public AuthStatus getAuthStatus() { return authStatus; }
    public Integer getRiskScore() { return riskScore; }
    public boolean isFrictionlessFlow() { return frictionlessFlow; }
    public boolean isChallengeFlow() { return challengeFlow; }
}