package org.nexus.gateway.reconciliation.link;

/**
 * 对账差异调整类型枚举。
 *
 * <p>标识资金调整的方向：
 * <ul>
 *   <li>{@code CREDIT_ADJUST}：加钱调整 — 商户余额增加（如长款入账）</li>
 *   <li>{@code DEBIT_ADJUST}：扣钱调整 — 商户余额减少（如短款扣减）</li>
 * </ul>
 * </p>
 */
public enum AdjustmentType {
    /** 加钱调整 — 商户余额增加（如长款入账、金额差异补入） */
    CREDIT_ADJUST,
    /** 扣钱调整 — 商户余额减少（如短款扣减、金额差异补扣） */
    DEBIT_ADJUST
}