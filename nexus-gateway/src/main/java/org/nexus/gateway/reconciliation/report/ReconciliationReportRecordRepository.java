package org.nexus.gateway.reconciliation.report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 对账报表记录 Repository。
 */
@Repository
public interface ReconciliationReportRecordRepository extends JpaRepository<ReconciliationReportRecord, Long> {

    /** 按商户查询报表列表，按生成时间倒序排列 */
    List<ReconciliationReportRecord> findByMerchantIdOrderByGeneratedAtDesc(Long merchantId);
}