package org.nexus.gateway.reconciliation.link;

import org.nexus.gateway.account.AccountOperationType;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.reconciliation.ReconciliationDiffReport;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancy;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancyRepository;
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

    /**
     * 日上限闸是否生效（2026-11-08 新增）。
     *
     * <p><b>默认 false</b>：三档判据照常计算、打日志、累加指标，但**不改变任何行为**
     * （该自动审批的照常自动审批并动账）。只有显式置 true 才会把超限单转
     * {@code PENDING_APPROVAL} 且不调用 {@code executeAdjustment}。
     * 本次上线只做观测，因此默认 false 是硬要求。</p>
     */
    @Value("${reconciliation.adjustment.daily-limits.enforce:false}")
    private boolean enforceDailyLimits;

    /**
     * 每商户当日 {@code AUTO_APPROVED} 笔数上限（默认 5）。
     *
     * <p>≤0 视为未配置、不参与拦截——与补偿侧同口径，避免"默认 0 导致全部拒绝"。</p>
     */
    @Value("${reconciliation.adjustment.daily-max-count:5}")
    private int dailyMaxCount;

    /** 每商户当日 {@code AUTO_APPROVED} 累计金额上限（默认 3000）。≤0 / null 视为未配置。 */
    @Value("${reconciliation.adjustment.daily-max-total:3000}")
    private BigDecimal dailyMaxTotal;

    /** 超限观测指标名（enforce=false 期间也照常累加，供切 true 前建立基线）。 */
    private static final String OVER_LIMIT_COUNTER = "nexus_reconciliation_adjustment_over_limit_total";

    /** 观测指标注册表（可空：非 Spring 上下文 / 单测未注入时静默降级）。 */
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMeterRegistry(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private final ReconciliationAdjustmentRepository adjustmentRepository;
    private final AccountService accountService;
    private final ApplicationEventPublisher eventPublisher;

    /** 审计（P0 修复 2026-10-11，setter 注入）：调账是资金动作，必须可追查。 */
    private org.nexus.gateway.audit.AuditLogService auditLogService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuditLogService(org.nexus.gateway.audit.AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 差错仓库（P1-3 修复 2026-10-12）。
     *
     * <p>用 setter 注入而非构造器参数：不破坏既有构造器签名与单测。</p>
     */
    private ReconciliationDiscrepancyRepository discrepancyRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDiscrepancyRepository(ReconciliationDiscrepancyRepository discrepancyRepository) {
        this.discrepancyRepository = discrepancyRepository;
    }

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

            // 业务决策（2026-10-11）：长款走**渠道退款** —— 平台是中间机构，非自有资金
            // 应原路退回，而不是计入商户余额。故本服务**不再**对 LONG_AMOUNT 做
            // CREDIT_ADJUST（旧行为：商户加钱）：否则与补偿侧的渠道退款路径
            // （AutoCompensationService 的 REFUND 类型）会对同一差错**双向动账**。
            // 长款的唯一处理入口 = 渠道退款（落地中）；在此期间两侧都不动账（fail-closed）。
            if (discrepancy.getDiscrepancyType() == ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT) {
                log.info("长款(LONG_AMOUNT)改由渠道退款路径处理，本服务跳过（避免双向动账）: discrepancyId={}",
                        discrepancy.getId());
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
                // 新增日上限判据（2026-11-08）：笔数 / 当日累计。判定与观测恒执行；
                // 是否拦截只看 enforceDailyLimits（默认 false ⇒ 行为与改动前完全一致）。
                DailyLimitCheck limitCheck = checkDailyAutoApproveLimits(merchantId, adjustmentAmount);

                if (limitCheck.overLimit() && enforceDailyLimits) {
                    // 超限且闸已开 → 复用既有 PENDING_APPROVAL，不调用 executeAdjustment。
                    adjustment.setApprovalStatus(ApprovalStatus.PENDING_APPROVAL);
                    adjustment = adjustmentRepository.save(adjustment);
                    log.warn("调账日上限超限，转人工审批（enforce=true）: id={}, merchantId={}, amount={}, "
                            + "当日 AUTO_APPROVED 笔数={}, 当日累计={}{}",
                            adjustment.getId(), merchantId, adjustmentAmount,
                            limitCheck.dayCount(), limitCheck.dayTotal().toPlainString(),
                            limitCheck.queryFailed() ? "（查询失败，按超限 fail-closed）" : "");
                } else {
                    adjustment.setApprovalStatus(ApprovalStatus.AUTO_APPROVED);
                    adjustment = adjustmentRepository.save(adjustment);
                    log.info("自动审批通过调整记录: id={}, merchantId={}, amount={}, type={}",
                            adjustment.getId(), merchantId, adjustmentAmount, adjustmentType);

                    // 自动审批的记录立即执行资金调整
                    executeAdjustment(adjustment);
                }
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

            // P1-3 修复（2026-10-12）：动账后把关联差错置 RESOLVED，否则差错永停 DISCOVERED。
            // 刻意放在同一事务内：回写失败则连同资金调整一起回滚——宁可整体重试，
            // 也不留「钱动了、差错仍显示未处理」的不一致状态。
            markDiscrepancyResolved(adjustment);

            // 审计（P0 修复 2026-10-11）：资金调整必须可追查——哪笔差错、往哪个方向、多少钱
            if (auditLogService != null) {
                try {
                    auditLogService.recordAdjust(adjustment.getMerchantId(), reference,
                            adjustment.getAdjustmentType() == AdjustmentType.CREDIT_ADJUST
                                    ? "RECON_ADJUST_CREDIT" : "RECON_ADJUST_DEBIT",
                            adjustment.getAmount() == null ? null : adjustment.getAmount().toPlainString());
                } catch (RuntimeException e) {
                    log.warn("Recon adjust audit write failed (ignored): id={}, error={}",
                            adjustment.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("对账调整执行失败: adjustmentId={}, merchantId={}, amount={}, error={}",
                    adjustment.getId(), adjustment.getMerchantId(), adjustment.getAmount(), e.getMessage(), e);
            if (auditLogService != null) {
                try {
                    auditLogService.recordAdjust(adjustment.getMerchantId(), reference,
                            "RECON_ADJUST_FAILED", null);
                } catch (RuntimeException ignored) {
                    // 审计失败绝不影响原异常抛出
                }
            }
            throw e;
        }
    }

    /**
     * 把关联差错置为 {@code RESOLVED}（P1-3 修复 2026-10-12）。
     *
     * <p>语义与 {@code AutoCompensationService.updateDiscrepancyStatus} 保持一致
     * （同样的目标状态、时间戳与 resolutionNote 风格），使两条处理路径在差错台账上
     * 呈现一致。已是 RESOLVED 的差错不重复写，避免覆盖人工填写的 resolutionNote。</p>
     *
     * <p>未注入 {@code discrepancyRepository} 时跳过（保持既有单测可直接构造）。</p>
     */
    private void markDiscrepancyResolved(ReconciliationAdjustment adjustment) {
        if (discrepancyRepository == null || adjustment.getDiscrepancyId() == null) {
            return;
        }
        discrepancyRepository.findById(adjustment.getDiscrepancyId()).ifPresent(d -> {
            if (d.getStatus() == ReconciliationDiscrepancy.DiscrepancyStatus.RESOLVED) {
                return;
            }
            d.setStatus(ReconciliationDiscrepancy.DiscrepancyStatus.RESOLVED);
            d.setResolvedAt(LocalDateTime.now());
            d.setResolutionNote("Auto-adjusted: " + adjustment.getAdjustmentType()
                    + ", amount=" + adjustment.getAmount());
            discrepancyRepository.save(d);
            log.info("差错已标记为 RESOLVED（自动调账闭环）: discrepancyId={}", d.getId());
        });
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
     * 计算并记录"该商户当日自动调账"日上限判据（2026-11-08 新增）。
     *
     * <p>口径与补偿侧一致：
     * <ul>
     *   <li>只统计 {@code approval_status = AUTO_APPROVED}；PENDING_APPROVAL / APPROVED / REJECTED 不占额度；</li>
     *   <li>自然日区间 {@code [今日 00:00, 明日 00:00)}，左闭右开；</li>
     *   <li>查询异常按超限处理（fail-closed）——<b>有意选择</b>：宁可转人工，也不在"算不清额度"时自动动账。</li>
     * </ul>
     * </p>
     *
     * <p>本方法只负责判定与观测；是否拦截由调用方按 {@link #enforceDailyLimits} 决定。
     * 注意这是<b>近似闸</b>：计数与写入之间存在竞态（两个事务可能同时读到"未超限"）。</p>
     */
    private DailyLimitCheck checkDailyAutoApproveLimits(Long merchantId, BigDecimal currentAmount) {
        LocalDateTime dayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        List<ReconciliationAdjustment> today;
        try {
            today = adjustmentRepository.findByMerchantIdAndApprovalStatusAndCreatedAtBetween(
                    merchantId, ApprovalStatus.AUTO_APPROVED, dayStart, dayStart.plusDays(1));
        } catch (RuntimeException e) {
            log.error("对账自动调账日上限查询失败，按超限处理（fail-closed，有意选择：宁转人工不自动动账）: "
                            + "merchantId={}, 本笔金额={}, error={}",
                    merchantId, currentAmount, e.getMessage(), e);
            return new DailyLimitCheck(0, BigDecimal.ZERO, false, false, true);
        }

        int dayCount = today == null ? 0 : today.size();
        BigDecimal dayTotal = today == null ? BigDecimal.ZERO : today.stream()
                .map(r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean countOver = dailyMaxCount > 0 && dayCount >= dailyMaxCount;
        boolean totalOver = dailyMaxTotal != null && dailyMaxTotal.signum() > 0
                && dayTotal.add(currentAmount).compareTo(dailyMaxTotal) > 0;

        if (countOver || totalOver) {
            // 观测要求：直接回答"若开闸，哪些商户会被拦、拦在哪一条"。
            log.warn("对账自动调账日上限命中（enforce={}，{}）: merchantId={}, 本笔金额={}, "
                            + "当日 AUTO_APPROVED 笔数={}（上限 {}）, 当日累计={} + 本笔={}（上限 {}）, 命中=[{}]",
                    enforceDailyLimits,
                    enforceDailyLimits ? "转 PENDING_APPROVAL" : "行为不变，仅观测",
                    merchantId, currentAmount,
                    dayCount, dailyMaxCount,
                    dayTotal.toPlainString(), dayTotal.add(currentAmount).toPlainString(),
                    dailyMaxTotal == null ? "未配置" : dailyMaxTotal.toPlainString(),
                    describeReasons(countOver, totalOver));
            // enforce=false 期间也累加，切 true 前即有基线。
            if (countOver) {
                incrementOverLimit("count");
            }
            if (totalOver) {
                incrementOverLimit("total");
            }
        }

        return new DailyLimitCheck(dayCount, dayTotal, countOver, totalOver, false);
    }

    private String describeReasons(boolean countOver, boolean totalOver) {
        if (countOver && totalOver) {
            return "count,total";
        }
        return countOver ? "count" : "total";
    }

    /** 指标累加：MeterRegistry 未注入或注册失败时静默降级，绝不影响资金主流程。 */
    private void incrementOverLimit(String reason) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(OVER_LIMIT_COUNTER, "reason", reason).increment();
        } catch (RuntimeException e) {
            log.debug("调账日上限指标累加失败（静默降级）: reason={}, error={}", reason, e.getMessage());
        }
    }

    /** 日上限判据结果（内部值对象，不对外暴露）。 */
    private record DailyLimitCheck(int dayCount, BigDecimal dayTotal,
                                   boolean countOver, boolean totalOver, boolean queryFailed) {
        boolean overLimit() {
            return queryFailed || countOver || totalOver;
        }
    }

    /**
     * 根据差错类型确定调整方向。
     *
     * <p>映射规则：
     * <ul>
     *   <li>LONG_AMOUNT（长款）→ <b>本服务已跳过</b>（2026-10-11 决策：长款走渠道退款，
     *       不再给商户加钱，避免与补偿侧双向动账；见 {@code processDiffReport} 中的跳过分支）</li>
     *   <li>SHORT_AMOUNT（短款）→ DEBIT_ADJUST（内部多记，商户扣钱）</li>
     *   <li>AMOUNT_MISMATCH → 根据金额差异方向判断</li>
     *   <li>STATUS_MISMATCH / INFO_MISMATCH → 不产生资金调整</li>
     * </ul>
     * </p>
     */
    private AdjustmentType determineAdjustmentType(ReconciliationDiscrepancy discrepancy) {
        switch (discrepancy.getDiscrepancyType()) {
            case LONG_AMOUNT:
                // 防御性分支：正常流程中 LONG_AMOUNT 已在 processDiffReport 被跳过
                // （2026-10-11 决策：长款走渠道退款）。此处保留 CREDIT_ADJUST 仅作兜底，
                // 且由于上游跳过，实际不会被执行。
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

    /** 日上限闸开关（测试用）。 */
    void setEnforceDailyLimits(boolean enforceDailyLimits) {
        this.enforceDailyLimits = enforceDailyLimits;
    }

    /** 当日自动调账笔数上限（测试用）。 */
    void setDailyMaxCount(int dailyMaxCount) {
        this.dailyMaxCount = dailyMaxCount;
    }

    /** 当日自动调账累计金额上限（测试用）。 */
    void setDailyMaxTotal(BigDecimal dailyMaxTotal) {
        this.dailyMaxTotal = dailyMaxTotal;
    }
}