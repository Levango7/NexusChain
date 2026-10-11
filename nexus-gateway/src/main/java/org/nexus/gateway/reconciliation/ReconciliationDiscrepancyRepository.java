package org.nexus.gateway.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账差错记录 Repository。
 *
 * <p>提供按商户、状态、差异类型等维度查询差错记录的方法。</p>
 */
@Repository
public interface ReconciliationDiscrepancyRepository
        extends JpaRepository<ReconciliationDiscrepancy, Long> {

    /** 按商户 ID 查询所有差错 */
    List<ReconciliationDiscrepancy> findByMerchantId(Long merchantId);

    /**
     * 待补渠道归属的差错（P1 修复 2026-10-11）。
     *
     * <p>V94 之前落库的差错没有 {@code channel_type}（编码时未采集），渠道退款路径对它们
     * 永久 fail-closed。本查询即运营的"待补录工作队列"。</p>
     */
    List<ReconciliationDiscrepancy> findByChannelTypeIsNull();


    /** 按商户 ID 和差错状态查询 */
    List<ReconciliationDiscrepancy> findByMerchantIdAndStatus(
            Long merchantId, ReconciliationDiscrepancy.DiscrepancyStatus status);

    /** 按商户 ID 和差异类型查询 */
    List<ReconciliationDiscrepancy> findByMerchantIdAndDiscrepancyType(
            Long merchantId, ReconciliationDiscrepancy.DiscrepancyType type);

    /** 按对账文件 ID 查询所有差错 */
    List<ReconciliationDiscrepancy> findByReconciliationFileId(Long reconciliationFileId);

    /** 按商户 ID 和处置规则查询 */
    List<ReconciliationDiscrepancy> findByMerchantIdAndResolutionType(
            Long merchantId, ReconciliationDiscrepancy.ResolutionType resolutionType);

    /** 按创建时间范围查询所有差错 */
    List<ReconciliationDiscrepancy> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    /** 按商户 ID 和创建时间范围查询差错 */
    List<ReconciliationDiscrepancy> findByMerchantIdAndCreatedAtBetween(
            Long merchantId, LocalDateTime start, LocalDateTime end);
}