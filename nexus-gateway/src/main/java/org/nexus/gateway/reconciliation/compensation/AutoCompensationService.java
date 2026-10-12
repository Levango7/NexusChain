package org.nexus.gateway.reconciliation.compensation;

import org.nexus.gateway.reconciliation.ReconciliationDiscrepancy;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 自动补偿服务 — 根据对账差错类型自动创建和执行补偿操作。
 *
 * <p>补偿策略：</p>
 * <ul>
 *   <li><b>LONG_AMOUNT</b>（长款：渠道有但内部无）→ 创建 REFUND 补偿（渠道退款）</li>
 *   <li><b>SHORT_AMOUNT</b>（短款：内部有但渠道无）→ 创建 INTERNAL_ADJUST 补偿（内部调账）</li>
 *   <li><b>AMOUNT_MISMATCH</b>（金额不一致）→ 创建 INTERNAL_ADJUST 补偿（差额调账）</li>
 *   <li>其他类型 → 不自动补偿，需人工处理</li>
 * </ul>
 *
 * <p>幂等保证：每个差错 ID 只能创建一条补偿记录（discrepancyId 唯一约束）。</p>
 *
 * <p>执行模式：</p>
 * <ul>
 *   <li><b>sandbox/dry-run 模式</b>：模拟执行，直接标记为 SUCCESS</li>
 *   <li><b>真实模式</b>：调用渠道退款接口或内部调账接口</li>
 * </ul>
 */
@Service
public class AutoCompensationService {

    private static final Logger log = LoggerFactory.getLogger(AutoCompensationService.class);

    @Value("${nexus.reconciliation.compensation.auto-enabled:true}")
    private boolean autoEnabled;

    /**
     * 执行模式开关。
     *
     * <p>安全修复（2026-10-12）：默认由 {@code true} 改为 {@code false}，并在
     * application.yml 显式声明。原默认 {@code true} 且未在 yml 出现 ⇒ 隐形开关：
     * 运维打开补偿后以为在真实执行，实际走 {@code simulateExecution} 并标记 SUCCESS——
     * "记录显示成功但资金未动"。需要模拟时请显式置 true（sandbox profile / 单测）。</p>
     */
    @Value("${nexus.reconciliation.compensation.sandbox:false}")
    private boolean sandbox;

    /**
     * 单笔补偿金额上限（2026-10-12 新增，默认 1000）。
     *
     * <p>与 {@code reconciliation.adjustment.auto-approve-threshold} 同口径，避免两套数字。</p>
     */
    @Value("${nexus.reconciliation.compensation.auto-max-amount:1000}")
    private BigDecimal autoMaxAmount;

    /** 每商户每日补偿笔数上限（2026-10-12 新增，默认 20）——堵"化整为零"绕开人工复核。 */
    @Value("${nexus.reconciliation.compensation.daily-max-count:20}")
    private int dailyMaxCount;

    /** 每商户每日补偿总额上限（2026-10-12 新增，默认 10000）。 */
    @Value("${nexus.reconciliation.compensation.daily-max-total:10000}")
    private BigDecimal dailyMaxTotal;

    private final CompensationRecordRepository compensationRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;

    /**
     * 商户账本服务（内部调账用；可空 = 旧装配/单测）。
     *
     * <p>P0 修复（2026-10-11）：真实模式的 INTERNAL_ADJUST 此前直接抛
     * {@code UnsupportedOperationException}（"差错 → 补偿"链路从未真正执行过）。
     * 现按 {@code ReconciliationAdjustmentService} 的**权威方向映射**调账：
     * 短款 → 扣账（DEBIT）、金额不一致 → 按差额方向。方向映射不在此处重新发明。</p>
     */
    private final org.nexus.gateway.account.AccountService accountService;

    /**
     * 渠道原路退回服务（P0 修复 2026-10-11，可空）。
     *
     * <p>长款走渠道退款的执行出口：按渠道交易号原路退回（见 {@link ChannelRefundService}）。
     * null（旧装配/单测）时真实模式拒绝执行，不静默成功。</p>
     */
    private final ChannelRefundService channelRefundService;

