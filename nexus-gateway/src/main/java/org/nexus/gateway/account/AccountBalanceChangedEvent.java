package org.nexus.gateway.account;

import org.nexus.gateway.event.PaymentEvent;

import java.math.BigDecimal;

/**
 * 账户余额变更事件 — 余额变更后发布，供清算引擎、对账模块、分账模块消费。
 *
 * <p>继承 {@link PaymentEvent}，复用 orderId/orderNo/merchantId 基础字段，
 * 额外携带账户余额变更的详细信息。</p>
 */
public class AccountBalanceChangedEvent extends PaymentEvent {

    private final String accountId;
    private final AccountOperationType operationType;
    private final BigDecimal amount;
    private final BigDecimal balanceBefore;
    private final BigDecimal balanceAfter;

    public AccountBalanceChangedEvent(Object source, Long orderId, String orderNo, Long merchantId,
                                       String accountId, AccountOperationType operationType,
                                       BigDecimal amount, BigDecimal balanceBefore, BigDecimal balanceAfter) {
        super(source, orderId, orderNo, merchantId);
        this.accountId = accountId;
        this.operationType = operationType;
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
    }

    @Override
    public String getEventType() { return "ACCOUNT_BALANCE_CHANGED"; }

    public String getAccountId() { return accountId; }
    public AccountOperationType getOperationType() { return operationType; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceBefore() { return balanceBefore; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
}