package org.nexus.gateway.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}