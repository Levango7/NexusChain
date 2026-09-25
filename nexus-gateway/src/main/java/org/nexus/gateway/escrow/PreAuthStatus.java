package org.nexus.gateway.escrow;

/**
 * 预授权状态枚举。
 *
 * <p>状态流转：</p>
 * <pre>
 *   AUTHORIZED → CAPTURED（终态）
 *   AUTHORIZED → VOIDED（终态）
 *   AUTHORIZED → EXPIRED（终态，超时自动释放）
 * </pre>
 */
public enum PreAuthStatus {
    /** 已授权 — 金额冻结在冻结账户，等待扣款/撤销/过期 */
    AUTHORIZED,
    /** 已扣款 — 扣款金额转入商户余额，剩余金额释放回原账户（终态） */
    CAPTURED,
    /** 已撤销 — 冻结金额全部释放回原账户（终态） */
    VOIDED,
    /** 已过期 — 超时自动释放，等同 VOIDED（终态） */
    EXPIRED
}