package org.nexus.gateway.voidreversal;

/**
 * 冲正请求状态枚举。
 *
 * <p>状态流转：</p>
 * <ul>
 *   <li>{@link #PENDING} → {@link #APPROVED} → {@link #COMPLETED}：审批通过并完成冲正</li>
 *   <li>{@link #PENDING} → {@link #REJECTED}：审批拒绝</li>
 *   <li>{@link #PENDING} → {@link #FAILED}：冲正执行失败</li>
 *   <li>{@link #COMPLETED}：终态，冲正已完成</li>
 *   <li>{@link #REJECTED}：终态，冲正被拒绝</li>
 *   <li>{@link #FAILED}：终态，冲正失败</li>
 * </ul>
 */
public enum ReversalStatus {
    /** 待审批 — 冲正请求已创建，等待审批 */
    PENDING,
    /** 已审批 — 审批通过，等待执行冲正操作 */
    APPROVED,
    /** 已拒绝 — 审批拒绝，冲正请求被驳回 */
    REJECTED,
    /** 已完成 — 冲正成功，订单状态已变更为 REVERSED */
    COMPLETED,
    /** 已失败 — 冲正执行失败 */
    FAILED
}