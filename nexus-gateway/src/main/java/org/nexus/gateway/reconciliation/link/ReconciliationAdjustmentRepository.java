package org.nexus.gateway.reconciliation.link;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 对账差异调整记录 Repository。
 *
 * <p>提供按商户、审批状态、调整类型等维度查询调整记录的方法。
 * {@code reference} 字段为唯一键，用于幂等检查。</p>
 */
@Repository
public interface ReconciliationAdjustmentRepository
        extends JpaRepository<ReconciliationAdjustment, Long> {

    /** 按商户 ID 查询所有调整记录 */
    List<ReconciliationAdjustment> findByMerchantId(Long merchantId);

    /** 按商户 ID 和审批状态查询 */
    List<ReconciliationAdjustment> findByMerchantIdAndApprovalStatus(
            Long merchantId, ApprovalStatus approvalStatus);

    /** 按审批状态查询 */
    List<ReconciliationAdjustment> findByApprovalStatus(ApprovalStatus approvalStatus);

    /** 按关联差错记录 ID 查询 */
    List<ReconciliationAdjustment> findByDiscrepancyId(Long discrepancyId);

    /** 按调整类型查询 */
    List<ReconciliationAdjustment> findByAdjustmentType(AdjustmentType adjustmentType);

    /** 按商户 ID 和调整类型查询 */
    List<ReconciliationAdjustment> findByMerchantIdAndAdjustmentType(
            Long merchantId, AdjustmentType adjustmentType);

    /** 按幂等键（reference）查询 — 用于幂等检查 */
    Optional<ReconciliationAdjustment> findByReference(String reference);

    /** 查询未执行的调整记录 */
    List<ReconciliationAdjustment> findByExecutedFalse();

    /** 按商户 ID 查询未执行的调整记录 */
    List<ReconciliationAdjustment> findByMerchantIdAndExecutedFalse(Long merchantId);
}