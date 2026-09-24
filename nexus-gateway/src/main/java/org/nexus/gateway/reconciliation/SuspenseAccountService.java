package org.nexus.gateway.reconciliation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 挂账资金服务。
 *
 * <p>负责挂账记录的自动创建、查询、核销和注销。挂账生命周期：
 * <ul>
 *   <li>{@code autoCreateSuspenseAccount}：对账发现差错时自动创建挂账记录</li>
 *   <li>{@code resolveSuspenseAccount}：核销挂账（差错资金已追回或已对平）</li>
 *   <li>{@code writeOffSuspenseAccount}：注销挂账（无法追回的资金，经审批后注销）</li>
 * </ul>
 * </p>
 *
 * <p>挂账状态流转：{@code PENDING → RESOLVED / WRITTEN_OFF}</p>
 */
@Service
public class SuspenseAccountService {

    private final SuspenseAccountRepository suspenseAccountRepository;

    public SuspenseAccountService(
            SuspenseAccountRepository suspenseAccountRepository) {
        this.suspenseAccountRepository = suspenseAccountRepository;
    }

    /**
     * 对账发现差错时自动创建挂账记录。
     *
     * <p>根据差错类型映射挂账类型：
     * <ul>
     *   <li>LONG_AMOUNT → LONG_PAYMENT</li>
     *   <li>SHORT_AMOUNT → SHORT_PAYMENT</li>
     *   <li>AMOUNT_MISMATCH → AMOUNT_MISMATCH</li>
     *   <li>STATUS_MISMATCH → STATUS_MISMATCH</li>
     *   <li>INFO_MISMATCH → STATUS_MISMATCH（信息不一致归入状态不一致）</li>
     * </ul>
     * </p>
     *
     * @param discrepancy 对账差错记录
     * @return 创建的挂账记录
     */
    @Transactional
    public SuspenseAccount autoCreateSuspenseAccount(ReconciliationDiscrepancy discrepancy) {
        SuspenseAccount account = new SuspenseAccount();
        account.setDiscrepancyId(discrepancy.getId());
        account.setMerchantId(discrepancy.getMerchantId());
        account.setAmount(discrepancy.getAmountDiff() != null
                ? discrepancy.getAmountDiff()
                : BigDecimal.ZERO);
        account.setDiscrepancyType(mapDiscrepancyType(discrepancy.getDiscrepancyType()));
        account.setStatus(SuspenseAccount.SuspenseStatus.PENDING);
        account.setDescription(buildDescription(discrepancy));

        return suspenseAccountRepository.save(account);
    }

    /**
     * 核销挂账（差错资金已追回或已对平）。
     *
     * <p>状态流转：PENDING → RESOLVED</p>
     *
     * @param id 挂账记录 ID
     * @param resolutionNote 核销备注
     * @return 更新后的挂账记录
     * @throws IllegalArgumentException 挂账记录不存在
     * @throws IllegalStateException 挂账状态不允许核销（非 PENDING）
     */
    @Transactional
    public SuspenseAccount resolveSuspenseAccount(Long id, String resolutionNote) {
        SuspenseAccount account = findSuspenseAccountById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Suspense account not found: " + id));

        if (account.getStatus() != SuspenseAccount.SuspenseStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot resolve: suspense account status is "
                            + account.getStatus() + ", expected PENDING");
        }

        account.setStatus(SuspenseAccount.SuspenseStatus.RESOLVED);
        account.setResolutionNote(resolutionNote);
        account.setResolvedAt(LocalDateTime.now());

