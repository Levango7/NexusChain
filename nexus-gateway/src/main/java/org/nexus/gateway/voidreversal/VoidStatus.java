package org.nexus.gateway.voidreversal;

/**
 * 撤销请求状态枚举。
 *
 * <p>状态流转：</p>
 * <ul>
 *   <li>{@link #PENDING} → {@link #APPROVED} → {@link #COMPLETED}：审批通过并完成撤销</li>
 *   <li>{@link #PENDING} → {@link #REJECTED}：审批拒绝</li>
 *   <li>{@link #PENDING} → {@link #FAILED}：撤销执行失败</li>
 *   <li>{@link #COMPLETED}：终态，撤销已完成</li>
 *   <li>{@link #REJECTED}：终态，撤销被拒绝</li>
 *   <li>{@link #FAILED}：终态，撤销失败</li>
 * </ul>
 */
public enum VoidStatus {
    /** 待审批 — 撤销请求已创建，等待审批 */
    PENDING,
    /** 已审批 — 审批通过，等待执行撤销操作 */
    APPROVED,
    /** 已拒绝 — 审批拒绝，撤销请求被驳回 */
    REJECTED,
    /** 已完成 — 撤销成功，订单状态已变更为 VOIDED */
    COMPLETED,
    /** 已失败 — 撤销执行失败（余额不足等） */
    FAILED
}