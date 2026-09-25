package org.nexus.gateway.escrow;

/**
 * 担保交易状态枚举。
 *
 * <p>状态流转：</p>
 * <pre>
 *   CREATED → FUNDED → CONFIRMED → RELEASED（终态）
 *   CREATED → FUNDED → REFUNDED（终态）
 *   CREATED → 超时取消
 * </pre>
 */
public enum EscrowStatus {
    /** 已创建 — 等待买家付款，资金未冻结 */
    CREATED,
    /** 已付款 — 买家已付款，资金冻结在担保账户 */
    FUNDED,
    /** 已确认 — 买家确认收货，待释放资金 */
    CONFIRMED,
    /** 已释放 — 资金已转入商户余额（终态） */
    RELEASED,
    /** 已退款 — 资金已退回买家（终态） */
    REFUNDED
}