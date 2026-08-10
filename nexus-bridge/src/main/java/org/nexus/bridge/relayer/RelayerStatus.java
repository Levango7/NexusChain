package org.nexus.bridge.relayer;

/**
 * Relayer 状态枚举。
 *
 * @since 1.2
 */
public enum RelayerStatus {
    /** 活跃，可接单 */
    ACTIVE,
    /** 暂停，不接受新请求 */
    INACTIVE,
    /** 已被惩罚 */
    SLASHED,
    /** 已注销 */
    DEREGISTERED
}