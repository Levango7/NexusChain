package org.nexus.gateway.fundtransfer;

/**
 * 调拨规则触发类型枚举。
 *
 * <p>定义资金调拨规则的触发方式：</p>
 * <ul>
 *   <li>{@link #BALANCE_THRESHOLD} — 余额阈值触发：当账户余额达到指定阈值时自动触发调拨</li>
 *   <li>{@link #SCHEDULED} — 定时触发：按 cron 表达式定时执行调拨</li>
 *   <li>{@link #MANUAL} — 手动触发：仅支持手动调用执行</li>
 * </ul>
 */
public enum TriggerType {
    /** 余额阈值触发 — 当账户余额达到指定阈值时自动触发 */
    BALANCE_THRESHOLD,
    /** 定时触发 — 按 cron 表达式定时执行 */
    SCHEDULED,
    /** 手动触发 — 仅支持手动调用 */
    MANUAL
}