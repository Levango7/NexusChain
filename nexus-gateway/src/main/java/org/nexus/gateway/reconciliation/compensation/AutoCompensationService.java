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

    @Value("${nexus.reconciliation.compensation.sandbox:true}")
    private boolean sandbox;

    private final CompensationRecordRepository compensationRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;

    public AutoCompensationService(CompensationRecordRepository compensationRepository,
                                    ReconciliationDiscrepancyRepository discrepancyRepository) {
        this.compensationRepository = compensationRepository;
        this.discrepancyRepository = discrepancyRepository;
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

            record.setStatus(CompensationRecord.CompensationStatus.SUCCESS);
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
     * 真实模式执行补偿（预留接口，当前未实现具体逻辑）。
     */
    private void executeRealCompensation(CompensationRecord record) {
        // TODO: 接入真实渠道退款接口和内部调账接口
        // 当前阶段所有环境均为 sandbox，此处不会被执行
        throw new UnsupportedOperationException("真实模式补偿执行尚未实现");
    }

    /**
     * 更新关联差错的状态。
     */
    private void updateDiscrepancyStatus(CompensationRecord record) {
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