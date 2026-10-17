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
 *
 * <p><b>两处输出（#52 A 段）：</b>①结构化 JSON 日志（专用 logger {@code SECURITY_AUDIT}，
 * 该 logger 没有自己的 appender，会落到 root——不丢，但不可查）；②交给 {@link SecurityAuditSink}
 * 落 {@code audit_events} 表，实现方在 {@code audit} 切片（依赖倒置的成因见该接口注释），
 * 走 {@code AuditLogService} 已有的失败旁路（落库异常只记 {@code AUDIT_PERSIST_FAILED}，
 * 不把业务带倒）。</p>
 *
 * <p>为什么没有新表/新列：{@code audit_events} 已带 {@code idx_audit_action} 与
 * {@code idx_audit_merchant}/{@code idx_audit_ts}，把类别编进 {@code action} 前缀
 * （{@code SECURITY_THREE_DS_AUTH} 这类）就能按类别与商户检索。代价是没有独立的 severity 维度
 * （{@code AuditEventType} 里也没有该字段，不在此处发明），需要分级检索时另开一笔加列。</p>
 */
@Service
public class SecurityAuditService {

    private static final Logger auditLog = LoggerFactory.getLogger("SECURITY_AUDIT");

    private final SecurityAuditSink sink;

    /** 无落库接缝的构造：只保留日志输出（留给不要求落库的单测与本地装配）。 */
    public SecurityAuditService() {
        this.sink = null;
    }

    /** 主构造器：多构造器下必须显式 @Autowired，否则 Spring 选无参那个 ⇒ sink 永不注入、
     *  审计静默退回"只写日志"（同 {@code AuditLogService:44} 记过的坑）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public SecurityAuditService(SecurityAuditSink sink) {
        this.sink = sink;
    }

    /**
     * 记录安全审计事件：结构化日志 + 落 {@code audit_events}。
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

        if (sink != null) {
            sink.persist(event);
        }
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