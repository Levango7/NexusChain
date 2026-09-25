package org.nexus.gateway.account;

/**
 * 账户流水操作类型枚举。
 *
 * <p>每种操作类型对应一种余额变更场景，流水记录中 {@link TransactionDirection}
 * 标识资金流向（CREDIT 入账 / DEBIT 出账）。</p>
 */
public enum AccountOperationType {
    /** 充值 — 管理员向商户账户充值 */
    DEPOSIT,
    /** 提现 — 商户从账户提现到链上钱包 */
    WITHDRAW,
    /** 冻结 — 余额账户 → 冻结账户 */
    FREEZE,
    /** 解冻 — 冻结账户 → 余额账户 */
    UNFREEZE,
    /** 转账 — 账户间资金转移 */
    TRANSFER,
    /** 支付入账 — 支付确认后商户余额增加 */
    PAYMENT,
    /** 退款出账 — 退款完成后商户余额减少 */
    REFUND,
    /** 撤销回滚 — 撤销交易后余额回滚 */
    VOID_REVERSE,
    /** 冲正调整 — 冲正交易后余额调整 */
    REVERSAL_ADJUST,
    /** 担保冻结 — 担保交易冻结金额 */
    ESCROW_FREEZE,
    /** 担保释放 — 担保交易释放冻结金额 */
    ESCROW_RELEASE,
    /** 预授权冻结 — 预授权冻结金额 */
    PREAUTH_FREEZE,
    /** 预授权扣款 — 预授权捕获扣款 */
    PREAUTH_CAPTURE,
    /** 预授权释放 — 预授权释放冻结金额 */
    PREAUTH_RELEASE,
    /** 清算入账 — 清算结算后商户余额增加（CREDIT） */
    CLEARING_SETTLE,
    /** 结算划拨 — 结算资金双向转移（CREDIT/DEBIT） */
    SETTLEMENT_TRANSFER,
    /** 对账差异调整 — 对账发现差异后余额调整（CREDIT/DEBIT） */
    RECON_ADJUST,
    /** 挂账核销 — 挂账资金核销处理（CREDIT/DEBIT） */
    SUSPENSE_WRITEOFF,
    /** 风控冻结 — 风控触发冻结金额（DEBIT+CREDIT） */
    RISK_FREEZE,
    /** 风控解冻 — 风控触发解冻金额（DEBIT+CREDIT） */
    RISK_UNFREEZE,
    /** 自动提现 — 系统自动触发提现（DEBIT） */
    AUTO_WITHDRAW,
    /** 备付金补充 — 备付金账户资金补充（DEBIT+CREDIT） */
    RESERVE_REPLENISH
}