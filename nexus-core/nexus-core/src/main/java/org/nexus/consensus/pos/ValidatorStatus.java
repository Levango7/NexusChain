package org.nexus.consensus.pos;

/**
 * 验证者状态枚举。
 *
 * @since 1.2
 */
public enum ValidatorStatus {
    /** 活跃中，可参与共识 */
    ACTIVE,
    /** 不活跃，不参与共识 */
    INACTIVE,
    /** 已被惩罚 */
    SLASHED
}