package org.nexus.gateway.reconciliation.link;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 交易对账审计记录 Repository。
 */
@Repository
public interface TransactionAuditRecordRepository
        extends JpaRepository<TransactionAuditRecord, Long> {

    /** 按审计日期查询 */
    List<TransactionAuditRecord> findByAuditDate(LocalDate auditDate);

    /** 按商户 ID 查询 */
    List<TransactionAuditRecord> findByMerchantId(Long merchantId);

    /** 按审计日期和商户 ID 查询 */
    List<TransactionAuditRecord> findByAuditDateAndMerchantId(LocalDate auditDate, Long merchantId);

    /** 按审计结论查询 */
    List<TransactionAuditRecord> findByConclusion(TransactionAuditRecord.AuditConclusion conclusion);

    /** 按审计日期范围查询 */
    List<TransactionAuditRecord> findByAuditDateBetween(LocalDate startDate, LocalDate endDate);
}