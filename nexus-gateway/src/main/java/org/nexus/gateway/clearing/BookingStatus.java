package org.nexus.gateway.clearing;

/**
 * 清算结算入账状态枚举。
 *
 * <p>定义清算结算记录的入账状态流转：</p>
 * <ul>
 *   <li>{@link #PENDING} — 待入账：记录已创建但尚未执行入账操作</li>
 *   <li>{@link #BOOKED} — 已入账：入账操作成功完成</li>
 *   <li>{@link #FAILED} — 入账失败：入账操作执行失败</li>
 * </ul>
 *
 * <p>状态流转：{@code PENDING} → {@code BOOKED} 或 {@code PENDING} → {@code FAILED}。</p>
 */
public enum BookingStatus {
    /** 待入账 — 记录已创建但尚未执行入账 */
    PENDING,
    /** 已入账 — 入账操作成功完成 */
    BOOKED,
    /** 入账失败 — 入账操作执行失败 */
    FAILED
}