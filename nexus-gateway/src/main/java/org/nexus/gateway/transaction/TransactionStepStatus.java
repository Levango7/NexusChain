package org.nexus.gateway.transaction;

/**
 * 分布式事务步骤状态枚举。
 *
 * <p>覆盖 TCC 和 SAGA 两种事务模式下所有可能的步骤状态：</p>
 * <ul>
 *   <li>{@link #TRY} — TCC Try 阶段（资源预留）</li>
 *   <li>{@link #CONFIRM} — TCC Confirm 阶段（确认提交）</li>
 *   <li>{@link #CANCEL} — TCC Cancel 阶段（取消预留）</li>
 *   <li>{@link #COMPENSATE} — SAGA 补偿阶段（逆向补偿已完成步骤）</li>
 *   <li>{@link #PENDING} — 事务已创建但尚未开始执行</li>
 *   <li>{@link #SUCCESS} — 步骤/事务执行成功（终态）</li>
 *   <li>{@link #FAILED} — 步骤/事务执行失败（终态）</li>
 * </ul>
 */
public enum TransactionStepStatus {
    /** TCC Try 阶段：资源预留 */
    TRY,
    /** TCC Confirm 阶段：确认提交 */
    CONFIRM,
    /** TCC Cancel 阶段：取消预留 */
    CANCEL,
    /** SAGA 补偿阶段：逆向补偿 */
    COMPENSATE,
    /** 事务已创建，尚未开始执行 */
    PENDING,
    /** 步骤/事务执行成功（终态） */
    SUCCESS,
    /** 步骤/事务执行失败（终态） */
    FAILED
}