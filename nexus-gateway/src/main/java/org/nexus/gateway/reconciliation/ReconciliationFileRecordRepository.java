package org.nexus.gateway.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 对账文件记录 Repository。
 *
 * <p>提供按商户查询对账文件历史、按周期类型筛选等查询方法。</p>
 */
@Repository
public interface ReconciliationFileRecordRepository extends JpaRepository<ReconciliationFileRecord, Long> {

    /** 按商户 ID 查询，按生成时间倒序排列 */
    List<ReconciliationFileRecord> findByMerchantIdOrderByGeneratedAtDesc(Long merchantId);

    /** 按商户 ID 和周期类型查询 */
    List<ReconciliationFileRecord> findByMerchantIdAndPeriodType(Long merchantId, String periodType);

    /** 按 ID 查找文件记录 */
    Optional<ReconciliationFileRecord> findById(Long id);
}