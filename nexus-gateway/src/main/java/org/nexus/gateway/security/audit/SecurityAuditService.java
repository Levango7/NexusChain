package org.nexus.gateway.security.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 安全审计日志服务。使用专用 Logger "SECURITY_AUDIT" 记录安全操作审计日志，
 * 确保日志中不包含明文敏感数据（明文密码、哈希值、卡号、CVV、DEK/KEK 明文等）。
 *
 * <p>审计日志格式遵循 Wave 12 设计文档 §9.3.2 的 JSON 结构：
 * <pre>
 * {
 *   "timestamp": "2026-09-26T10:00:00.000Z",
 *   "merchantId": 123,
 *   "operationType": "ENCRYPT_FIELD",
 *   "securityEventType": "ENCRYPTION_OPERATION",
 *   "result": "SUCCESS",
 *   "paymentOrderId": 456,
 *   "details": "脱敏后的详情"
 * }
 * </pre></p>
 *
 * <p>设计依据：Wave 12 设计文档 §9.3.1 — SecurityAuditService。</p>
 */
@Service
public class SecurityAuditService {

    private static final Logger auditLog = LoggerFactory.getLogger("SECURITY_AUDIT");

    /**
     * 记录安全审计事件。将事件以结构化 JSON 格式写入专用审计日志。
     *
     * @param event 审计事件，safeDetails 字段必须为脱敏后的信息
     */
    public void record(AuditEvent event) {
        if (event.getTimestamp() == null) {
            event.setTimestamp(java.time.Instant.now());
        }

        auditLog.info(
                "{\"timestamp\":\"{}\",\"merchantId\":{},\"operationType\":\"{}\","
                        + "\"securityEventType\":\"{}\",\"result\":\"{}\","
                        + "\"paymentOrderId\":{},\"details\":\"{}\"}",
                event.getTimestamp(),
                event.getMerchantId() != null ? event.getMerchantId() : "null",
                event.getOperationType() != null ? event.getOperationType() : "null",
                event.getSecurityEventType() != null ? event.getSecurityEventType() : "null",
                event.getResult() != null ? event.getResult() : "null",
                event.getPaymentOrderId() != null ? event.getPaymentOrderId() : "null",
                event.getSafeDetails() != null ? event.getSafeDetails() : "null"
        );
    }

    /**
     * 便捷方法：记录加密操作审计事件。
     */
    public void recordEncryptionOperation(Long merchantId, String operationType, String result,
                                          Long paymentOrderId, String safeDetails) {
        AuditEvent event = new AuditEvent(
                merchantId, operationType, AuditEventType.ENCRYPTION_OPERATION,
                result, paymentOrderId, safeDetails
        );
        record(event);
    }

    /**
     * 便捷方法：记录密钥轮换审计事件。
     */
    public void recordKeyRotation(Long merchantId, String result, String safeDetails) {
        AuditEvent event = new AuditEvent(
                merchantId, "KEY_ROTATION", AuditEventType.KEY_ROTATION,
                result, null, safeDetails
        );
        record(event);
    }

    /**
     * 便捷方法：记录防重放拦截审计事件。
     */
    public void recordReplayInterception(Long merchantId, String result, String safeDetails) {
        AuditEvent event = new AuditEvent(
                merchantId, "REPLAY_INTERCEPTED", AuditEventType.REPLAY_INTERCEPTION,
                result, null, safeDetails
        );
        record(event);
    }

    /**
     * 便捷方法：记录 3DS 认证审计事件。
     */
    public void recordThreeDsAuth(Long merchantId, String result, Long paymentOrderId, String safeDetails) {
        AuditEvent event = new AuditEvent(
                merchantId, "3DS_AUTH", AuditEventType.THREE_DS_AUTH,
                result, paymentOrderId, safeDetails
        );
        record(event);
    }

    /**
     * 便捷方法：记录密码验证审计事件。
     */
    public void recordPasswordValidation(Long merchantId, String result, String safeDetails) {
        AuditEvent event = new AuditEvent(
                merchantId, "PASSWORD_VERIFY", AuditEventType.PASSWORD_VALIDATION,
                result, null, safeDetails
        );
        record(event);
    }

    /**
     * 便捷方法：记录密码锁定审计事件。
     */
    public void recordPasswordLocked(Long merchantId, String safeDetails) {
        AuditEvent event = new AuditEvent(
                merchantId, "PASSWORD_LOCKED", AuditEventType.PASSWORD_LOCKED,
                "BLOCKED", null, safeDetails
        );
        record(event);
    }

    /**
     * 便捷方法：记录二次验证审计事件。
     */
    public void recordSecondFactor(Long merchantId, String operationType, String result,
                                   Long paymentOrderId, String safeDetails) {
        AuditEvent event = new AuditEvent(
                merchantId, operationType, AuditEventType.SECOND_FACTOR,
                result, paymentOrderId, safeDetails
        );
        record(event);
    }
}