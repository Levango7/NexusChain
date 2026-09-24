package org.nexus.gateway.reconciliation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 差错处理服务。
 *
 * <p>负责对账差错的分类处置、状态流转和持久化管理。处置规则：
 * <ul>
 *   <li>{@code AUTO_RESOLVE}：金额容差范围内自动解决</li>
 *   <li>{@code MANUAL_REVIEW}：需要人工审核的差错</li>
 *   <li>{@code PENDING_INVESTIGATION}：挂账待查</li>
 * </ul>
 * </p>
 *
 * <p>差错状态流转：{@code DISCOVERED → INVESTIGATING → RESOLVED/ESCALATED}</p>
 */
@Service
public class DiscrepancyResolutionService {

    private final ReconciliationDiscrepancyRepository discrepancyRepository;

    /** 金额容差（默认 0.01 元），容差范围内的差错可自动解决 */
    @Value("${reconciliation.amount.tolerance:0.01}")
    private BigDecimal amountTolerance;

    public DiscrepancyResolutionService(
            ReconciliationDiscrepancyRepository discrepancyRepository) {
        this.discrepancyRepository = discrepancyRepository;
    }

    /**
     * 保存差错记录列表（对账引擎发现差异后批量持久化）。
     *
     * @param discrepancies 差错记录列表
     * @return 保存后的差错记录列表
     */
    @Transactional
    public List<ReconciliationDiscrepancy> saveDiscrepancies(
            List<ReconciliationDiscrepancy> discrepancies) {
        if (discrepancies == null || discrepancies.isEmpty()) {
            return List.of();
        }
        return discrepancyRepository.saveAll(discrepancies);
    }

    /**
     * 自动处置差错：对容差范围内的差错自动标记为已解决。
     *
     * <p>处置规则：
     * <ul>
     *   <li>AMOUNT_MISMATCH 且金额差异 ≤ 容差 → AUTO_RESOLVE + RESOLVED</li>
     *   <li>LONG_AMOUNT / SHORT_AMOUNT → PENDING_INVESTIGATION（挂账待查）</li>
     *   <li>STATUS_MISMATCH / INFO_MISMATCH → MANUAL_REVIEW</li>
     * </ul>
     * </p>
     *
     * @param discrepancy 待处置的差错记录
     * @return 处置后的差错记录
     */
    @Transactional
    public ReconciliationDiscrepancy autoResolve(ReconciliationDiscrepancy discrepancy) {
        if (discrepancy.getStatus() != ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED) {
            return discrepancy;
        }

        switch (discrepancy.getDiscrepancyType()) {
            case AMOUNT_MISMATCH:
                if (discrepancy.getAmountDiff() != null
                        && discrepancy.getAmountDiff().compareTo(amountTolerance) <= 0) {
                    discrepancy.setResolutionType(
                            ReconciliationDiscrepancy.ResolutionType.AUTO_RESOLVE);
                    discrepancy.setStatus(ReconciliationDiscrepancy.DiscrepancyStatus.RESOLVED);
                    discrepancy.setResolutionNote(
                            "Auto-resolved: amount diff " + discrepancy.getAmountDiff()
                                    + " within tolerance " + amountTolerance);
                    discrepancy.setResolvedAt(LocalDateTime.now());
                } else {
                    discrepancy.setResolutionType(
                            ReconciliationDiscrepancy.ResolutionType.MANUAL_REVIEW);
                }
                break;

            case STATUS_MISMATCH:
            case INFO_MISMATCH:
                discrepancy.setResolutionType(
                        ReconciliationDiscrepancy.ResolutionType.MANUAL_REVIEW);
                break;

            case LONG_AMOUNT:
            case SHORT_AMOUNT:
                discrepancy.setResolutionType(
                        ReconciliationDiscrepancy.ResolutionType.PENDING_INVESTIGATION);
                break;

            default:
                discrepancy.setResolutionType(
                        ReconciliationDiscrepancy.ResolutionType.MANUAL_REVIEW);
                break;
        }

        return discrepancyRepository.save(discrepancy);
    }

    /**
     * 批量自动处置差错。
     *
     * @param discrepancies 差错记录列表
     * @return 处置后的差错记录列表
     */
    @Transactional
    public List<ReconciliationDiscrepancy> autoResolveBatch(
            List<ReconciliationDiscrepancy> discrepancies) {
        if (discrepancies == null || discrepancies.isEmpty()) {
            return List.of();
        }
        List<ReconciliationDiscrepancy> resolved = discrepancies.stream()
                .map(this::autoResolve)
                .collect(java.util.stream.Collectors.toList());
        return resolved;
    }

