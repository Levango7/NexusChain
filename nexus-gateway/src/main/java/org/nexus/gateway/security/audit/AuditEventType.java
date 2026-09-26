package org.nexus.gateway.security.audit;

/**
 * 安全审计事件类型枚举。定义所有安全相关操作的审计事件分类。
 *
 * <p>设计依据：Wave 12 设计文档 §9.3.3 — 审计事件类型。</p>
 */
public enum AuditEventType {

    /** 字段加密/解密操作 */
    ENCRYPTION_OPERATION,

    /** KEK 密钥轮换 */
    KEY_ROTATION,

    /** 防重放拦截 */
    REPLAY_INTERCEPTION,

    /** 3DS 认证 */
    THREE_DS_AUTH,

    /** 支付密码验证 */
    PASSWORD_VALIDATION,

    /** 支付密码锁定 */
    PASSWORD_LOCKED,

    /** 二次验证（OTP/TOTP/EMAIL） */
    SECOND_FACTOR
}