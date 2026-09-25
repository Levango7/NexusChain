package org.nexus.gateway.reconciliation.link;

import org.nexus.gateway.account.AccountOperationType;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.reconciliation.SuspenseAccount;
import org.nexus.gateway.reconciliation.SuspenseAccountRepository;
import org.nexus.gateway.reconciliation.SuspenseAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 挂账核销服务 — 联动资金账户调整。
 *
 * <p>核心职责：
 * <ul>
 *   <li>{@link #autoWriteoff} — 自动核销：金额 ≤ 阈值时自动核销挂账并调整资金</li>
 *   <li>{@link #manualWriteoff} — 人工核销：需审批后核销挂账并调整资金</li>
 * </ul>
 * </p>
 *
 * <p>核销规则：
 * <ul>
 *   <li>自动核销阈值默认 500 元，金额 ≤ 阈值的挂账可自动核销</li>
 *   <li>自动核销在同一事务中完成资金调整和挂账状态变更</li>
 *   <li>人工核销需先提交方案、审批通过后执行</li>
 * </ul>
 * </p>
 *
 * <p>资金调整通过 {@link AccountService#depositWithType} / {@link AccountService#withdrawWithType}
 * 执行，操作类型为 {@link AccountOperationType#SUSPENSE_WRITEOFF}。</p>
 *
 * <p>幂等保证：通过挂账 ID 作为 reference 的幂等键，确保同一挂账不重复核销。
 * 同时检查挂账状态，非 PENDING 状态的挂账拒绝核销。</p>
 */
@Service
public class SuspenseWriteoffService {

    private static final Logger log = LoggerFactory.getLogger(SuspenseWriteoffService.class);

    /** 自动核销金额阈值（默认 500 元） */
    @Value("${reconciliation.suspense.auto-writeoff-threshold:500}")
    private BigDecimal autoWriteoffThreshold;

    private final SuspenseAccountService suspenseAccountService;
    private final SuspenseAccountRepository suspenseAccountRepository;
    private final AccountService accountService;

    public SuspenseWriteoffService(
            SuspenseAccountService suspenseAccountService,
            SuspenseAccountRepository suspenseAccountRepository,
            AccountService accountService) {
        this.suspenseAccountService = suspenseAccountService;
        this.suspenseAccountRepository = suspenseAccountRepository;
        this.accountService = accountService;
    }

    /**
     * 自动核销挂账 — 金额 ≤ 阈值时在同一事务中完成资金调整和挂账状态变更。
     *
     * <p>处理逻辑：
     * <ol>
     *   <li>幂等检查：挂账状态必须为 PENDING</li>
     *   <li>金额检查：挂账金额 ≤ 自动核销阈值</li>
     *   <li>根据挂账类型确定资金调整方向</li>
     *   <li>在同一事务中执行资金调整 + 更新挂账状态为 RESOLVED</li>
     * </ol>
     * </p>
     *
     * @param suspenseAccountId 挂账记录 ID
     * @return 核销后的挂账记录
     * @throws IllegalArgumentException 挂账记录不存在
     * @throws IllegalStateException 挂账状态不允许核销（非 PENDING）或金额超出自动核销阈值
     */
    @Transactional
    public SuspenseAccount autoWriteoff(Long suspenseAccountId) {
        SuspenseAccount account = suspenseAccountService.findSuspenseAccountById(suspenseAccountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Suspense account not found: " + suspenseAccountId));

        // 幂等检查：非 PENDING 状态拒绝核销
        if (account.getStatus() != SuspenseAccount.SuspenseStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot auto-writeoff: suspense account status is "
                            + account.getStatus() + ", expected PENDING");
        }

        // 金额检查：超出阈值不允许自动核销
        if (account.getAmount().compareTo(autoWriteoffThreshold) > 0) {
            throw new IllegalStateException(
                    "Cannot auto-writeoff: amount " + account.getAmount()
                            + " exceeds threshold " + autoWriteoffThreshold);
        }

        // 执行资金调整（在同一事务中）
        executeWriteoffAdjustment(account);

        // 更新挂账状态为 RESOLVED
        account.setStatus(SuspenseAccount.SuspenseStatus.RESOLVED);
        account.setResolutionNote("Auto-writeoff: amount within threshold " + autoWriteoffThreshold);
        account.setResolvedAt(LocalDateTime.now());
        account.setResolvedBy("SYSTEM");

        account = suspenseAccountRepository.save(account);
        log.info("挂账自动核销完成: id={}, amount={}", suspenseAccountId, account.getAmount());

        return account;
    }

    /**
     * 人工核销挂账 — 审批通过后执行资金调整和挂账状态变更。
     *
     * <p>处理逻辑：
     * <ol>
     *   <li>幂等检查：挂账状态必须为 PENDING</li>
     *   <li>在同一事务中执行资金调整 + 更新挂账状态为 RESOLVED</li>
     * </ol>
     * </p>
     *
     * <p>与自动核销的区别：人工核销不检查金额阈值，适用于超过自动核销阈值的挂账。
     * 调用方需在审批通过后调用此方法。</p>
     *
     * @param suspenseAccountId 挂账记录 ID
     * @param approvedBy 审批人
     * @param resolutionNote 核销备注
     * @return 核销后的挂账记录
     * @throws IllegalArgumentException 挂账记录不存在
     * @throws IllegalStateException 挂账状态不允许核销（非 PENDING）
     */
    @Transactional
    public SuspenseAccount manualWriteoff(Long suspenseAccountId, String approvedBy, String resolutionNote) {
        SuspenseAccount account = suspenseAccountService.findSuspenseAccountById(suspenseAccountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Suspense account not found: " + suspenseAccountId));

        // 幂等检查：非 PENDING 状态拒绝核销
        if (account.getStatus() != SuspenseAccount.SuspenseStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot manual-writeoff: suspense account status is "
                            + account.getStatus() + ", expected PENDING");
        }

        // 执行资金调整（在同一事务中）
        executeWriteoffAdjustment(account);

        // 更新挂账状态为 RESOLVED
        account.setStatus(SuspenseAccount.SuspenseStatus.RESOLVED);
        account.setResolutionNote("Manual-writeoff by " + approvedBy + ": " + resolutionNote);
        account.setResolvedAt(LocalDateTime.now());
        account.setResolvedBy(approvedBy);

        account = suspenseAccountRepository.save(account);
        log.info("挂账人工核销完成: id={}, amount={}, approvedBy={}",
                suspenseAccountId, account.getAmount(), approvedBy);

        return account;
    }

    /**
     * 执行挂账核销的资金调整 — 在同一事务中调用 AccountService。
     *
     * <p>根据挂账类型确定资金调整方向：
     * <ul>
     *   <li>LONG_PAYMENT（长款）→ depositWithType（商户加钱）</li>
     *   <li>SHORT_PAYMENT（短款）→ withdrawWithType（商户扣钱）</li>
     *   <li>AMOUNT_MISMATCH → 根据差异方向判断</li>
     *   <li>STATUS_MISMATCH → 不产生资金调整</li>
     * </ul>
     * </p>
     *
     * <p>幂等保证：使用挂账 ID 作为 reference 的一部分，确保同一挂账不重复调整。
     * AccountService 内部也有基于 reference 的幂等检查（通过 AccountTransaction）。</p>
     *
     * @param account 挂账记录
     */
    private void executeWriteoffAdjustment(SuspenseAccount account) {
        String reference = "SUSPENSE_WO_" + account.getId();

        // 金额为 0 时跳过资金调整
        if (account.getAmount() == null || account.getAmount().compareTo(BigDecimal.ZERO) == 0) {
            log.info("挂账金额为 0，跳过资金调整: suspenseAccountId={}", account.getId());
            return;
        }

        boolean isCredit = isCreditAdjustment(account);

        try {
            if (isCredit) {
                accountService.depositWithType(
                        account.getMerchantId(),
                        account.getAmount(),
                        reference,
                        AccountOperationType.SUSPENSE_WRITEOFF);
                log.info("挂账核销加钱成功: suspenseAccountId={}, merchantId={}, amount={}",
                        account.getId(), account.getMerchantId(), account.getAmount());
            } else {
                accountService.withdrawWithType(
                        account.getMerchantId(),
                        account.getAmount(),
                        reference,
                        AccountOperationType.SUSPENSE_WRITEOFF);
                log.info("挂账核销扣钱成功: suspenseAccountId={}, merchantId={}, amount={}",
                        account.getId(), account.getMerchantId(), account.getAmount());
            }
        } catch (Exception e) {
            log.error("挂账核销资金调整失败: suspenseAccountId={}, merchantId={}, amount={}, error={}",
                    account.getId(), account.getMerchantId(), account.getAmount(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 根据挂账类型判断是否为加钱调整。
     *
     * <p>映射规则：
     * <ul>
     *   <li>LONG_PAYMENT（长款）→ 加钱（渠道多收，商户应入账）</li>
     *   <li>SHORT_PAYMENT（短款）→ 扣钱（内部多记，商户应出账）</li>
     *   <li>AMOUNT_MISMATCH → 加钱（默认加钱方向）</li>
     *   <li>STATUS_MISMATCH → 不调整（金额为 0 时已跳过）</li>
     * </ul>
     * </p>
     */
    private boolean isCreditAdjustment(SuspenseAccount account) {
        switch (account.getDiscrepancyType()) {
            case LONG_PAYMENT:
                return true;
            case SHORT_PAYMENT:
                return false;
            case AMOUNT_MISMATCH:
                return true;
            case STATUS_MISMATCH:
            default:
                return true;
        }
    }

    /**
     * 查询所有可自动核销的挂账（PENDING 状态且金额 ≤ 阈值）。
     */
    public List<SuspenseAccount> findAutoWriteoffCandidates() {
        List<SuspenseAccount> pendingAccounts =
                suspenseAccountService.findByStatus(SuspenseAccount.SuspenseStatus.PENDING);
        return pendingAccounts.stream()
                .filter(a -> a.getAmount().compareTo(autoWriteoffThreshold) <= 0)
                .toList();
    }

    /**
     * 获取自动核销金额阈值。
     */
    public BigDecimal getAutoWriteoffThreshold() {
        return autoWriteoffThreshold;
    }

    /**
     * 设置自动核销金额阈值（主要用于测试）。
     */
    public void setAutoWriteoffThreshold(BigDecimal autoWriteoffThreshold) {
        this.autoWriteoffThreshold = autoWriteoffThreshold;
    }
}