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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
 *   <li>{@link #executeAutoWithdraw} — 执行自动提现（三段式协议，批次⑤ P0 加固 2026-10-18：
 *       ① 独立事务认领 + 扣账并<b>先提交</b>；② 出款调用发生在<b>事务外</b>；
 *       ③ 独立事务落最终结果——成功推进 lastExecutedAt + 记凭据 + 清认领，
 *       失败补偿入账 + 清认领；落库/补偿失败则认领保留、下轮拒跑并告警）。
 *       原实现把出款调用放在 DB 事务里：出款成功后的 commit 失败会连扣账一起回滚，
 *       重跑即二次出款。</li>
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

    /**
     * 出款认领未决（进行中或需人工核对）时写入日志/审计前部的稳定标记 — 供监控精确匹配。
     * 出现即表示该规则有一笔<b>已扣账</b>的出款结果未知：认领保留期间拒绝重复执行（fail-closed），
     * 人工核对链上/渠道实际结果后方可处置。
     */
    public static final String PAYOUT_CLAIM_UNRESOLVED_MARKER = "AUTO_WITHDRAW_PAYOUT_UNRESOLVED_MANUAL_REQUIRED";

    private final AutoWithdrawRuleRepository ruleRepository;
    private final AccountService accountService;
    private final MerchantAccountRepository accountRepository;
    private final MerchantPayoutAccountService payoutAccountService;
    private final PayoutExecutor payoutExecutor;

    /**
     * 执行协议各阶段的编程式事务模板（批次⑤ P0 加固，2026-10-18）。
     * 认领段 / 成功落库段 / 补偿段各自独立提交；出款调用位于这些事务之外。
     */
    private final TransactionTemplate transactionTemplate;

    /** 审计（P0 修复 2026-10-11，setter 注入）：出款是资金动作。 */
    private org.nexus.gateway.audit.AuditLogService auditLogService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuditLogService(org.nexus.gateway.audit.AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** 写出款审计（旁路，失败不影响业务流程）。 */
    private void audit(String action, Long merchantId, String detail) {
        if (auditLogService == null) {
            return;
        }
        try {
            auditLogService.recordPayout(merchantId, action, detail);
        } catch (RuntimeException e) {
            log.warn("Payout audit write failed (ignored): action={}, error={}", action, e.getMessage());
        }
    }

    /**
     * 唯一构造器（2026-10-11：删除 3 参兼容重载；2026-10-18 批次⑤：事务管理器入构）。
     *
     * <p>该类曾同时存在 3 参与 5 参两个构造器：即便 5 参上有 @Autowired，Spring 6
     * 在部分情形下仍选 3 参（依赖目标类型尚未就绪时）→ 出款接线静默失效；
     * 而"多构造器无 @Autowired"的形态更会让上下文直接起不来
     * （CI k6 冒烟三连红的根因：No default constructor found）。
     * <b>单构造器类对 Spring 是最 robust 的形态</b>；出款依赖（账户服务 + 执行器）
     * 是必须项，不应有"可缺省"的重载。</p>
     *
     * <p>批次⑤ P0 加固把出款调用移出 DB 事务，需要编程式事务划分独立提交的
     * 认领 / 落库 / 补偿段——事务管理器为此以第 6 个构造参数注入（保持单构造器）。</p>
     */
    public AutoWithdrawService(AutoWithdrawRuleRepository ruleRepository,
                                AccountService accountService,
                                MerchantAccountRepository accountRepository,
                                MerchantPayoutAccountService payoutAccountService,
                                PayoutExecutor payoutExecutor,
                                PlatformTransactionManager transactionManager) {
        this.ruleRepository = ruleRepository;
        this.accountService = accountService;
        this.accountRepository = accountRepository;
        this.payoutAccountService = payoutAccountService;
        this.payoutExecutor = payoutExecutor;
        this.transactionTemplate = transactionManager != null
                ? new TransactionTemplate(transactionManager) : null;
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
     * 执行自动提现 — 根据规则触发自动提现操作（三段式协议，批次⑤ P0 加固，2026-10-18）。
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>幂等控制：快速预检当前周期是否已执行过（权威判定在认领事务内对加锁行重做）</li>
     *   <li>余额检查：查询商户可用余额，判断是否达到触发阈值</li>
     *   <li>风控评估：检查账户状态和预警标志</li>
     *   <li><b>认领（独立事务，先提交）</b>：加锁规则行 → 复检周期与未决认领 →
     *       置 payout_claim_reference + 扣账（同一事务原子提交）</li>
     *   <li><b>出款（事务外）</b>：调用 PayoutExecutor；不可逆的外部动作不待在 DB 事务内</li>
     *   <li><b>结果落库（独立事务）</b>：成功 → lastExecutedAt + 凭据 + 清认领；
     *       失败 → 补偿入账 + 清认领（规则不推进，下周期可重试）；落库或补偿失败 →
     *       认领保留，下轮拒跑并告警（宁滞留待人工核对，勿自动重试——避免二次出款）</li>
     * </ol>
     *
     * @param rule 自动提现规则
     * @return 执行结果（包含是否执行、提现金额、流水编号等）
     */
    public Map<String, Object> executeAutoWithdraw(AutoWithdrawRule rule) {
        if (transactionTemplate == null) {
            // fail-closed：执行协议必须有能力独立提交认领段，否则会退化为
            // "事务内调外部动作"的旧形态（二次出款风险）。
            throw new IllegalStateException(
                    "transaction support is not configured; auto-withdraw execution refused (fail-closed)");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleId", rule.getId());
        result.put("merchantId", rule.getMerchantId());
        result.put("executed", false);

        // 1. 幂等控制（快速预检，无锁）：权威判定在认领事务内对加锁行重做
        if (isAlreadyExecutedThisPeriod(rule)) {
            log.info("自动提现已在本周期执行过，跳过: merchantId={}, ruleId={}",
                    rule.getMerchantId(), rule.getId());
            result.put("reason", "ALREADY_EXECUTED_THIS_PERIOD");
            return result;
        }

        // 1b. 未决认领快速预检（权威判定同样在认领事务内）：出现即代表有一笔已扣账、
        //     结果未知的出款——必须先于余额/去向检查拒跑（即使在挂起期间商户改过去向），
        //     且告警不受"没有生效出款账户"等后续检查遮蔽。
        if (rule.getPayoutClaimReference() != null) {
            log.warn("{} 存在未决出款认领（快速预检），拒绝重复执行: merchantId={}, ruleId={}, "
                            + "claimReference={}, claimAt={}",
                    PAYOUT_CLAIM_UNRESOLVED_MARKER, rule.getMerchantId(), rule.getId(),
                    rule.getPayoutClaimReference(), rule.getPayoutClaimAt());
            result.put("reason", "PAYOUT_CLAIM_ACTIVE");
            result.put("claimReference", rule.getPayoutClaimReference());
            result.put("claimAt", rule.getPayoutClaimAt());
            audit("AUTO_WITHDRAW_PAYOUT_CLAIM_ACTIVE", rule.getMerchantId(),
                    "claimReference=" + rule.getPayoutClaimReference()
                            + ", claimAt=" + rule.getPayoutClaimAt());
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

        // 5. 出款去向解析（P0 修复 2026-10-11：没有生效账户 → **不扣账**，fail-closed）
        // 注：出款去向不得使用平台方个人账户（二清红线）——账户绑定侧的护栏见
        // MerchantPayoutAccountService。
        String reference = "AUTO_WITHDRAW_" + LocalDate.now() + "_" + rule.getId();

        MerchantPayoutAccount payoutAccount = null;
        if (payoutAccountService != null) {
            payoutAccount = payoutAccountService.resolveActiveAccount(rule.getMerchantId())
                    .orElse(null);
            if (payoutAccount == null) {
                log.warn("自动提现跳过：商户无生效出款账户（不扣账，fail-closed）: merchantId={}",
                        rule.getMerchantId());
                result.put("reason", "NO_PAYOUT_ACCOUNT");
                audit("AUTO_WITHDRAW_NO_PAYOUT_ACCOUNT", rule.getMerchantId(),
                        "amount=" + withdrawAmount.toPlainString());
                return result;
            }
        }

        // 6. 认领（独立事务，先提交）：加锁规则行 → 复检周期与未决认领 → 置认领 + 扣账（原子）。
        // 批次⑤ P0 加固（2026-10-18）：原实现把"扣账 + 出款调用 + 规则推进"放在同一 DB 事务里——
        // 出款是事务内不可逆的外部动作，出款成功后的 commit 失败会把扣账与规则状态一起回滚，
        // 下个周期重跑即二次出款。现改为认领先独立提交：认领在库即代表"这笔已扣账的出款未决"，
        // 任何重入（并发入口 / 崩溃后重启 / 重试）都在此闸门被拒。
        ClaimOutcome claim;
        try {
            claim = transactionTemplate.execute(status -> {
                AutoWithdrawRule locked = ruleRepository.findByIdForUpdate(rule.getId())
                        .orElseThrow(() -> new IllegalStateException("自动提现规则不存在: " + rule.getId()));
                if (isAlreadyExecutedThisPeriod(locked)) {
                    return ClaimOutcome.ofAlreadyExecuted();
                }
                if (locked.getPayoutClaimReference() != null) {
                    return ClaimOutcome.ofClaimActive(locked.getPayoutClaimReference(),
                            locked.getPayoutClaimAt());
                }
                locked.setPayoutClaimReference(reference);
                locked.setPayoutClaimAt(LocalDateTime.now());
                MerchantAccount updated = accountService.withdrawWithType(
                        locked.getMerchantId(), withdrawAmount, reference,
                        AccountOperationType.AUTO_WITHDRAW);
                ruleRepository.save(locked);
                return ClaimOutcome.ofClaimed(updated);
            });
        } catch (Exception e) {
            // 认领 / 扣账失败 → 事务整体回滚（认领与扣账都不留），行为与旧实现一致
            log.error("自动提现执行失败（认领/扣账阶段，已回滚）: merchantId={}, error={}",
                    rule.getMerchantId(), e.getMessage());
            result.put("reason", "EXECUTION_FAILED");
            result.put("error", e.getMessage());
            return result;
        }

        if (claim.alreadyExecuted()) {
            log.info("自动提现已在本周期执行过（认领复检），跳过: merchantId={}, ruleId={}",
                    rule.getMerchantId(), rule.getId());
            result.put("reason", "ALREADY_EXECUTED_THIS_PERIOD");
            return result;
        }

        if (claim.claimActive()) {
            // 未决认领：可能正在出款中（并发入口），也可能挂起（崩溃 / 落库失败，需人工核对）。
            // fail-closed：拒绝重复执行，只告警——宁滞留待人工核对，勿自动重试（二次出款）。
            log.warn("{} 存在未决出款认领，拒绝重复执行: merchantId={}, ruleId={}, claimReference={}, claimAt={}",
                    PAYOUT_CLAIM_UNRESOLVED_MARKER, rule.getMerchantId(), rule.getId(),
                    claim.activeClaimReference(), claim.activeClaimAt());
            result.put("reason", "PAYOUT_CLAIM_ACTIVE");
            result.put("claimReference", claim.activeClaimReference());
            result.put("claimAt", claim.activeClaimAt());
            audit("AUTO_WITHDRAW_PAYOUT_CLAIM_ACTIVE", rule.getMerchantId(),
                    "claimReference=" + claim.activeClaimReference()
                            + ", claimAt=" + claim.activeClaimAt());
            return result;
        }

        // 7. 出款（事务外）：不可逆的外部动作不得待在 DB 事务内。
        String payoutRef = null;
        if (payoutExecutor != null) {
            try {
                payoutRef = payoutExecutor.pay(rule.getMerchantId(), payoutAccount,
                        withdrawAmount, reference);
            } catch (RuntimeException e) {
                // 干净失败（按执行器契约：抛异常 = 未出款）：补偿退账 + 清认领（独立事务）。
                // 规则不推进（下周期可重试）；补偿失败 → 认领保留，下轮拒跑并告警。
                boolean compensated = compensatePayoutFailure(rule.getId(), rule.getMerchantId(),
                        withdrawAmount, reference);
                log.error("自动提现已扣账但出款失败{}: merchantId={}, amount={}, error={}",
                        compensated ? "，已退回余额" : "，且补偿未完成（认领保留，需人工核对）",
                        rule.getMerchantId(), withdrawAmount, e.getMessage());
                result.put("reason", "PAYOUT_FAILED_REFUNDED");
                result.put("refunded", compensated);
                result.put("error", e.getMessage());
                audit("AUTO_WITHDRAW_PAYOUT_FAILED_REFUNDED", rule.getMerchantId(),
                        "amount=" + withdrawAmount.toPlainString() + ", refunded=" + compensated);
                return result;
            }
        }

        // 8. 落库（独立事务）：推进 lastExecutedAt + 记凭据 + 清认领；失败 → 认领保留并告警。
        if (!finalizePayoutSuccess(rule.getId(), payoutRef)) {
            result.put("reason", "PAYOUT_DONE_FINALIZE_FAILED");
            result.put("payoutRef", payoutRef);
            result.put("error", "出款已成功，但结果落库失败；认领保留等待人工核对");
            return result;
        }

        result.put("executed", true);
        result.put("withdrawAmount", withdrawAmount);
        result.put("reference", reference);
        result.put("balanceAfter", claim.updatedAccount().getBalance());
        result.put("payoutRef", payoutRef);
        result.put("payoutAccountType",
                payoutAccount != null ? payoutAccount.getAccountType().name() : null);

        log.info("自动提现成功: merchantId={}, amount={}, balanceAfter={}, payoutRef={}",
                rule.getMerchantId(), withdrawAmount, claim.updatedAccount().getBalance(), payoutRef);
        audit("AUTO_WITHDRAW_EXECUTED", rule.getMerchantId(),
                "amount=" + withdrawAmount.toPlainString() + ", payoutRef=" + payoutRef);

        return result;
    }

    /**
     * 出款成功后的落库（独立事务）：推进 lastExecutedAt + 记录出款凭据 + 清认领。
     *
     * @return true=落库成功；false=落库失败（认领保留，下轮拒跑并告警，需人工核对）
     */
    private boolean finalizePayoutSuccess(Long ruleId, String payoutRef) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                AutoWithdrawRule locked = ruleRepository.findByIdForUpdate(ruleId)
                        .orElseThrow(() -> new IllegalStateException("自动提现规则不存在: " + ruleId));
                locked.setLastExecutedAt(LocalDateTime.now());
                locked.setLastPayoutRef(payoutRef);
                locked.setPayoutClaimReference(null);
                locked.setPayoutClaimAt(null);
                ruleRepository.save(locked);
            });
            return true;
        } catch (RuntimeException e) {
            log.error("CRITICAL: {} 出款已成功但结果落库失败（认领保留，需人工核对后处置）: ruleId={}, payoutRef={}",
                    PAYOUT_CLAIM_UNRESOLVED_MARKER, ruleId, payoutRef, e);
            return false;
        }
    }

    /**
     * 出款失败后的补偿（独立事务）：退回已扣余额 + 清认领；规则不推进（下周期可重试）。
     *
     * @return true=补偿成功；false=补偿失败（认领保留，下轮拒跑并告警，需人工核对）
     */
    private boolean compensatePayoutFailure(Long ruleId, Long merchantId,
                                            BigDecimal amount, String reference) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                accountService.depositWithType(merchantId, amount,
                        "AUTO_WITHDRAW_ROLLBACK_" + reference,
                        AccountOperationType.RECON_ADJUST);
                AutoWithdrawRule locked = ruleRepository.findByIdForUpdate(ruleId)
                        .orElseThrow(() -> new IllegalStateException("自动提现规则不存在: " + ruleId));
                locked.setPayoutClaimReference(null);
                locked.setPayoutClaimAt(null);
                ruleRepository.save(locked);
            });
            return true;
        } catch (RuntimeException e) {
            log.error("CRITICAL: {} 出款失败且补偿未完成（认领保留，需人工核对后处置）: ruleId={}, merchantId={}, reference={}",
                    PAYOUT_CLAIM_UNRESOLVED_MARKER, ruleId, merchantId, reference, e);
            return false;
        }
    }

    /** 认领阶段（事务内）的产出：已执行过 / 有未决认领 / 认领成功。 */
    private record ClaimOutcome(boolean alreadyExecuted, boolean claimActive,
                                String activeClaimReference, LocalDateTime activeClaimAt,
                                MerchantAccount updatedAccount) {

        static ClaimOutcome ofAlreadyExecuted() {
            return new ClaimOutcome(true, false, null, null, null);
        }

        static ClaimOutcome ofClaimActive(String claimReference, LocalDateTime claimAt) {
            return new ClaimOutcome(false, true, claimReference, claimAt, null);
        }

        static ClaimOutcome ofClaimed(MerchantAccount updatedAccount) {
            return new ClaimOutcome(false, false, null, null, updatedAccount);
        }
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