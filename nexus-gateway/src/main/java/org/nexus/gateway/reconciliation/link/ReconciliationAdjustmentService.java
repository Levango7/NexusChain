package org.nexus.gateway.reconciliation.link;

import org.nexus.gateway.account.AccountOperationType;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.reconciliation.ReconciliationDiffReport;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 对账差异调整服务。
 *
 * <p>核心职责：
 * <ul>
 *   <li>{@link #processDiffReport} — 处理对账差异报告，自动调整容差内差异，超出容差创建待审批记录</li>
 *   <li>{@link #approveAdjustment} — 审批通过调整记录，执行资金调整</li>
 *   <li>{@link #rejectAdjustment} — 审批拒绝调整记录</li>
 *   <li>{@link #executeAdjustment} — 执行资金调整（通过 AccountService）</li>
 * </ul>
 * </p>
 *
 * <p>金额限制规则：
 * <ul>
 *   <li>调整金额 ≤ 1000 元：AUTO_APPROVED，自动执行资金调整</li>
 *   <li>调整金额 > 1000 元：PENDING_APPROVAL，需人工审批后执行</li>
 * </ul>
 * </p>
 *
 * <p>所有资金调整通过 {@link AccountService#depositWithType} / {@link AccountService#withdrawWithType}
 * 执行，操作类型为 {@link AccountOperationType#RECON_ADJUST}。</p>
 */
@Service
public class ReconciliationAdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationAdjustmentService.class);

    /** 自动审批金额阈值（默认 1000 元） */
    @Value("${reconciliation.adjustment.auto-approve-threshold:1000}")
    private BigDecimal autoApproveThreshold;

    private final ReconciliationAdjustmentRepository adjustmentRepository;
    private final AccountService accountService;
    private final ApplicationEventPublisher eventPublisher;

    public ReconciliationAdjustmentService(
            ReconciliationAdjustmentRepository adjustmentRepository,
            AccountService accountService,
            ApplicationEventPublisher eventPublisher) {
        this.adjustmentRepository = adjustmentRepository;
        this.accountService = accountService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 处理对账差异报告 — 自动调整容差内差异，超出容差创建待审批记录。
     *
     * <p>处理逻辑：
     * <ol>
     *   <li>遍历差异报告中的所有差错记录</li>
     *   <li>根据差错类型确定调整方向（CREDIT_ADJUST / DEBIT_ADJUST）</li>
     *   <li>金额 ≤ 阈值：创建 AUTO_APPROVED 记录并立即执行资金调整</li>
     *   <li>金额 > 阈值：创建 PENDING_APPROVAL 记录，等待人工审批</li>
     *   <li>幂等检查：同一 reference 不重复创建调整记录</li>
     * </ol>
     * </p>
     *
     * @param merchantId 商户 ID
     * @param diffReport 对账差异报告
     * @param reconciliationFileId 关联的对账文件记录 ID
     * @return 创建的调整记录列表
     */
    @Transactional
    public List<ReconciliationAdjustment> processDiffReport(Long merchantId,
                                                             ReconciliationDiffReport diffReport,
                                                             Long reconciliationFileId) {
        if (diffReport == null || diffReport.getDiscrepancies() == null
                || diffReport.getDiscrepancies().isEmpty()) {
            log.info("对账差异报告无差异记录，跳过调整处理: merchantId={}", merchantId);
            return List.of();
        }

        List<ReconciliationAdjustment> adjustments = new ArrayList<>();

        // 批量构建幂等键并一次性查询已存在的 reference，避免 N+1 查询
        List<String> references = diffReport.getDiscrepancies().stream()
                .map(d -> buildReference(d, reconciliationFileId))
                .collect(Collectors.toList());
        Set<String> existingReferences = adjustmentRepository.findByReferenceIn(references).stream()
                .map(ReconciliationAdjustment::getReference)
                .collect(Collectors.toSet());

        for (ReconciliationDiscrepancy discrepancy : diffReport.getDiscrepancies()) {
            // 幂等检查：同一差错不重复创建调整记录（使用批量查询结果）
            String reference = buildReference(discrepancy, reconciliationFileId);
            if (existingReferences.contains(reference)) {
                log.info("调整记录已存在（幂等跳过）: reference={}", reference);
                continue;
            }

            // 根据差错类型确定调整方向和金额
            AdjustmentType adjustmentType = determineAdjustmentType(discrepancy);
            BigDecimal adjustmentAmount = determineAdjustmentAmount(discrepancy);

            if (adjustmentAmount == null || adjustmentAmount.compareTo(BigDecimal.ZERO) == 0) {
                log.debug("调整金额为 0，跳过: discrepancyId={}", discrepancy.getId());
                continue;
            }

            // 创建调整记录
            ReconciliationAdjustment adjustment = new ReconciliationAdjustment();
            adjustment.setMerchantId(merchantId);
            adjustment.setDiscrepancyId(discrepancy.getId());
            adjustment.setAdjustmentType(adjustmentType);
            adjustment.setAmount(adjustmentAmount);
            adjustment.setReference(reference);
            adjustment.setDescription(buildDescription(discrepancy, adjustmentType, adjustmentAmount));

            // 金额 ≤ 阈值：自动审批
            if (adjustmentAmount.compareTo(autoApproveThreshold) <= 0) {
                adjustment.setApprovalStatus(ApprovalStatus.AUTO_APPROVED);
                adjustment = adjustmentRepository.save(adjustment);
                log.info("自动审批通过调整记录: id={}, merchantId={}, amount={}, type={}",
                        adjustment.getId(), merchantId, adjustmentAmount, adjustmentType);

                // 自动审批的记录立即执行资金调整
                executeAdjustment(adjustment);
            } else {
                adjustment.setApprovalStatus(ApprovalStatus.PENDING_APPROVAL);
                adjustment = adjustmentRepository.save(adjustment);
                log.info("创建待审批调整记录: id={}, merchantId={}, amount={}, type={}",
                        adjustment.getId(), merchantId, adjustmentAmount, adjustmentType);
            }

            adjustments.add(adjustment);
        }

        return adjustments;
    }

    /**
     * 审批通过调整记录 — 人工审批通过后执行资金调整。
     *
     * <p>审批通过后：
     * <ol>
     *   <li>更新审批状态为 APPROVED</li>
     *   <li>记录审批人和审批时间</li>
     *   <li>执行资金调整（通过 AccountService）</li>
     * </ol>
     * </p>
     *
     * @param adjustmentId 调整记录 ID
     * @param approvedBy 审批人
     * @return 更新后的调整记录
     * @throws IllegalArgumentException 调整记录不存在
     * @throws IllegalStateException 调整记录状态不允许审批（非 PENDING_APPROVAL）
     */
    @Transactional
    public ReconciliationAdjustment approveAdjustment(Long adjustmentId, String approvedBy) {
        ReconciliationAdjustment adjustment = adjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Adjustment not found: " + adjustmentId));

        if (adjustment.getApprovalStatus() != ApprovalStatus.PENDING_APPROVAL) {
            throw new IllegalStateException(
                    "Cannot approve: adjustment approval status is "
                            + adjustment.getApprovalStatus() + ", expected PENDING_APPROVAL");
        }

        adjustment.setApprovalStatus(ApprovalStatus.APPROVED);
        adjustment.setApprovedBy(approvedBy);
        adjustment.setApprovedAt(LocalDateTime.now());
        adjustment = adjustmentRepository.save(adjustment);

        log.info("审批通过调整记录: id={}, approvedBy={}", adjustmentId, approvedBy);

        // 审批通过后执行资金调整
        executeAdjustment(adjustment);

        return adjustment;
    }

    /**
     * 审批拒绝调整记录。
     *
     * @param adjustmentId 调整记录 ID
     * @param rejectedBy 审批人
     * @param reason 拒绝原因
     * @return 更新后的调整记录
     * @throws IllegalArgumentException 调整记录不存在
     * @throws IllegalStateException 调整记录状态不允许审批（非 PENDING_APPROVAL）
     */
    @Transactional
    public ReconciliationAdjustment rejectAdjustment(Long adjustmentId, String rejectedBy, String reason) {
        ReconciliationAdjustment adjustment = adjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Adjustment not found: " + adjustmentId));

        if (adjustment.getApprovalStatus() != ApprovalStatus.PENDING_APPROVAL) {
            throw new IllegalStateException(
                    "Cannot reject: adjustment approval status is "
                            + adjustment.getApprovalStatus() + ", expected PENDING_APPROVAL");
        }

        adjustment.setApprovalStatus(ApprovalStatus.REJECTED);
        adjustment.setApprovedBy(rejectedBy);
        adjustment.setRejectionReason(reason);
        adjustment = adjustmentRepository.save(adjustment);

        log.info("审批拒绝调整记录: id={}, rejectedBy={}, reason={}", adjustmentId, rejectedBy, reason);

        return adjustment;
    }

    /**
     * 执行资金调整 — 通过 AccountService 执行实际的余额变更。
     *
     * <p>根据调整类型调用不同的 AccountService 方法：
     * <ul>
     *   <li>CREDIT_ADJUST → {@link AccountService#depositWithType}（余额增加）</li>
     *   <li>DEBIT_ADJUST → {@link AccountService#withdrawWithType}（余额减少）</li>
     * </ul>
     * 操作类型统一为 {@link AccountOperationType#RECON_ADJUST}。</p>
     *
     * <p>幂等保证：执行前检查 {@code executed} 标志，已执行的记录不重复执行。</p>
     *
     * @param adjustment 调整记录
     */
    @Transactional
    public void executeAdjustment(ReconciliationAdjustment adjustment) {
        // 幂等检查：已执行的记录不重复执行
        if (Boolean.TRUE.equals(adjustment.getExecuted())) {
            log.info("调整记录已执行（幂等跳过）: id={}", adjustment.getId());
            return;
        }

        String reference = "RECON_ADJ_" + adjustment.getId();

        try {
            if (adjustment.getAdjustmentType() == AdjustmentType.CREDIT_ADJUST) {
                accountService.depositWithType(
                        adjustment.getMerchantId(),
                        adjustment.getAmount(),
                        reference,
                        AccountOperationType.RECON_ADJUST);
                log.info("对账调整加钱成功: adjustmentId={}, merchantId={}, amount={}",
                        adjustment.getId(), adjustment.getMerchantId(), adjustment.getAmount());
            } else {
                accountService.withdrawWithType(
                        adjustment.getMerchantId(),
                        adjustment.getAmount(),
                        reference,
                        AccountOperationType.RECON_ADJUST);
                log.info("对账调整扣钱成功: adjustmentId={}, merchantId={}, amount={}",
                        adjustment.getId(), adjustment.getMerchantId(), adjustment.getAmount());
            }

            adjustment.setExecuted(true);
            adjustment.setExecutedAt(LocalDateTime.now());
            adjustmentRepository.save(adjustment);
        } catch (Exception e) {
            log.error("对账调整执行失败: adjustmentId={}, merchantId={}, amount={}, error={}",
                    adjustment.getId(), adjustment.getMerchantId(), adjustment.getAmount(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 查询商户的所有调整记录。
     */
    public List<ReconciliationAdjustment> getAdjustmentsByMerchant(Long merchantId) {
        return adjustmentRepository.findByMerchantId(merchantId);
    }

    /**
     * 按审批状态查询调整记录。
     */
    public List<ReconciliationAdjustment> getAdjustmentsByStatus(ApprovalStatus status) {
        return adjustmentRepository.findByApprovalStatus(status);
    }

    /**
     * 按商户 ID 和审批状态查询调整记录。
     */
    public List<ReconciliationAdjustment> getAdjustmentsByMerchantAndStatus(
            Long merchantId, ApprovalStatus status) {
        return adjustmentRepository.findByMerchantIdAndApprovalStatus(merchantId, status);
    }

    /**
     * 按关联差错记录 ID 查询调整记录。
     */
    public List<ReconciliationAdjustment> getAdjustmentsByDiscrepancyId(Long discrepancyId) {
        return adjustmentRepository.findByDiscrepancyId(discrepancyId);
    }

    /**
     * 按 ID 查找调整记录。
     */
    public Optional<ReconciliationAdjustment> findAdjustmentById(Long id) {
        return adjustmentRepository.findById(id);
    }

    // --- 内部方法 ---

    /**
     * 根据差错类型确定调整方向。
     *
     * <p>映射规则：
     * <ul>
     *   <li>LONG_AMOUNT（长款）→ CREDIT_ADJUST（渠道多收，商户加钱）</li>
     *   <li>SHORT_AMOUNT（短款）→ DEBIT_ADJUST（内部多记，商户扣钱）</li>
     *   <li>AMOUNT_MISMATCH → 根据金额差异方向判断</li>
     *   <li>STATUS_MISMATCH / INFO_MISMATCH → 不产生资金调整</li>
     * </ul>
     * </p>
     */
    private AdjustmentType determineAdjustmentType(ReconciliationDiscrepancy discrepancy) {
        switch (discrepancy.getDiscrepancyType()) {
            case LONG_AMOUNT:
                // 长款：渠道有但内部无，商户应加钱
                return AdjustmentType.CREDIT_ADJUST;
            case SHORT_AMOUNT:
                // 短款：内部有但渠道无，商户应扣钱
                return AdjustmentType.DEBIT_ADJUST;
            case AMOUNT_MISMATCH:
                // 金额不一致：渠道金额 > 内部金额 → 加钱；渠道金额 < 内部金额 → 扣钱
                if (discrepancy.getChannelAmount() != null && discrepancy.getInternalAmount() != null) {
                    if (discrepancy.getChannelAmount().compareTo(discrepancy.getInternalAmount()) > 0) {
                        return AdjustmentType.CREDIT_ADJUST;
                    } else {
                        return AdjustmentType.DEBIT_ADJUST;
                    }
                }
                return AdjustmentType.CREDIT_ADJUST;
            default:
                // STATUS_MISMATCH / INFO_MISMATCH 默认不调整，返回 CREDIT_ADJUST 但金额为 0
                return AdjustmentType.CREDIT_ADJUST;
        }
    }

    /**
     * 根据差错类型确定调整金额。
     */
    private BigDecimal determineAdjustmentAmount(ReconciliationDiscrepancy discrepancy) {
        switch (discrepancy.getDiscrepancyType()) {
            case LONG_AMOUNT:
                // 长款：调整金额 = 渠道金额
                return discrepancy.getChannelAmount() != null
                        ? discrepancy.getChannelAmount() : BigDecimal.ZERO;
            case SHORT_AMOUNT:
                // 短款：调整金额 = 内部金额
                return discrepancy.getInternalAmount() != null
                        ? discrepancy.getInternalAmount() : BigDecimal.ZERO;
            case AMOUNT_MISMATCH:
                // 金额不一致：调整金额 = 金额差异
                return discrepancy.getAmountDiff() != null
                        ? discrepancy.getAmountDiff() : BigDecimal.ZERO;
            default:
                // STATUS_MISMATCH / INFO_MISMATCH 不产生资金调整
                return BigDecimal.ZERO;
        }
    }

    /**
     * 构建幂等键（reference）。
     */
    private String buildReference(ReconciliationDiscrepancy discrepancy, Long reconciliationFileId) {
        return "RECON_DIFF_" + reconciliationFileId + "_" + discrepancy.getId();
    }

    /**
     * 构建调整描述。
     */
    private String buildDescription(ReconciliationDiscrepancy discrepancy,
                                     AdjustmentType adjustmentType, BigDecimal amount) {
        StringBuilder sb = new StringBuilder();
        sb.append("Auto-created from discrepancy #").append(discrepancy.getId());
        sb.append(" (type=").append(discrepancy.getDiscrepancyType());
        sb.append(", txn=").append(discrepancy.getTransactionId());
        sb.append(", adjustment=").append(adjustmentType);
        sb.append(", amount=").append(amount);
        sb.append(")");
        return sb.toString();
    }

    /**
     * 获取自动审批金额阈值。
     */
    public BigDecimal getAutoApproveThreshold() {
        return autoApproveThreshold;
    }

    /**
     * 设置自动审批金额阈值（主要用于测试，package-private 防止外部修改）。
     */
    void setAutoApproveThreshold(BigDecimal autoApproveThreshold) {
        this.autoApproveThreshold = autoApproveThreshold;
    }
}