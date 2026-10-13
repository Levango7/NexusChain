package org.nexus.gateway.clearing;

/**
 * 清算结算入账状态枚举。
 *
 * <p>定义清算结算记录的入账状态流转：</p>
 * <ul>
 *   <li>{@link #PENDING} — 待入账：记录已创建但尚未执行入账操作</li>
 *   <li>{@link #EXECUTING} — 已认领：某副本已拿到这条指令的执行权，动账尚未收尾</li>
 *   <li>{@link #BOOKED} — 已入账：入账操作成功完成</li>
 *   <li>{@link #FAILED} — 入账失败：入账操作执行失败</li>
 * </ul>
 *
 * <p>状态流转：{@code PENDING} →（CAS 认领）→ {@code EXECUTING} → {@code BOOKED} 或 {@code FAILED}。
 * 兼容说明：历史上存在 {@code PENDING → BOOKED/FAILED} 的直接流转（ clearing 侧仍以 PENDING 落账后
 * 就地改写），{@code EXECUTING} 只由"结算划拨指令"的认领流程产生，不影响其它写入点。</p>
 *
 * <p>{@code EXECUTING} 残留是**可见的故障信号**（副本在认领后、收尾前崩溃），
 * 交人工/对账处理；不引入该态的话，认领只能靠"直接改成终态"，
 * 那会把"改了状态但钱没动"变成静默丢失。</p>
 */
public enum BookingStatus {
    /** 待入账 — 记录已创建但尚未执行入账 */
    PENDING,
    /** 已认领 — 执行权已被某个副本原子拿走，尚未收尾 */
    EXECUTING,
    /** 已入账 — 入账操作成功完成 */
    BOOKED,
    /** 入账失败 — 入账操作执行失败 */
    FAILED
}