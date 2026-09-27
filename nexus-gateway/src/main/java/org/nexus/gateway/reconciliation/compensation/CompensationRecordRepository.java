package org.nexus.gateway.reconciliation.compensation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 补偿记录 Repository。
 */
@Repository
public interface CompensationRecordRepository extends JpaRepository<CompensationRecord, Long> {

    /** 按差错ID查询补偿记录（幂等检查） */
    Optional<CompensationRecord> findByDiscrepancyId(Long discrepancyId);

    /** 查询商户的所有补偿记录 */
    List<CompensationRecord> findByMerchantId(Long merchantId);

    /** 幂等判断：差错是否已有补偿记录 */
    boolean existsByDiscrepancyId(Long discrepancyId);
}