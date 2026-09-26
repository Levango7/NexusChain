package org.nexus.gateway.security.audit.event;

import java.time.Instant;

/**
 * 3DS 认证完成事件。当 3DS 认证流程结束时发布，包含认证结果信息。
 *
 * <p>设计依据：Wave 12 设计文档 §2.2.5 — SecurityEventPublisher。</p>
 */
public class ThreeDsAuthCompletedEvent {

    private final Long merchantId;
    private final Long paymentOrderId;
    private final String transStatus;
    private final String eci;
    private final String flowType;
    private final Instant completedAt;

    public ThreeDsAuthCompletedEvent(Long merchantId, Long paymentOrderId, String transStatus,
                                      String eci, String flowType) {
        this.merchantId = merchantId;
        this.paymentOrderId = paymentOrderId;
        this.transStatus = transStatus;
        this.eci = eci;
        this.flowType = flowType;
        this.completedAt = Instant.now();
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public Long getPaymentOrderId() {
        return paymentOrderId;
    }

    public String getTransStatus() {
        return transStatus;
    }

    public String getEci() {
        return eci;
    }

    public String getFlowType() {
        return flowType;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}