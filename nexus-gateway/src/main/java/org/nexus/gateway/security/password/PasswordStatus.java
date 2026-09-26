package org.nexus.gateway.security.password;

/**
 * 支付密码状态枚举。
 *
 * <p>ACTIVE: 正常可用；LOCKED: 因连续失败被锁定；EXPIRED: 密码已过期需更换。</p>
 */
public enum PasswordStatus {
    ACTIVE,
    LOCKED,
    EXPIRED
}