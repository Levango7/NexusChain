package org.nexus.gateway.account;

/**
 * 账户流水方向枚举。
 *
 * <p>标识资金流向：{@link #CREDIT} 表示资金流入账户（余额增加），
 * {@link #DEBIT} 表示资金流出账户（余额减少）。</p>
 */
public enum TransactionDirection {
    /** 入账 — 资金流入，余额增加 */
    CREDIT,
    /** 出账 — 资金流出，余额减少 */
    DEBIT
}