    /** 审计（P0 修复 2026-10-11，setter 注入）。 */
    private org.nexus.gateway.audit.AuditLogService auditLogService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuditLogService(org.nexus.gateway.audit.AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** 测试便捷构造：无账本、无渠道退款服务（真实模式将被 fail-closed 拒绝）。 */
    public AutoCompensationService(CompensationRecordRepository compensationRepository,
                                    ReconciliationDiscrepancyRepository discrepancyRepository) {
        this(compensationRepository, discrepancyRepository, null, null);
    }

    /** 测试构造：有账本、无渠道退款服务。 */
    public AutoCompensationService(CompensationRecordRepository compensationRepository,
                                    ReconciliationDiscrepancyRepository discrepancyRepository,
                                    org.nexus.gateway.account.AccountService accountService) {
        this(compensationRepository, discrepancyRepository, accountService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AutoCompensationService(CompensationRecordRepository compensationRepository,
                                    ReconciliationDiscrepancyRepository discrepancyRepository,
                                    org.nexus.gateway.account.AccountService accountService,
                                    ChannelRefundService channelRefundService) {
        this.compensationRepository = compensationRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.accountService = accountService;
        this.channelRefundService = channelRefundService;
    }

    // ==================== 自动补偿创建 ====================

    /**
     * 为单个差错自动创建补偿记录（如果适用）。
     *
     * @param discrepancy 对账差错
     * @return 创建的补偿记录，或 Optional.empty()（不适用自动补偿或已存在）
     */
    @Transactional
    public Optional<CompensationRecord> createCompensationForDiscrepancy(
            ReconciliationDiscrepancy discrepancy) {
        if (!autoEnabled) {
            log.debug("[AutoCompensation] 自动补偿未启用，跳过: discrepancyId={}", discrepancy.getId());
            return Optional.empty();
        }

        // 幂等检查
        if (compensationRepository.existsByDiscrepancyId(discrepancy.getId())) {
            log.debug("[AutoCompensation] 差错已有补偿记录，跳过: discrepancyId={}", discrepancy.getId());
            return Optional.empty();
        }

        // 判断是否适用自动补偿
        CompensationRecord.CompensationType compType = determineCompensationType(discrepancy);
        if (compType == null) {
            log.debug("[AutoCompensation] 差错类型不适用自动补偿: type={}, discrepancyId={}",
                    discrepancy.getDiscrepancyType(), discrepancy.getId());
            return Optional.empty();
        }

        // 创建补偿记录
        CompensationRecord record = new CompensationRecord();
        record.setDiscrepancyId(discrepancy.getId());
        record.setMerchantId(discrepancy.getMerchantId());
        record.setCompensationType(compType);
        record.setAmount(determineCompensationAmount(discrepancy));
        record.setTransactionId(discrepancy.getTransactionId());
        record.setStatus(CompensationRecord.CompensationStatus.PENDING);

        CompensationRecord saved = compensationRepository.save(record);
        log.info("[AutoCompensation] 创建补偿记录: id={}, discrepancyId={}, type={}, amount={}",
                saved.getId(), saved.getDiscrepancyId(), saved.getCompensationType(), saved.getAmount());

        return Optional.of(saved);
    }

    /**
     * 批量为差错列表创建补偿记录。
     *
     * @param discrepancies 差错列表
     * @return 创建的补偿记录列表
     */
    @Transactional
    public List<CompensationRecord> createCompensationsForDiscrepancies(
            List<ReconciliationDiscrepancy> discrepancies) {
        return discrepancies.stream()
                .map(this::createCompensationForDiscrepancy)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    // ==================== 人工入口（2026-10-12 新增） ====================

    /**
     * 取差错归属商户（人工入口做归属校验用）。
     *
     * <p>放在服务侧而非让控制器直接注入差错仓库：控制器构造器保持不变，
     * 既有 Web 测试无需改动。</p>
     */
    public Optional<Long> merchantIdOfDiscrepancy(Long discrepancyId) {
        if (discrepancyId == null) {
            return Optional.empty();
        }
        return discrepancyRepository.findById(discrepancyId)
                .map(ReconciliationDiscrepancy::getMerchantId);
    }

    /**
     * 人工发起补偿（按差错 ID 创建补偿记录，供运营对**长款**发起渠道原路退回）。
     *
     * <p>为什么需要它：自动创建路径无生产调用方 ⇒ 补偿能力整体不可达，长款在生产
     * 既不被调账也不被退款。本方法把人工入口接上。</p>
     *
     * <p><b>只对 LONG_AMOUNT 开放</b>：短款/金额不符已由
     * {@code ReconciliationAdjustmentService} 自动调账处理，再建补偿会对同一差错
     * 二次动账（与 #70 消除的"双向动账"同类）。</p>
     *
     * <p>上限（超限一律不建记录并转 MANUAL_REVIEW，不静默跳过）：
     * 单笔 ≤ {@code auto-max-amount}；该商户当日笔数 ≤ {@code daily-max-count}；
     * 当日总额 + 本笔 ≤ {@code daily-max-total}。</p>
     *
     * <p>刻意不复用 {@link #createCompensationForDiscrepancy}：后者受 {@code auto-enabled}
     * 开关约束，而人工入口是运营的显式动作，不应被"自动补偿开关"关掉。</p>
     *
     * @return 创建的补偿记录；不存在/非长款/已存在/超限时为空
     */
    @Transactional
    public Optional<CompensationRecord> createCompensationForDiscrepancyId(Long discrepancyId) {
        if (discrepancyId == null) {
            return Optional.empty();
        }
        ReconciliationDiscrepancy discrepancy = discrepancyRepository.findById(discrepancyId).orElse(null);
        if (discrepancy == null) {
            log.warn("[AutoCompensation] 人工入口：差错不存在 discrepancyId={}", discrepancyId);
            return Optional.empty();
        }

        if (discrepancy.getDiscrepancyType() != ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT) {
            log.info("[AutoCompensation] 人工入口仅对长款开放，跳过: type={}, discrepancyId={}",
                    discrepancy.getDiscrepancyType(), discrepancyId);
            return Optional.empty();
        }

        String rejectReason = checkCompensationCaps(discrepancy);
        if (rejectReason != null) {
            markManualReview(discrepancy, rejectReason);
            return Optional.empty();
        }

        if (compensationRepository.existsByDiscrepancyId(discrepancy.getId())) {
            log.info("[AutoCompensation] 人工入口：差错已有补偿记录，跳过 discrepancyId={}", discrepancy.getId());
            return Optional.empty();
        }

        CompensationRecord record = new CompensationRecord();
        record.setDiscrepancyId(discrepancy.getId());
        record.setMerchantId(discrepancy.getMerchantId());
        record.setCompensationType(CompensationRecord.CompensationType.REFUND);
        record.setAmount(discrepancy.getChannelAmount());
        record.setTransactionId(discrepancy.getTransactionId());
        record.setStatus(CompensationRecord.CompensationStatus.PENDING);

        CompensationRecord saved = compensationRepository.save(record);
        log.info("[AutoCompensation] 人工入口创建补偿记录: id={}, discrepancyId={}, amount={}",
                saved.getId(), saved.getDiscrepancyId(), saved.getAmount());
        return Optional.of(saved);
    }

    /**
     * 校验补偿上限。通过返回 {@code null}；不通过返回拒绝原因。
     *
     * <p>笔数/总额按**自然日**统计（{@code createdAt} 落在当日区间），
     * 复用已有的 {@code findByMerchantIdAndCreatedAtBetween}，不新增仓库方法。</p>
     *
     * <p>未配置（{@code null} 或 ≤0）的上限**不参与拦截**——避免"字段默认 0 导致全部拒绝"
     * 的隐形陷阱（非 Spring 上下文 / 显式置 0 时都会命中）。</p>
     */
    private String checkCompensationCaps(ReconciliationDiscrepancy discrepancy) {
        BigDecimal amount = discrepancy.getChannelAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return "补偿金额缺失或非正数";
        }
        if (autoMaxAmount != null && autoMaxAmount.signum() > 0
                && amount.compareTo(autoMaxAmount) > 0) {
            return "单笔金额 " + amount.toPlainString() + " 超过上限 " + autoMaxAmount.toPlainString();
        }
        if (discrepancy.getMerchantId() == null) {
            return "差错缺少商户归属";
        }

        LocalDateTime dayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        List<CompensationRecord> today = compensationRepository
                .findByMerchantIdAndCreatedAtBetween(discrepancy.getMerchantId(), dayStart, dayStart.plusDays(1));

        if (dailyMaxCount > 0 && today.size() >= dailyMaxCount) {
            return "当日补偿笔数 " + today.size() + " 已达上限 " + dailyMaxCount;
        }

        BigDecimal todayTotal = today.stream()
                .map(r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (dailyMaxTotal != null && dailyMaxTotal.signum() > 0
                && todayTotal.add(amount).compareTo(dailyMaxTotal) > 0) {
            return "当日累计 " + todayTotal.toPlainString() + " + 本笔 " + amount.toPlainString()
                    + " 超过上限 " + dailyMaxTotal.toPlainString();
        }
        return null;
    }

    /**
     * 超限转人工复核：把差错标为 {@code MANUAL_REVIEW}，避免"静默跳过"。
     *
     * <p>实体在事务内由 {@code findById} 加载，属受管状态，置字段即由脏检查落库，
     * 无需显式 save。</p>
     */
    private void markManualReview(ReconciliationDiscrepancy discrepancy, String reason) {
        log.warn("[AutoCompensation] 补偿超限，转人工复核: discrepancyId={}, reason={}",
                discrepancy.getId(), reason);
        discrepancy.setResolutionType(ReconciliationDiscrepancy.ResolutionType.MANUAL_REVIEW);
    }

    // ==================== 补偿执行 ====================

    /**
     * 执行补偿操作。
     *
     * @param compensationId 补偿记录 ID
     * @return 更新后的补偿记录
     */
    @Transactional
    public CompensationRecord executeCompensation(Long compensationId) {
        CompensationRecord record = compensationRepository.findById(compensationId)
                .orElseThrow(() -> new IllegalArgumentException("补偿记录不存在: id=" + compensationId));

        if (record.getStatus() != CompensationRecord.CompensationStatus.PENDING) {
            log.warn("[AutoCompensation] 补偿记录非 PENDING 状态，跳过执行: id={}, status={}",
                    compensationId, record.getStatus());
            return record;
        }

        record.setStatus(CompensationRecord.CompensationStatus.EXECUTING);
        compensationRepository.save(record);

        try {
            if (sandbox) {
                // sandbox 模式：模拟执行
                log.info("[AutoCompensation] sandbox 模式，模拟执行补偿: id={}", compensationId);
                simulateExecution(record);
            } else {
                // 真实模式：调用实际接口
                log.info("[AutoCompensation] 真实模式，执行补偿: id={}", compensationId);
                executeRealCompensation(record);
            }

            // 2026-10-12：模拟执行不得记 SUCCESS——否则数据无法区分"真补偿"与"假补偿"。
            // SIMULATED 会被补偿统计与差错闭环自然排除（二者都只认 SUCCESS）。
            record.setStatus(sandbox
                    ? CompensationRecord.CompensationStatus.SIMULATED
                    : CompensationRecord.CompensationStatus.SUCCESS);
            record.setExecutedAt(LocalDateTime.now());
        } catch (Exception e) {
            log.error("[AutoCompensation] 补偿执行失败: id={}, error={}", compensationId, e.getMessage(), e);
            record.setStatus(CompensationRecord.CompensationStatus.FAILED);
            record.setFailureReason(e.getMessage());
        }

        CompensationRecord saved = compensationRepository.save(record);

        // 更新关联的差错状态
        updateDiscrepancyStatus(record);

        return saved;
    }

    /**
     * 批量执行所有 PENDING 状态的补偿记录。
     *
     * @return 执行结果列表
     */
    @Transactional
    public List<CompensationRecord> executeAllPending() {
        List<CompensationRecord> pendingRecords = compensationRepository.findAll().stream()
                .filter(r -> r.getStatus() == CompensationRecord.CompensationStatus.PENDING)
                .toList();

        log.info("[AutoCompensation] 批量执行 {} 条 PENDING 补偿记录", pendingRecords.size());

        return pendingRecords.stream()
                .map(r -> executeCompensation(r.getId()))
                .toList();
    }

    /**
     * 批量执行指定商户的 PENDING 状态补偿记录。
     *
     * <p>P0-3：只执行当前认证商户的补偿，防止跨商户操作。</p>
     *
     * @param merchantId 商户 ID
     * @return 执行结果列表
     */
    @Transactional
    public List<CompensationRecord> executeAllPendingByMerchant(Long merchantId) {
        List<CompensationRecord> pendingRecords = compensationRepository
                .findByMerchantIdAndStatus(merchantId, CompensationRecord.CompensationStatus.PENDING);

        log.info("[AutoCompensation] 批量执行商户 {} 的 {} 条 PENDING 补偿记录",
                merchantId, pendingRecords.size());

        return pendingRecords.stream()
                .map(r -> executeCompensation(r.getId()))
                .toList();
    }

    // ==================== 查询 ====================

    /**
     * 查询补偿记录。
     */
    public Optional<CompensationRecord> getCompensation(Long id) {
        return compensationRepository.findById(id);
    }

    /**
     * 按差错 ID 查询补偿记录。
     */
    public Optional<CompensationRecord> getCompensationByDiscrepancy(Long discrepancyId) {
        return compensationRepository.findByDiscrepancyId(discrepancyId);
    }

    /**
     * 查询商户的所有补偿记录。
     */
    public List<CompensationRecord> getCompensationsByMerchant(Long merchantId) {
        return compensationRepository.findByMerchantId(merchantId);
    }

    // ==================== 内部方法 ====================

    /**
     * 根据差错类型判断补偿类型。
     */
    private CompensationRecord.CompensationType determineCompensationType(
            ReconciliationDiscrepancy discrepancy) {
        switch (discrepancy.getDiscrepancyType()) {
            case LONG_AMOUNT:
                // 长款：渠道有但内部无 → 渠道退款
                return CompensationRecord.CompensationType.REFUND;
            case SHORT_AMOUNT:
                // 短款：内部有但渠道无 → 内部调账
                return CompensationRecord.CompensationType.INTERNAL_ADJUST;
            case AMOUNT_MISMATCH:
                // 金额不一致 → 内部调账（差额）
                return CompensationRecord.CompensationType.INTERNAL_ADJUST;
            default:
                // STATUS_MISMATCH, TIME_MISMATCH, INFO_MISMATCH → 不自动补偿
                return null;
        }
    }

    /**
     * 确定补偿金额。
     */
    private BigDecimal determineCompensationAmount(ReconciliationDiscrepancy discrepancy) {
        switch (discrepancy.getDiscrepancyType()) {
            case LONG_AMOUNT:
                // 长款：退还渠道侧金额
                return discrepancy.getChannelAmount();
            case SHORT_AMOUNT:
                // 短款：补足内部侧金额
                return discrepancy.getInternalAmount();
            case AMOUNT_MISMATCH:
                // 金额不一致：补偿差额
                return discrepancy.getAmountDiff();
            default:
                return BigDecimal.ZERO;
        }
    }

    /**
     * sandbox 模式模拟执行。
     */
    private void simulateExecution(CompensationRecord record) {
        if (record.getCompensationType() == CompensationRecord.CompensationType.REFUND) {
            // 模拟渠道退款引用
            record.setChannelRefundRef("SIMULATED_REFUND_" + record.getId());
        } else {
            // 模拟内部调账引用
            record.setAccountTransactionRef("SIMULATED_ADJ_" + record.getId());
        }
    }

    /**
     * 真实模式执行补偿（P0 修复 2026-10-11）。
     *
     * <h3>INTERNAL_ADJUST（内部调账）—— 真实实现</h3>
     * <p>按 {@code ReconciliationAdjustmentService} 的**权威方向映射**调商户账本
     * （该服务是自动对账路径的执行者，方向语义以它为准，此处不重新发明）：</p>
     * <ul>
     *   <li>SHORT_AMOUNT（短款：内部多记）→ 扣账（{@code withdrawWithType}）</li>
     *   <li>AMOUNT_MISMATCH → 渠道金额 &gt; 内部金额 → 加钱；否则扣钱</li>
     *   <li>其余（含 LONG_AMOUNT）→ 加钱（长款=渠道多收，商户应加钱）</li>
     * </ul>
     * <p>金额沿用 {@link #determineCompensationAmount}（与调账服务的金额口径一致）；
     * 操作类型 {@code RECON_ADJUST}，reference {@code AUTO_COMP_<补偿记录ID>}（可追溯、可幂等）。</p>
     *
     * <h3>REFUND（渠道退款）—— 显式拒绝，防重复动账</h3>
     * <p>{@code LONG_AMOUNT} 在本服务被映射为 REFUND，但同一类型在自动对账路径
     * （{@code ReconciliationAdjustmentService}）已被映射为 <b>CREDIT_ADJUST（商户加钱）</b>
     * —— 两者语义相反。若此处再走"渠道退款"，同一差错会被**双向动账**。
     * 因此本路径拒绝执行并给出明确原因（落 FAILED，可人工核对），
     * 渠道退款的真实实现需先由业务拍板"长款究竟加钱还是退款"。</p>
     *
     * <h3>fail-closed</h3>
     * <p>账本服务未注入（旧装配/单测）→ 拒绝执行，不静默成功。</p>
     */
    private void executeRealCompensation(CompensationRecord record) {
        if (record.getCompensationType() == CompensationRecord.CompensationType.REFUND) {
            // 业务决策（2026-10-11 已拍板）：长款走**渠道退款**——平台是中间机构，
            // 非自有资金应原路退回，而非计入商户余额。
            // 3/3 步：按"渠道归属 + 渠道交易号"真实发起原路退回
            // （互斥已在 1/3 步完成：ReconciliationAdjustmentService 不再对 LONG_AMOUNT 做
            //  CREDIT_ADJUST，不会与这里双向动账）。
            //
            // fail-closed 边界：渠道缺失 / 交易号缺失 / 渠道不支持 / 接口失败 → 异常上抛，
            // 由 executeCompensation 捕获置 FAILED 并记录原因（绝不伪造成功）。
            if (channelRefundService == null) {
                throw new IllegalStateException(
                        "长款渠道退款拒绝执行：ChannelRefundService 未注入（fail-closed，不静默成功）");
            }
            ReconciliationDiscrepancy discrepancyRefund = discrepancyRepository
                    .findById(record.getDiscrepancyId())
                    .orElseThrow(() -> new IllegalStateException(
                            "长款渠道退款拒绝执行：关联差错不存在 discrepancyId="
                                    + record.getDiscrepancyId()));

            // 幂等键：同一补偿记录派生同一商户退款单号 → 补偿可重跑不会重复退
            String outRefundNo = "AUTO_REFUND_" + record.getId();
            String refundRef = channelRefundService.refundByChannelTransaction(
                    discrepancyRefund.getChannelType(),
                    discrepancyRefund.getTransactionId(),
                    discrepancyRefund.getChannelAmount(),
                    record.getAmount(),
                    outRefundNo);
            record.setChannelRefundRef(refundRef);
            log.info("[AutoCompensation] 长款渠道退款成功: recordId={}, channel={}, channelTxId={}, refundRef={}",
                    record.getId(), discrepancyRefund.getChannelType(),
                    discrepancyRefund.getTransactionId(), refundRef);
            if (auditLogService != null) {
                try {
                    auditLogService.recordChannelRefund(record.getMerchantId(),
                            discrepancyRefund.getChannelType(),
                            discrepancyRefund.getTransactionId(),
                            record.getAmount() == null ? null : record.getAmount().toPlainString(),
                            refundRef);
                } catch (RuntimeException ignored) {
                    // 审计失败不阻断资金操作
                }
            }
            return;
        }

        if (accountService == null) {
            throw new IllegalStateException(
                    "内部调账补偿拒绝执行：AccountService 未注入（fail-closed，不静默成功）");
        }

        ReconciliationDiscrepancy discrepancy = discrepancyRepository
                .findById(record.getDiscrepancyId())
                .orElseThrow(() -> new IllegalStateException(
                        "内部调账补偿拒绝执行：关联差错不存在 discrepancyId=" + record.getDiscrepancyId()));

        BigDecimal amount = record.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException(
                    "内部调账补偿拒绝执行：金额非法 amount=" + amount);
        }

        String reference = "AUTO_COMP_" + record.getId();
        if (shouldCredit(discrepancy)) {
            accountService.depositWithType(record.getMerchantId(), amount, reference,
                    org.nexus.gateway.account.AccountOperationType.RECON_ADJUST);
            log.info("[AutoCompensation] 真实调账（加钱）成功: recordId={}, merchantId={}, amount={}",
                    record.getId(), record.getMerchantId(), amount);
        } else {
            accountService.withdrawWithType(record.getMerchantId(), amount, reference,
                    org.nexus.gateway.account.AccountOperationType.RECON_ADJUST);
            log.info("[AutoCompensation] 真实调账（扣钱）成功: recordId={}, merchantId={}, amount={}",
                    record.getId(), record.getMerchantId(), amount);
        }
        record.setAccountTransactionRef(reference);
    }

    /**
     * 方向判定 —— 与 {@code ReconciliationAdjustmentService#determineAdjustmentType} 严格同款。
     *
     * <p>短款 → 扣账；金额不一致 → 渠道 &gt; 内部则加钱、否则扣账；其余（长款等）→ 加钱。</p>
     */
    private boolean shouldCredit(ReconciliationDiscrepancy discrepancy) {
        switch (discrepancy.getDiscrepancyType()) {
            case SHORT_AMOUNT:
                return false;
            case AMOUNT_MISMATCH:
                if (discrepancy.getChannelAmount() != null && discrepancy.getInternalAmount() != null) {
                    return discrepancy.getChannelAmount().compareTo(discrepancy.getInternalAmount()) > 0;
                }
                return true;
            default:
                return true;
        }
    }

    /**
     * 更新关联差错的状态。
     */
    private void updateDiscrepancyStatus(CompensationRecord record) {
        // 只认 SUCCESS（真实执行）：SIMULATED 表示资金未动，**不得**把差错闭环——
        // 否则一次模拟运行就会把真实差错标成"已处理"，掩盖未补偿的商户。
        if (record.getStatus() == CompensationRecord.CompensationStatus.SUCCESS) {
            discrepancyRepository.findById(record.getDiscrepancyId()).ifPresent(d -> {
                d.setStatus(ReconciliationDiscrepancy.DiscrepancyStatus.RESOLVED);
                d.setResolvedAt(LocalDateTime.now());
                d.setResolutionNote("Auto-compensated: " + record.getCompensationType()
                        + ", amount=" + record.getAmount());
                discrepancyRepository.save(d);
                log.info("[AutoCompensation] 差错已标记为 RESOLVED: discrepancyId={}", d.getId());
            });
        }
    }
}