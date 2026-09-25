package org.nexus.gateway.fundreport;

import org.nexus.gateway.account.AccountOperationType;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.account.MerchantAccountRepository;
import org.nexus.gateway.account.AccountStatus;
import org.nexus.gateway.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 自动提现服务 — 商户自动提现规则配置与执行。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>{@link #configureAutoWithdraw} — 配置自动提现规则（含参数校验）</li>
 *   <li>{@link #executeAutoWithdraw} — 执行自动提现（幂等控制 + 余额检查 + 风控评估 + 提现执行）</li>
 *   <li>{@link #getAutoWithdrawRule} — 查询商户自动提现规则</li>
 *   <li>{@link #disableAutoWithdraw} — 禁用自动提现规则</li>
 * </ul>
 *
 * <p>自动提现通过 {@link AccountService#withdrawWithType} 执行，
 * 操作类型为 {@link AccountOperationType#AUTO_WITHDRAW}。</p>
 */
@Service
public class AutoWithdrawService {

    private static final Logger log = LoggerFactory.getLogger(AutoWithdrawService.class);

    /** 单次自动提现最大金额限制 */
    private static final BigDecimal MAX_WITHDRAW_AMOUNT = new BigDecimal("100000000");

    /** 最低保留余额默认值 */
    private static final BigDecimal DEFAULT_MIN_RETAIN = BigDecimal.ZERO;

    private final AutoWithdrawRuleRepository ruleRepository;
    private final AccountService accountService;
    private final MerchantAccountRepository accountRepository;

    public AutoWithdrawService(AutoWithdrawRuleRepository ruleRepository,
                                AccountService accountService,
                                MerchantAccountRepository accountRepository) {
        this.ruleRepository = ruleRepository;
        this.accountService = accountService;
        this.accountRepository = accountRepository;
    }

    // === 配置自动提现 ===

    /**
     * 配置自动提现规则 — 创建或更新商户的自动提现配置。
     *
     * <p>校验规则：</p>
     * <ul>
     *   <li>threshold 必须 > 0</li>
     *   <li>targetAmount 若不为 null，必须 > 0 且 <= threshold</li>
     *   <li>minRetain 必须 >= 0</li>
     *   <li>threshold 必须 > minRetain</li>
     *   <li>同一商户只能有一条启用的规则</li>
     * </ul>
     *
     * @param merchantId 商户 ID
     * @param frequency 提现频率
     * @param threshold 触发阈值
     * @param targetAmount 目标提现金额（null 表示提现全部余额）
     * @param minRetain 最低保留余额
     * @return 配置后的规则
     * @throws IllegalArgumentException 参数校验失败
     */
    @Transactional
    public AutoWithdrawRule configureAutoWithdraw(Long merchantId, WithdrawFrequency frequency,
                                                   BigDecimal threshold, BigDecimal targetAmount,
                                                   BigDecimal minRetain) {
        // 参数校验
        if (merchantId == null) {
            throw new IllegalArgumentException("商户 ID 不能为空");
        }
        if (frequency == null) {
            throw new IllegalArgumentException("提现频率不能为空");
        }
        if (threshold == null || threshold.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("触发阈值必须大于 0");
        }
        if (targetAmount != null && targetAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("目标提现金额必须大于 0");
        }
        if (targetAmount != null && targetAmount.compareTo(threshold) > 0) {
            throw new IllegalArgumentException("目标提现金额不能大于触发阈值");
        }
        if (minRetain == null) {
            minRetain = DEFAULT_MIN_RETAIN;
        }
        if (minRetain.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("最低保留余额不能小于 0");
        }
        if (threshold.compareTo(minRetain) <= 0) {
            throw new IllegalArgumentException("触发阈值必须大于最低保留余额");
        }

        // 同一商户只能有一条启用的规则
        Optional<AutoWithdrawRule> existingRule = ruleRepository.findByMerchantIdAndEnabledTrue(merchantId);
        AutoWithdrawRule rule;
        if (existingRule.isPresent()) {
            rule = existingRule.get();
            log.info("更新商户自动提现规则: merchantId={}, ruleId={}", merchantId, rule.getId());
        } else {
            rule = new AutoWithdrawRule();
            rule.setMerchantId(merchantId);
            rule.setTenantId(TenantContext.getCurrentTenantId());
            log.info("创建商户自动提现规则: merchantId={}", merchantId);
        }

        rule.setFrequency(frequency);
        rule.setThreshold(threshold);
        rule.setTargetAmount(targetAmount);
        rule.setMinRetain(minRetain);
        rule.setEnabled(true);

        return ruleRepository.save(rule);
    }

    // === 执行自动提现 ===

    /**
     * 执行自动提现 — 根据规则触发自动提现操作。
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>幂等控制：检查当前周期是否已执行过（基于 lastExecutedAt）</li>
     *   <li>余额检查：查询商户可用余额，判断是否达到触发阈值</li>
     *   <li>风控评估：检查账户状态和预警标志</li>
     *   <li>提现执行：通过 AccountService.withdrawWithType 执行提现</li>
     * </ol>
     *
     * @param rule 自动提现规则
     * @return 执行结果（包含是否执行、提现金额、流水编号等）
     */
    @Transactional
    public Map<String, Object> executeAutoWithdraw(AutoWithdrawRule rule) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleId", rule.getId());
        result.put("merchantId", rule.getMerchantId());
        result.put("executed", false);

        // 1. 幂等控制：检查当前周期是否已执行
        if (isAlreadyExecutedThisPeriod(rule)) {
            log.info("自动提现已在本周期执行过，跳过: merchantId={}, ruleId={}",
                    rule.getMerchantId(), rule.getId());
            result.put("reason", "ALREADY_EXECUTED_THIS_PERIOD");
            return result;
        }

        // 2. 余额检查：查询可用余额
        Map<String, Object> balanceInfo = accountService.getBalance(rule.getMerchantId());
        BigDecimal balance = (BigDecimal) balanceInfo.get("balance");
        String balanceStatus = (String) balanceInfo.get("balanceStatus");

        result.put("currentBalance", balance);

        if (balance.compareTo(rule.getThreshold()) < 0) {
            log.debug("余额未达到触发阈值，跳过: merchantId={}, balance={}, threshold={}",
                    rule.getMerchantId(), balance, rule.getThreshold());
            result.put("reason", "BELOW_THRESHOLD");
            return result;
        }

        // 3. 风控评估：检查账户状态
        if ("FROZEN".equals(balanceStatus)) {
            log.warn("账户已冻结，无法自动提现: merchantId={}", rule.getMerchantId());
            result.put("reason", "ACCOUNT_FROZEN");
            return result;
        }
        if ("CLOSED".equals(balanceStatus)) {
            log.warn("账户已关闭，无法自动提现: merchantId={}", rule.getMerchantId());
            result.put("reason", "ACCOUNT_CLOSED");
            return result;
        }

        // 检查预警标志
        Optional<MerchantAccount> accountOpt = accountRepository
                .findByMerchantIdAndAccountType(rule.getMerchantId(),
                        org.nexus.gateway.account.AccountType.BALANCE);
        if (accountOpt.isPresent()) {
            MerchantAccount account = accountOpt.get();
            if (account.getAlertFlag() != null && "CRITICAL".equals(account.getAlertFlag())) {
                log.warn("账户预警级别为 CRITICAL，暂停自动提现: merchantId={}", rule.getMerchantId());
                result.put("reason", "ALERT_CRITICAL");
                return result;
            }
            if (account.getAlertFlag() != null && "EMERGENCY".equals(account.getAlertFlag())) {
                log.warn("账户预警级别为 EMERGENCY，暂停自动提现: merchantId={}", rule.getMerchantId());
                result.put("reason", "ALERT_EMERGENCY");
                return result;
            }
        }

        // 4. 计算提现金额
        BigDecimal withdrawAmount = calculateWithdrawAmount(rule, balance);
        if (withdrawAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("计算提现金额为 0，跳过: merchantId={}", rule.getMerchantId());
            result.put("reason", "ZERO_AMOUNT");
            return result;
        }

        // 5. 执行提现
        String reference = "AUTO_WITHDRAW_" + LocalDate.now() + "_" + rule.getId();
        try {
            MerchantAccount updatedAccount = accountService.withdrawWithType(
                    rule.getMerchantId(), withdrawAmount, reference,
                    AccountOperationType.AUTO_WITHDRAW);

            // 更新规则的最后执行时间
            rule.setLastExecutedAt(LocalDateTime.now());
            ruleRepository.save(rule);

            result.put("executed", true);
            result.put("withdrawAmount", withdrawAmount);
            result.put("reference", reference);
            result.put("balanceAfter", updatedAccount.getBalance());

            log.info("自动提现成功: merchantId={}, amount={}, balanceAfter={}",
                    rule.getMerchantId(), withdrawAmount, updatedAccount.getBalance());

        } catch (Exception e) {
            log.error("自动提现执行失败: merchantId={}, error={}", rule.getMerchantId(), e.getMessage());
            result.put("reason", "EXECUTION_FAILED");
            result.put("error", e.getMessage());
        }

        return result;
    }

    // === 查询与禁用 ===

    /**
     * 查询商户的自动提现规则。
     *
     * @param merchantId 商户 ID
     * @return 规则列表
     */
    @Transactional(readOnly = true)
    public List<AutoWithdrawRule> getAutoWithdrawRule(Long merchantId) {
        return ruleRepository.findByMerchantId(merchantId);
    }

    /**
     * 禁用商户的自动提现规则。
     *
     * @param ruleId 规则 ID
     * @param merchantId 商户 ID（用于验证规则归属）
     * @return 禁用后的规则
     * @throws IllegalArgumentException 规则不存在或不属于该商户
     */
    @Transactional
    public AutoWithdrawRule disableAutoWithdraw(Long ruleId, Long merchantId) {
        AutoWithdrawRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("自动提现规则不存在: " + ruleId));
        if (!rule.getMerchantId().equals(merchantId)) {
            throw new IllegalArgumentException("自动提现规则不属于该商户: ruleId=" + ruleId + ", merchantId=" + merchantId);
        }
        rule.setEnabled(false);
        return ruleRepository.save(rule);
    }

    // === 内部方法 ===

    /**
     * 判断当前周期是否已执行过自动提现。
     *
     * <p>根据频率判断：</p>
     * <ul>
     *   <li>DAILY — 同一天不重复执行</li>
     *   <li>WEEKLY — 同一周不重复执行</li>
     *   <li>MONTHLY — 同一月不重复执行</li>
     * </ul>
     */
    private boolean isAlreadyExecutedThisPeriod(AutoWithdrawRule rule) {
        if (rule.getLastExecutedAt() == null) {
            return false;
        }

        LocalDateTime lastExecuted = rule.getLastExecutedAt();
        LocalDateTime now = LocalDateTime.now();

        switch (rule.getFrequency()) {
            case DAILY:
                return lastExecuted.toLocalDate().equals(now.toLocalDate());
            case WEEKLY:
                // 同一年同一周不重复执行
                return lastExecuted.toLocalDate().getYear() == now.toLocalDate().getYear()
                        && getWeekOfYear(lastExecuted.toLocalDate()) == getWeekOfYear(now.toLocalDate());
            case MONTHLY:
                return lastExecuted.getYear() == now.getYear()
                        && lastExecuted.getMonthValue() == now.getMonthValue();
            default:
                return false;
        }
    }

    /**
     * 计算实际提现金额。
     *
     * <p>逻辑：</p>
     * <ul>
     *   <li>如果 targetAmount 不为 null，提现 min(targetAmount, balance - minRetain)</li>
     *   <li>如果 targetAmount 为 null，提现 balance - minRetain（全部可提余额）</li>
     * </ul>
     */
    private BigDecimal calculateWithdrawAmount(AutoWithdrawRule rule, BigDecimal balance) {
        BigDecimal availableForWithdraw = balance.subtract(rule.getMinRetain());
        if (availableForWithdraw.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        if (rule.getTargetAmount() != null) {
            BigDecimal amount = rule.getTargetAmount().min(availableForWithdraw);
            // 不超过单次最大金额限制
            return amount.min(MAX_WITHDRAW_AMOUNT);
        }

        return availableForWithdraw.min(MAX_WITHDRAW_AMOUNT);
    }

    /**
     * 获取日期在一年中的周数（ISO 标准）。
     */
    private int getWeekOfYear(LocalDate date) {
        return date.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }
}