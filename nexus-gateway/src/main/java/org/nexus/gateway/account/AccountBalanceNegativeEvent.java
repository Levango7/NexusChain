package org.nexus.gateway.account;

import java.math.BigDecimal;

/**
 * 账户余额负数告警事件 — 退款等操作导致余额变为负数时发布，供告警系统消费。
 *
 * <p>继承 {@link AccountBalanceChangedEvent}，复用账户余额变更的基础字段，
 * 额外携带触发负余额的业务凭证编号（如 refundNo），便于告警系统追溯原因。</p>
 *
 * <p>典型场景：退款扣减时余额不足，允许暂时为负但需触发告警。</p>
 */
public class AccountBalanceNegativeEvent extends AccountBalanceChangedEvent {

    /** 触发负余额的业务凭证编号（如 refundNo） */
    private final String triggerReference;

    /**
     * 构造余额负数告警事件。
     *
     * @param source           事件源
     * @param merchantId       商户 ID
     * @param accountId        账户编号
     * @param operationType    操作类型
     * @param amount           操作金额
     * @param balanceBefore    操作前余额
     * @param balanceAfter     操作后余额（负数）
     * @param triggerReference 触发凭证编号
     */
    public AccountBalanceNegativeEvent(Object source, Long merchantId, String accountId,
                                        AccountOperationType operationType, BigDecimal amount,
                                        BigDecimal balanceBefore, BigDecimal balanceAfter,
                                        String triggerReference) {
        super(source, null, null, merchantId, accountId, operationType, amount, balanceBefore, balanceAfter);
        this.triggerReference = triggerReference;
    }

    @Override
    public String getEventType() { return "ACCOUNT_BALANCE_NEGATIVE"; }

    public String getTriggerReference() { return triggerReference; }
}