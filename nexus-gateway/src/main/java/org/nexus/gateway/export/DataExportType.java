package org.nexus.gateway.export;

/**
 * 数据导出类型枚举。
 *
 * <p>定义支持的数据导出类型，每种类型对应一类业务数据：
 * <ul>
 *   <li>{@link #TRANSACTIONS} — 交易记录（PaymentOrder）</li>
 *   <li>{@link #SETTLEMENTS} — 结算记录</li>
 *   <li>{@link #REFUNDS} — 退款记录（Refund）</li>
 *   <li>{@link #SPLITS} — 分账记录（SplitOrder）</li>
 *   <li>{@link #RISK_EVENTS} — 风控事件（RiskEvent）</li>
 *   <li>{@link #WEBHOOK_DELIVERIES} — Webhook 投递记录</li>
 * </ul>
 */
public enum DataExportType {
    /** 交易记录 */
    TRANSACTIONS,
    /** 结算记录 */
    SETTLEMENTS,
    /** 退款记录 */
    REFUNDS,
    /** 分账记录 */
    SPLITS,
    /** 风控事件 */
    RISK_EVENTS,
    /** Webhook 投递记录 */
    WEBHOOK_DELIVERIES
}