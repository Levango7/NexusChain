package org.nexus.gateway.transaction;

/**
 * 分布式事务类型枚举。
 *
 * <p>支持两种轻量级分布式事务模式：</p>
 * <ul>
 *   <li>{@link #TCC} — Try-Confirm-Cancel，适用于需要强一致性的短事务</li>
 *   <li>{@link #SAGA} — 编排式补偿事务，适用于长流程、多步骤的事务</li>
 * </ul>
 */
public enum TransactionType {
    /** Try-Confirm-Cancel 模式 */
    TCC,
    /** SAGA 编排式补偿模式 */
    SAGA
}