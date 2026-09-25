package org.nexus.gateway.fundtransfer;

import org.nexus.gateway.account.AccountBalanceChangedEvent;
import org.nexus.gateway.account.AccountType;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.account.MerchantAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 调拨规则事件监听器 — 监听 {@link AccountBalanceChangedEvent} 自动触发调拨规则。
 *
 * <p>当账户余额变更时，检查所有 BALANCE_THRESHOLD 类型的启用规则，
 * 满足阈值条件时自动执行调拨。使用 {@code @Async} 异步执行，
 * 联动失败不阻断主流程。</p>
 */
@Component
public class TransferRuleEventListener {

    private static final Logger log = LoggerFactory.getLogger(TransferRuleEventListener.class);

    private final TransferRuleRepository transferRuleRepository;
    private final FundTransferService fundTransferService;
    private final MerchantAccountRepository accountRepository;

    public TransferRuleEventListener(TransferRuleRepository transferRuleRepository,
                                      FundTransferService fundTransferService,
                                      MerchantAccountRepository accountRepository) {
        this.transferRuleRepository = transferRuleRepository;
        this.fundTransferService = fundTransferService;
        this.accountRepository = accountRepository;
    }

    /**
     * 监听账户余额变更事件 — 异步检查并执行调拨规则。
     *
     * <p>联动失败不阻断主流程，仅记录日志。</p>
     *
     * @param event 账户余额变更事件
     */
    @Async
    @EventListener
    public void onAccountBalanceChanged(AccountBalanceChangedEvent event) {
        try {
            log.debug("收到余额变更事件: merchantId={}, accountId={}, operationType={}, balanceAfter={}",
                    event.getMerchantId(), event.getAccountId(),
                    event.getOperationType(), event.getBalanceAfter());

            // 查找所有启用的余额阈值规则
            List<TransferRule> rules = transferRuleRepository
                    .findByTriggerTypeAndEnabled(TriggerType.BALANCE_THRESHOLD, true);

            for (TransferRule rule : rules) {
                try {
                    checkAndExecuteRule(rule, event);
                } catch (Exception e) {
                    log.warn("调拨规则检查失败: ruleCode={}, error={}", rule.getRuleCode(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("余额变更事件处理失败: merchantId={}, error={}",
                    event.getMerchantId(), e.getMessage(), e);
        }
    }

    /**
     * 检查单条规则是否满足阈值条件并执行调拨。
     */
    private void checkAndExecuteRule(TransferRule rule, AccountBalanceChangedEvent event) {
        // 获取商户的转出账户
        MerchantAccount fromAccount = accountRepository
                .findByMerchantIdAndAccountType(event.getMerchantId(), rule.getFromAccountType())
                .orElse(null);

        if (fromAccount == null) {
            return;
        }

        // 检查阈值条件
        BigDecimal balance = fromAccount.getBalance();
        if (!checkThreshold(balance, rule)) {
            return;
        }

        // 计算调拨金额
        BigDecimal transferAmount = calculateTransferAmount(balance, rule);
        if (transferAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        log.info("事件触发调拨: ruleCode={}, merchantId={}, from={}, to={}, amount={}",
                rule.getRuleCode(), event.getMerchantId(),
                rule.getFromAccountType(), rule.getToAccountType(), transferAmount);

        fundTransferService.manualTransfer(
                event.getMerchantId(),
                rule.getFromAccountType(),
                rule.getToAccountType(),
                transferAmount,
                "RULE_" + rule.getRuleCode());
    }

    /**
     * 检查余额是否满足阈值条件。
     */
    private boolean checkThreshold(BigDecimal balance, TransferRule rule) {
        if (rule.getThresholdAmount() == null) {
            return false;
        }
        String direction = rule.getThresholdDirection();
        if ("ABOVE".equalsIgnoreCase(direction)) {
            return balance.compareTo(rule.getThresholdAmount()) >= 0;
        } else if ("BELOW".equalsIgnoreCase(direction)) {
            return balance.compareTo(rule.getThresholdAmount()) <= 0;
        }
        return false;
    }

    /**
     * 根据规则计算调拨金额。
     */
    private BigDecimal calculateTransferAmount(BigDecimal balance, TransferRule rule) {
        return switch (rule.getTransferAmountType()) {
            case FIXED -> rule.getTransferAmount() != null ? rule.getTransferAmount() : BigDecimal.ZERO;
            case PERCENTAGE -> {
                if (rule.getTransferPercentage() == null) {
                    yield BigDecimal.ZERO;
                }
                yield balance.multiply(rule.getTransferPercentage())
                        .divide(new BigDecimal("100"), 0, RoundingMode.DOWN);
            }
            case ALL -> balance;
        };
    }
}