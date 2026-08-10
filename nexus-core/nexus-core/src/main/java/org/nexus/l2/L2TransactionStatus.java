package org.nexus.l2;

/**
 * L2 交易状态枚举。
 *
 * @since 1.2
 */
public enum L2TransactionStatus {
    /** 待打包到批次 */
    PENDING,
    /** 已包含在已提交批次中 */
    INCLUDED,
    /** 批次已在 L1 确认 */
    CONFIRMED,
    /** 被挑战回滚 */
    REVERTED
}