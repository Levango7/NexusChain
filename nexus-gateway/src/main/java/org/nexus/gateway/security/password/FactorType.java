package org.nexus.gateway.security.password;

/**
 * 二次验证类型枚举。
 *
 * <p>OTP: 一次性短信验证码；TOTP: 基于时间的一次性密码；EMAIL: 邮箱验证码。</p>
 */
public enum FactorType {
    OTP,
    TOTP,
    EMAIL
}