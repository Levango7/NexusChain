package org.nexus.gateway.security.audit;

import java.time.Instant;

/**
 * 安全审计事件 DTO。记录安全操作的完整上下文信息，供 {@link SecurityAuditService} 写入审计日志。
 *
 * <p>字段 safeDetails 仅包含脱敏后的信息，禁止包含明文密码、密码哈希值、
 * 明文卡号、明文 CVV、DEK 明文、KEK 明文等敏感数据。</p>
 *
 * <p>设计依据：Wave 12 设计文档 §9.3.1、§9.3.2 — 审计日志格式。</p>
 */
public class AuditEvent {

    private Instant timestamp;
    private Long merchantId;
    private String operationType;
    private AuditEventType securityEventType;
    private String result;
    private Long paymentOrderId;
    private String safeDetails;

    public AuditEvent() {
    }

    public AuditEvent(Long merchantId, String operationType, AuditEventType securityEventType,
                      String result, Long paymentOrderId, String safeDetails) {
        this.timestamp = Instant.now();
        this.merchantId = merchantId;
        this.operationType = operationType;
        this.securityEventType = securityEventType;
        this.result = result;
        this.paymentOrderId = paymentOrderId;
        this.safeDetails = safeDetails;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public AuditEventType getSecurityEventType() {
        return securityEventType;
    }

    public void setSecurityEventType(AuditEventType securityEventType) {
        this.securityEventType = securityEventType;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Long getPaymentOrderId() {
        return paymentOrderId;
    }

    public void setPaymentOrderId(Long paymentOrderId) {
        this.paymentOrderId = paymentOrderId;
    }

    public String getSafeDetails() {
        return safeDetails;
    }

    public void setSafeDetails(String safeDetails) {
        this.safeDetails = safeDetails;
    }
}