package org.nexus.gateway.security.threeds;

import org.springframework.context.ApplicationEvent;

/**
 * 3DS 认证完成事件 — 认证流程结束时发布，联动风控系统。
 *
 * <p>事件接收方（如风控服务）可根据 {@code transStatus} 和 {@code authStatus}
 * 调整订单的风险等级。</p>
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