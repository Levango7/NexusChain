package org.nexus.gateway.risk.link;

import org.nexus.gateway.account.AccountBalanceChangedEvent;
import org.nexus.gateway.account.AccountOperationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 大额交易拦截事件监听器 — 监听超限额信号。
 *
 * <p>当账户余额变更事件中的交易金额超过预设阈值时，自动创建大额交易拦截记录，
 * 阻断交易并等待人工审核。</p>
 *
 * <p>触发条件：交易金额 > large-transaction-threshold（默认 100000）。</p>
 */
@Component
public class LargeTransactionInterceptionListener {

    private static final Logger log = LoggerFactory.getLogger(LargeTransactionInterceptionListener.class);

    private final LargeTransactionInterceptionService interceptionService;
    private final BigDecimal defaultThreshold;

    public LargeTransactionInterceptionListener(
            LargeTransactionInterceptionService interceptionService,
            @Value("${nexus.risk.large-transaction-threshold:100000}") String threshold) {
        this.interceptionService = interceptionService;
        this.defaultThreshold = new BigDecimal(threshold);
    }

    /**
     * 监听账户余额变更事件 — 检查交易金额是否超过阈值。
     *
     * <p>仅对以下操作类型触发拦截检查：</p>
     * <ul>
     *   <li>WITHDRAW — 提现</li>
     *   <li>TRANSFER — 转账</li>
     *   <li>REFUND — 退款</li>
     * </ul>
     *
     * @param event 账户余额变更事件
     */
    @Async
    @EventListener
    public void onAccountBalanceChanged(AccountBalanceChangedEvent event) {
        if (event.getAmount() == null || event.getMerchantId() == null) {
            return;
        }

        // 仅对出账类操作触发拦截检查
        AccountOperationType opType = event.getOperationType();
        if (opType != AccountOperationType.WITHDRAW
                && opType != AccountOperationType.TRANSFER
                && opType != AccountOperationType.REFUND) {
            return;
        }

        BigDecimal amount = event.getAmount();
        if (amount.compareTo(defaultThreshold) <= 0) {
            return;
        }

        log.info("检测到大额交易，触发拦截: merchantId={}, amount={}, threshold={}, operationType={}",
                event.getMerchantId(), amount, defaultThreshold, opType);

        try {
            // 使用订单号作为 riskEventId，确保幂等检查生效
            String riskEventId = event.getOrderNo() != null
                    ? "LTI-" + event.getOrderNo()
                    : "LTI-" + event.getMerchantId() + "-" + System.currentTimeMillis();

            interceptionService.intercept(
                    event.getMerchantId(),
                    event.getOrderNo(),
                    amount,
                    null,
                    defaultThreshold,
                    riskEventId
            );
        } catch (Exception e) {
            log.error("大额交易拦截失败: merchantId={}, amount={}, error={}",
                    event.getMerchantId(), amount, e.getMessage(), e);
        }
    }
}