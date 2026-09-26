package org.nexus.gateway.security.threeds;

/**
 * 3DS 认证状态枚举。
 *
 * <p>对应 {@code three_ds_auth_records.auth_status} 字段，
 * 表示一次 3DS 认证流程的生命周期状态。</p>
 */
public enum AuthStatus {
    /** 认证已发起，等待结果（Frictionless 或 Challenge 进行中）。 */
    INITIATED,
    /** 认证已完成（成功通过 Frictionless 或 Challenge 验证）。 */
    COMPLETED,
    /** 认证失败（Challenge 验证失败、被拒绝等）。 */
    FAILED,
    /** 认证超时（Challenge 未在规定时间内完成）。 */
    TIMEOUT
}