    /**
     * 开始调查差错（状态流转：DISCOVERED → INVESTIGATING）。
     *
     * @param discrepancyId 差错记录 ID
     * @return 更新后的差错记录
     * @throws IllegalStateException 如果差错状态不允许此流转
     */
    @Transactional
    public ReconciliationDiscrepancy startInvestigation(Long discrepancyId) {
        ReconciliationDiscrepancy discrepancy = findDiscrepancyById(discrepancyId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Discrepancy not found: " + discrepancyId));

        if (discrepancy.getStatus() != ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED) {
            throw new IllegalStateException(
                    "Cannot start investigation: discrepancy status is "
                            + discrepancy.getStatus() + ", expected DISCOVERED");
        }

        discrepancy.setStatus(ReconciliationDiscrepancy.DiscrepancyStatus.INVESTIGATING);
        return discrepancyRepository.save(discrepancy);
    }

    /**
     * 解决差错（状态流转：INVESTIGATING → RESOLVED）。
     *
     * @param discrepancyId 差错记录 ID
     * @param resolutionNote 解决备注
     * @return 更新后的差错记录
     * @throws IllegalStateException 如果差错状态不允许此流转
     */
    @Transactional
    public ReconciliationDiscrepancy resolveDiscrepancy(Long discrepancyId, String resolutionNote) {
        ReconciliationDiscrepancy discrepancy = findDiscrepancyById(discrepancyId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Discrepancy not found: " + discrepancyId));

        if (discrepancy.getStatus() != ReconciliationDiscrepancy.DiscrepancyStatus.INVESTIGATING
                && discrepancy.getStatus() != ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED) {
            throw new IllegalStateException(
                    "Cannot resolve: discrepancy status is "
                            + discrepancy.getStatus() + ", expected DISCOVERED or INVESTIGATING");
        }

        discrepancy.setStatus(ReconciliationDiscrepancy.DiscrepancyStatus.RESOLVED);
        discrepancy.setResolutionNote(resolutionNote);
        discrepancy.setResolvedAt(LocalDateTime.now());
        return discrepancyRepository.save(discrepancy);
    }

    /**
     * 升级差错（状态流转：INVESTIGATING → ESCALATED）。
     *
     * <p>升级意味着差错需要上级介入处理，超出当前处理人权限。</p>
     *
     * @param discrepancyId 差错记录 ID
     * @param escalationNote 升级备注
     * @return 更新后的差错记录
     * @throws IllegalStateException 如果差错状态不允许此流转
     */
    @Transactional
    public ReconciliationDiscrepancy escalateDiscrepancy(Long discrepancyId, String escalationNote) {
        ReconciliationDiscrepancy discrepancy = findDiscrepancyById(discrepancyId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Discrepancy not found: " + discrepancyId));

        if (discrepancy.getStatus() != ReconciliationDiscrepancy.DiscrepancyStatus.INVESTIGATING
                && discrepancy.getStatus() != ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED) {
            throw new IllegalStateException(
                    "Cannot escalate: discrepancy status is "
                            + discrepancy.getStatus() + ", expected DISCOVERED or INVESTIGATING");
        }

        discrepancy.setStatus(ReconciliationDiscrepancy.DiscrepancyStatus.ESCALATED);
        discrepancy.setResolutionNote(escalationNote);
        return discrepancyRepository.save(discrepancy);
    }

    /**
     * 查询商户的所有差错记录。
     */
    public List<ReconciliationDiscrepancy> getDiscrepanciesByMerchant(Long merchantId) {
        return discrepancyRepository.findByMerchantId(merchantId);
    }

    /**
     * 按状态查询商户的差错记录。
     */
    public List<ReconciliationDiscrepancy> getDiscrepanciesByMerchantAndStatus(
            Long merchantId, ReconciliationDiscrepancy.DiscrepancyStatus status) {
        return discrepancyRepository.findByMerchantIdAndStatus(merchantId, status);
    }

    /**
     * 按差异类型查询商户的差错记录。
     */
    public List<ReconciliationDiscrepancy> getDiscrepanciesByMerchantAndType(
            Long merchantId, ReconciliationDiscrepancy.DiscrepancyType type) {
        return discrepancyRepository.findByMerchantIdAndDiscrepancyType(merchantId, type);
    }

    /**
     * 查询对账文件关联的所有差错记录。
     */
    public List<ReconciliationDiscrepancy> getDiscrepanciesByFileId(Long reconciliationFileId) {
        return discrepancyRepository.findByReconciliationFileId(reconciliationFileId);
    }

    /**
     * 按 ID 查找差错记录。
     */
    public Optional<ReconciliationDiscrepancy> findDiscrepancyById(Long id) {
        return discrepancyRepository.findById(id);
    }

    /**
     * 获取金额容差配置。
     */
    public BigDecimal getAmountTolerance() {
        return amountTolerance;
    }

    /**
     * 设置金额容差（主要用于测试）。
     */
    public void setAmountTolerance(BigDecimal amountTolerance) {
        this.amountTolerance = amountTolerance;
    }
}