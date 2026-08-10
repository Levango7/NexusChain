package org.nexus.pool.feemarket;

/**
 * 交易紧急程度枚举。
 *
 * @since 1.2
 */
public enum TransactionUrgency {
    /** 低优先级，可延迟打包 */
    LOW,
    /** 普通优先级 */
    NORMAL,
    /** 高优先级，尽快打包 */
    HIGH,
    /** 紧急，下一区块必打包 */
    URGENT
}