        return suspenseAccountRepository.save(account);
    }

    /**
     * 注销挂账（无法追回的资金，经审批后注销）。
     *
     * <p>状态流转：PENDING → WRITTEN_OFF</p>
     *
     * @param id 挂账记录 ID
     * @param reason 注销原因
     * @return 更新后的挂账记录
     * @throws IllegalArgumentException 挂账记录不存在
     * @throws IllegalStateException 挂账状态不允许注销（非 PENDING）
     */
    @Transactional
    public SuspenseAccount writeOffSuspenseAccount(Long id, String reason) {
        SuspenseAccount account = findSuspenseAccountById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Suspense account not found: " + id));

        if (account.getStatus() != SuspenseAccount.SuspenseStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot write off: suspense account status is "
                            + account.getStatus() + ", expected PENDING");
        }

        account.setStatus(SuspenseAccount.SuspenseStatus.WRITTEN_OFF);
        account.setResolutionNote(reason);
        account.setResolvedAt(LocalDateTime.now());

        return suspenseAccountRepository.save(account);
    }

    /**
     * 按商户 ID 查询挂账记录。
     */
    public List<SuspenseAccount> findByMerchantId(Long merchantId) {
        return suspenseAccountRepository.findByMerchantId(merchantId);
    }

    /**
     * 按挂账状态查询。
     */
    public List<SuspenseAccount> findByStatus(SuspenseAccount.SuspenseStatus status) {
        return suspenseAccountRepository.findByStatus(status);
    }

    /**
     * 按差错类型查询。
     */
    public List<SuspenseAccount> findByDiscrepancyType(
            SuspenseAccount.DiscrepancyType discrepancyType) {
        return suspenseAccountRepository.findByDiscrepancyType(discrepancyType);
    }

    /**
     * 按商户 ID 和挂账状态查询。
     */
    public List<SuspenseAccount> findByMerchantIdAndStatus(
            Long merchantId, SuspenseAccount.SuspenseStatus status) {
        return suspenseAccountRepository.findByMerchantIdAndStatus(merchantId, status);
    }

    /**
     * 按商户 ID 和差错类型查询。
     */
    public List<SuspenseAccount> findByMerchantIdAndDiscrepancyType(
            Long merchantId, SuspenseAccount.DiscrepancyType discrepancyType) {
        return suspenseAccountRepository.findByMerchantIdAndDiscrepancyType(
                merchantId, discrepancyType);
    }

    /**
     * 按关联差错记录 ID 查询挂账。
     */
    public List<SuspenseAccount> findByDiscrepancyId(Long discrepancyId) {
        return suspenseAccountRepository.findByDiscrepancyId(discrepancyId);
    }

    /**
     * 按 ID 查找挂账记录。
     */
    public Optional<SuspenseAccount> findSuspenseAccountById(Long id) {
        return suspenseAccountRepository.findById(id);
    }

    // --- 内部方法 ---

    /**
     * 将对账差错类型映射为挂账差错类型。
     */
    private SuspenseAccount.DiscrepancyType mapDiscrepancyType(
            ReconciliationDiscrepancy.DiscrepancyType type) {
        switch (type) {
            case LONG_AMOUNT:
                return SuspenseAccount.DiscrepancyType.LONG_PAYMENT;
            case SHORT_AMOUNT:
                return SuspenseAccount.DiscrepancyType.SHORT_PAYMENT;
            case AMOUNT_MISMATCH:
                return SuspenseAccount.DiscrepancyType.AMOUNT_MISMATCH;
            case STATUS_MISMATCH:
            case INFO_MISMATCH:
                return SuspenseAccount.DiscrepancyType.STATUS_MISMATCH;
            default:
                return SuspenseAccount.DiscrepancyType.STATUS_MISMATCH;
        }
    }

    /**
     * 根据差错记录构建挂账描述。
     */
    private String buildDescription(ReconciliationDiscrepancy discrepancy) {
        StringBuilder sb = new StringBuilder();
        sb.append("Auto-created from discrepancy #").append(discrepancy.getId());
        sb.append(" (type=").append(discrepancy.getDiscrepancyType());
        sb.append(", txn=").append(discrepancy.getTransactionId());
        if (discrepancy.getAmountDiff() != null) {
            sb.append(", diff=").append(discrepancy.getAmountDiff());
        }
        sb.append(")");
        return sb.toString();
    }
}