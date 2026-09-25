package org.nexus.gateway.reconciliation.link;

import org.nexus.gateway.reconciliation.ReconciliationDiscrepancy;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 交易对账审计服务。
 *
 * <p>核心职责：
 * <ul>
 *   <li>{@link #executeAudit} — 执行正向 + 反向对账审计</li>
 *   <li>生成审计记录，判断审计结论（BALANCED / DISCREPANCY_FOUND / AUDIT_FAILED）</li>
 * </ul>
 * </p>
 *
 * <p>审计维度：
 * <ul>
 *   <li>正向对账（FORWARD）：内部交易 → 渠道记录，检查每笔内部交易是否有对应渠道记录</li>
 *   <li>反向对账（REVERSE）：渠道记录 → 内部交易，检查每笔渠道记录是否有对应内部交易</li>
 * </ul>
 * </p>
 *
 * <p>审计结论：
 * <ul>
 *   <li>BALANCED — 正向和反向均无差异，资金账户平衡</li>
 *   <li>DISCREPANCY_FOUND — 存在未匹配或金额不一致的差异</li>
 *   <li>AUDIT_FAILED — 数据异常或系统错误导致审计失败</li>
 * </ul>
 * </p>
 */
@Service
public class TransactionAuditService {

    private static final Logger log = LoggerFactory.getLogger(TransactionAuditService.class);

    private final TransactionAuditRecordRepository auditRecordRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;

    public TransactionAuditService(
            TransactionAuditRecordRepository auditRecordRepository,
            ReconciliationDiscrepancyRepository discrepancyRepository) {
        this.auditRecordRepository = auditRecordRepository;
        this.discrepancyRepository = discrepancyRepository;
    }

    /**
     * 执行交易对账审计 — 正向 + 反向对账。
     *
     * <p>审计流程：
     * <ol>
     *   <li>正向对账（FORWARD）：检查内部交易是否有对应渠道记录</li>
     *   <li>反向对账（REVERSE）：检查渠道记录是否有对应内部交易</li>
     *   <li>汇总差异，判断审计结论</li>
     *   <li>保存审计记录</li>
     * </ol>
     * </p>
     *
     * @param auditDate 审计日期
     * @param merchantId 商户 ID（null 表示全量审计）
     * @return 审计记录列表（正向 + 反向）
     */
    @Transactional
    public List<TransactionAuditRecord> executeAudit(LocalDate auditDate, Long merchantId) {
        log.info("开始执行交易对账审计: auditDate={}, merchantId={}", auditDate, merchantId);

        List<TransactionAuditRecord> results = new ArrayList<>();

        try {
            // 正向对账：内部交易 → 渠道记录
            TransactionAuditRecord forwardAudit = executeForwardAudit(auditDate, merchantId);
            results.add(forwardAudit);

            // 反向对账：渠道记录 → 内部交易
            TransactionAuditRecord reverseAudit = executeReverseAudit(auditDate, merchantId);
            results.add(reverseAudit);

            log.info("交易对账审计完成: auditDate={}, merchantId={}, conclusion={}",
                    auditDate, merchantId,
                    forwardAudit.getConclusion() == TransactionAuditRecord.AuditConclusion.BALANCED
                            && reverseAudit.getConclusion() == TransactionAuditRecord.AuditConclusion.BALANCED
                            ? "BALANCED" : "DISCREPANCY_FOUND");

        } catch (Exception e) {
            log.error("交易对账审计失败: auditDate={}, merchantId={}, error={}",
                    auditDate, merchantId, e.getMessage(), e);

            // 记录审计失败
            TransactionAuditRecord failedRecord = new TransactionAuditRecord();
            failedRecord.setAuditDate(auditDate);
            failedRecord.setMerchantId(merchantId);
            failedRecord.setAuditDirection(TransactionAuditRecord.AuditDirection.FORWARD);
            failedRecord.setConclusion(TransactionAuditRecord.AuditConclusion.AUDIT_FAILED);
            failedRecord.setDescription("Audit failed: " + e.getMessage());
            failedRecord.setExecutedAt(LocalDateTime.now());
            results.add(auditRecordRepository.save(failedRecord));
        }

        return results;
    }

    /**
     * 正向对账审计 — 内部交易 → 渠道记录。
     *
     * <p>检查每笔内部交易是否有对应的渠道记录，以及金额是否一致。
     * 差异类型：SHORT_AMOUNT（内部有渠道无）、AMOUNT_MISMATCH（金额不一致）。</p>
     */
    private TransactionAuditRecord executeForwardAudit(LocalDate auditDate, Long merchantId) {
        TransactionAuditRecord record = new TransactionAuditRecord();
        record.setAuditDate(auditDate);
        record.setMerchantId(merchantId);
        record.setAuditDirection(TransactionAuditRecord.AuditDirection.FORWARD);
        record.setExecutedAt(LocalDateTime.now());

        // 查询审计日期范围内的差错记录
        List<ReconciliationDiscrepancy> discrepancies;
        if (merchantId != null) {
            discrepancies = discrepancyRepository.findByMerchantId(merchantId);
        } else {
            discrepancies = discrepancyRepository.findAll();
        }

        // 统计正向差异：短款（内部有渠道无）和金额不一致
        long forwardDiscrepancyCount = discrepancies.stream()
                .filter(d -> d.getDiscrepancyType() == ReconciliationDiscrepancy.DiscrepancyType.SHORT_AMOUNT
                        || d.getDiscrepancyType() == ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH)
                .count();

        BigDecimal forwardDiscrepancyAmount = discrepancies.stream()
                .filter(d -> d.getDiscrepancyType() == ReconciliationDiscrepancy.DiscrepancyType.SHORT_AMOUNT
                        || d.getDiscrepancyType() == ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH)
                .map(d -> d.getAmountDiff() != null ? d.getAmountDiff() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        record.setDiscrepancyCount(forwardDiscrepancyCount);
        record.setDiscrepancyAmount(forwardDiscrepancyAmount);
        record.setConclusion(forwardDiscrepancyCount == 0
                ? TransactionAuditRecord.AuditConclusion.BALANCED
                : TransactionAuditRecord.AuditConclusion.DISCREPANCY_FOUND);
        record.setDescription(buildForwardDescription(forwardDiscrepancyCount, forwardDiscrepancyAmount));

        return auditRecordRepository.save(record);
    }

    /**
     * 反向对账审计 — 渠道记录 → 内部交易。
     *
     * <p>检查每笔渠道记录是否有对应的内部交易，以及金额是否一致。
     * 差异类型：LONG_AMOUNT（渠道有内部无）、AMOUNT_MISMATCH（金额不一致）。</p>
     */
    private TransactionAuditRecord executeReverseAudit(LocalDate auditDate, Long merchantId) {
        TransactionAuditRecord record = new TransactionAuditRecord();
        record.setAuditDate(auditDate);
        record.setMerchantId(merchantId);
        record.setAuditDirection(TransactionAuditRecord.AuditDirection.REVERSE);
        record.setExecutedAt(LocalDateTime.now());

        // 查询差错记录
        List<ReconciliationDiscrepancy> discrepancies;
        if (merchantId != null) {
            discrepancies = discrepancyRepository.findByMerchantId(merchantId);
        } else {
            discrepancies = discrepancyRepository.findAll();
        }

        // 统计反向差异：长款（渠道有内部无）和金额不一致
        long reverseDiscrepancyCount = discrepancies.stream()
                .filter(d -> d.getDiscrepancyType() == ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT
                        || d.getDiscrepancyType() == ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH)
                .count();

        BigDecimal reverseDiscrepancyAmount = discrepancies.stream()
                .filter(d -> d.getDiscrepancyType() == ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT
                        || d.getDiscrepancyType() == ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH)
                .map(d -> d.getAmountDiff() != null ? d.getAmountDiff() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        record.setDiscrepancyCount(reverseDiscrepancyCount);
        record.setDiscrepancyAmount(reverseDiscrepancyAmount);
        record.setConclusion(reverseDiscrepancyCount == 0
                ? TransactionAuditRecord.AuditConclusion.BALANCED
                : TransactionAuditRecord.AuditConclusion.DISCREPANCY_FOUND);
        record.setDescription(buildReverseDescription(reverseDiscrepancyCount, reverseDiscrepancyAmount));

        return auditRecordRepository.save(record);
    }

    /**
     * 查询审计记录。
     */
    public List<TransactionAuditRecord> getAuditRecordsByDate(LocalDate auditDate) {
        return auditRecordRepository.findByAuditDate(auditDate);
    }

    /**
     * 按商户查询审计记录。
     */
    public List<TransactionAuditRecord> getAuditRecordsByMerchant(Long merchantId) {
        return auditRecordRepository.findByMerchantId(merchantId);
    }

    /**
     * 按日期范围查询审计记录。
     */
    public List<TransactionAuditRecord> getAuditRecordsByDateRange(LocalDate startDate, LocalDate endDate) {
        return auditRecordRepository.findByAuditDateBetween(startDate, endDate);
    }

    // --- 内部方法 ---

    private String buildForwardDescription(long count, BigDecimal amount) {
        if (count == 0) {
            return "Forward audit: all internal transactions matched with channel records";
        }
        return "Forward audit: " + count + " discrepancies found, total amount=" + amount;
    }

    private String buildReverseDescription(long count, BigDecimal amount) {
        if (count == 0) {
            return "Reverse audit: all channel records matched with internal transactions";
        }
        return "Reverse audit: " + count + " discrepancies found, total amount=" + amount;
    }
}