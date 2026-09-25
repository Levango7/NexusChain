package org.nexus.gateway.fundtransfer;

import org.nexus.gateway.account.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 资金调拨服务 — 手动调拨、规则触发调拨、规则配置管理。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>{@link #manualTransfer} — 手动调拨：指定商户、账户类型和金额进行资金调拨</li>
 *   <li>{@link #executeTransferRules} — 规则触发调拨：检查余额阈值规则并自动执行</li>
 *   <li>{@link #createTransferRule} — 创建调拨规则</li>
 *   <li>{@link #updateTransferRule} — 更新调拨规则</li>
 *   <li>{@link #getTransferRules} — 查询调拨规则列表</li>
 * </ul>
 *
 * <p>所有资金操作通过 {@link AccountService} 执行，不直接操作 Repository。</p>
 */
@Service
public class FundTransferService {

    private static final Logger log = LoggerFactory.getLogger(FundTransferService.class);

    private final AccountService accountService;
    private final TransferRuleRepository transferRuleRepository;
    private final MerchantAccountRepository accountRepository;

    public FundTransferService(AccountService accountService,
                               TransferRuleRepository transferRuleRepository,
                               MerchantAccountRepository accountRepository) {
        this.accountService = accountService;
        this.transferRuleRepository = transferRuleRepository;
        this.accountRepository = accountRepository;
    }

    // === 手动调拨 ===

    /**
     * 手动调拨 — 从商户的指定类型账户调拨资金到另一类型账户。
     *
     * <p>操作步骤：</p>
     <ol>
     *   <li>获取转出账户和转入账户</li>
     *   <li>校验余额充足</li>
     *   <li>通过 AccountService.transfer 执行转账</li>
     * </ol>
     *
     * @param merchantId 商户ID
     * @param fromAccountType 转出账户类型
     * @param toAccountType 转入账户类型
     * @param amount 调拨金额（必须 > 0）
     * @param reference 调拨关联凭证
     * @return 转出账户（调拨后）
     * @throws IllegalArgumentException 金额 <= 0 或账户类型相同
     * @throws IllegalStateException 余额不足或账户不可操作
     */
    @Transactional
    public MerchantAccount manualTransfer(Long merchantId, AccountType fromAccountType,
                                           AccountType toAccountType, BigDecimal amount, String reference) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("调拨金额必须大于 0");
        }
        if (fromAccountType == toAccountType) {
            throw new IllegalArgumentException("转出和转入账户类型不能相同");
        }

        // 确保两个账户都存在
        MerchantAccount fromAccount = accountService.getOrCreateAccount(merchantId, fromAccountType);
        MerchantAccount toAccount = accountService.getOrCreateAccount(merchantId, toAccountType);

        log.info("手动调拨: merchantId={}, from={}, to={}, amount={}, reference={}",
                merchantId, fromAccountType, toAccountType, amount, reference);

        return accountService.transfer(fromAccount.getAccountId(), toAccount.getAccountId(), amount);
    }

    // === 规则触发调拨 ===

    /**
     * 执行调拨规则 — 检查所有启用的余额阈值规则，满足条件时自动执行调拨。
     *
     * <p>对每条 BALANCE_THRESHOLD 类型的启用规则：</p>
     * <ol>
     *   <li>获取商户的转出账户余额</li>
     *   <li>判断余额是否满足阈值条件（ABOVE/BELOW）</li>
     *   <li>满足条件时计算调拨金额并执行转账</li>
     * </ol>
     *
     * @param merchantId 商户ID（为 null 时检查所有商户）
     * @return 成功执行的调拨次数
     */
    @Transactional
    public int executeTransferRules(Long merchantId) {
        List<TransferRule> rules = transferRuleRepository
                .findByTriggerTypeAndEnabled(TriggerType.BALANCE_THRESHOLD, true);

        int executedCount = 0;
        for (TransferRule rule : rules) {
            try {
                if (executeSingleRule(rule, merchantId)) {
                    executedCount++;
                }
            } catch (Exception e) {
                log.warn("调拨规则执行失败: ruleCode={}, error={}", rule.getRuleCode(), e.getMessage());
            }
        }

        log.info("调拨规则执行完成: 共检查 {} 条规则, 成功执行 {} 条", rules.size(), executedCount);
        return executedCount;
    }

    /**
     * 执行单条调拨规则。
     *
     * @param rule 调拨规则
     * @param targetMerchantId 目标商户ID（为 null 时跳过商户过滤）
     * @return 是否执行了调拨
     */
    private boolean executeSingleRule(TransferRule rule, Long targetMerchantId) {
        // 获取所有商户（如果指定了商户ID则只检查该商户）
        List<MerchantAccount> accounts;
        if (targetMerchantId != null) {
            accounts = accountRepository.findByMerchantIdAndAccountType(
                            targetMerchantId, rule.getFromAccountType())
                    .map(List::of)
                    .orElse(List.of());
        } else {
            accounts = accountRepository.findByAccountType(rule.getFromAccountType());
        }

        for (MerchantAccount account : accounts) {
            BigDecimal balance = account.getBalance();
            boolean thresholdMet = checkThreshold(balance, rule);

            if (thresholdMet) {
                BigDecimal transferAmount = calculateTransferAmount(balance, rule);
                if (transferAmount.compareTo(BigDecimal.ZERO) > 0) {
                    MerchantAccount toAccount = accountService.getOrCreateAccount(
                            account.getMerchantId(), rule.getToAccountType());

                    log.info("规则触发调拨: ruleCode={}, merchantId={}, from={}, to={}, amount={}",
                            rule.getRuleCode(), account.getMerchantId(),
                            rule.getFromAccountType(), rule.getToAccountType(), transferAmount);

                    accountService.transfer(account.getAccountId(), toAccount.getAccountId(), transferAmount);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 检查余额是否满足阈值条件。
     */
    public boolean checkThreshold(BigDecimal balance, TransferRule rule) {
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
    public BigDecimal calculateTransferAmount(BigDecimal balance, TransferRule rule) {
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

    // === 规则配置管理 ===

    /**
     * 创建调拨规则。
     *
     * @param rule 调拨规则配置
     * @return 创建后的规则
     * @throws IllegalArgumentException 规则编号已存在
     */
    @Transactional
    public TransferRule createTransferRule(TransferRule rule) {
        if (rule.getRuleCode() == null || rule.getRuleCode().isBlank()) {
            throw new IllegalArgumentException("规则编号不能为空");
        }
        if (transferRuleRepository.findByRuleCode(rule.getRuleCode()).isPresent()) {
            throw new IllegalArgumentException("规则编号已存在: " + rule.getRuleCode());
        }

        validateRule(rule);
        rule = transferRuleRepository.save(rule);
        log.info("创建调拨规则: ruleCode={}, triggerType={}", rule.getRuleCode(), rule.getTriggerType());
        return rule;
    }

    /**
     * 更新调拨规则。
     *
     * @param ruleCode 规则编号
     * @param updates 更新内容
     * @return 更新后的规则
     * @throws IllegalArgumentException 规则不存在
     */
    @Transactional
    public TransferRule updateTransferRule(String ruleCode, TransferRule updates) {
        TransferRule existing = transferRuleRepository.findByRuleCode(ruleCode)
                .orElseThrow(() -> new IllegalArgumentException("规则不存在: " + ruleCode));

        if (updates.getRuleName() != null) {
            existing.setRuleName(updates.getRuleName());
        }
        if (updates.getFromAccountType() != null) {
            existing.setFromAccountType(updates.getFromAccountType());
        }
        if (updates.getToAccountType() != null) {
            existing.setToAccountType(updates.getToAccountType());
        }
        if (updates.getTriggerType() != null) {
            existing.setTriggerType(updates.getTriggerType());
        }
        if (updates.getThresholdAmount() != null) {
            existing.setThresholdAmount(updates.getThresholdAmount());
        }
        if (updates.getThresholdDirection() != null) {
            existing.setThresholdDirection(updates.getThresholdDirection());
        }
        if (updates.getTransferAmountType() != null) {
            existing.setTransferAmountType(updates.getTransferAmountType());
        }
        if (updates.getTransferAmount() != null) {
            existing.setTransferAmount(updates.getTransferAmount());
        }
        if (updates.getTransferPercentage() != null) {
            existing.setTransferPercentage(updates.getTransferPercentage());
        }
        if (updates.getCronExpression() != null) {
            existing.setCronExpression(updates.getCronExpression());
        }
        if (updates.getEnabled() != null) {
            existing.setEnabled(updates.getEnabled());
        }
        if (updates.getDescription() != null) {
            existing.setDescription(updates.getDescription());
        }

        validateRule(existing);
        existing = transferRuleRepository.save(existing);
        log.info("更新调拨规则: ruleCode={}", ruleCode);
        return existing;
    }

    /**
     * 查询调拨规则列表。
     *
     * @param triggerType 触发类型（为 null 时查询全部）
     * @param enabled 启用状态（为 null 时查询全部）
     * @return 调拨规则列表
     */
    @Transactional(readOnly = true)
    public List<TransferRule> getTransferRules(TriggerType triggerType, Boolean enabled) {
        if (triggerType != null && enabled != null) {
            return transferRuleRepository.findByTriggerTypeAndEnabled(triggerType, enabled);
        } else if (triggerType != null) {
            return transferRuleRepository.findByTriggerType(triggerType);
        } else if (enabled != null) {
            return transferRuleRepository.findByEnabled(enabled);
        }
        return transferRuleRepository.findAll();
    }

    /**
     * 按规则编号查询调拨规则。
     *
     * @param ruleCode 规则编号
     * @return 调拨规则（可能为空）
     */
    @Transactional(readOnly = true)
    public Optional<TransferRule> getTransferRule(String ruleCode) {
        return transferRuleRepository.findByRuleCode(ruleCode);
    }

    // === 校验 ===

    /**
     * 校验调拨规则配置的完整性。
     */
    private void validateRule(TransferRule rule) {
        if (rule.getTriggerType() == TriggerType.BALANCE_THRESHOLD) {
            if (rule.getThresholdAmount() == null) {
                throw new IllegalArgumentException("BALANCE_THRESHOLD 类型必须指定阈值金额");
            }
            if (rule.getThresholdDirection() == null) {
                throw new IllegalArgumentException("BALANCE_THRESHOLD 类型必须指定阈值方向");
            }
        }
        if (rule.getTriggerType() == TriggerType.SCHEDULED) {
            if (rule.getCronExpression() == null || rule.getCronExpression().isBlank()) {
                throw new IllegalArgumentException("SCHEDULED 类型必须指定 cron 表达式");
            }
        }
        if (rule.getTransferAmountType() == TransferAmountType.FIXED) {
            if (rule.getTransferAmount() == null) {
                throw new IllegalArgumentException("FIXED 类型必须指定固定金额");
            }
        }
        if (rule.getTransferAmountType() == TransferAmountType.PERCENTAGE) {
            if (rule.getTransferPercentage() == null) {
                throw new IllegalArgumentException("PERCENTAGE 类型必须指定百分比");
            }
            if (rule.getTransferPercentage().compareTo(BigDecimal.ZERO) < 0
                    || rule.getTransferPercentage().compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("百分比必须在 0-100 之间");
            }
        }
    }
}