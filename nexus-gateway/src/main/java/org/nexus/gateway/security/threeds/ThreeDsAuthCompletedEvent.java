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
 * <p>注意别与本包同名类混淆：{@code org.nexus.gateway.security.audit.event.ThreeDsAuthCompletedEvent}
 * 是另一个 POJO，只被 {@code SecurityEventPublisher} 引用，而后者<b>无任何调用方</b>——
 * 本类才是生产路径上真正被发布的那个。</p>
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