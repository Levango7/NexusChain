package org.nexus.gateway.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 挂账资金记录 Repository。
 *
 * <p>提供按商户、状态、差错类型等维度查询挂账记录的方法。</p>
 */
@Repository
public interface SuspenseAccountRepository
        extends JpaRepository<SuspenseAccount, Long> {

    /** 按商户 ID 查询所有挂账 */
    List<SuspenseAccount> findByMerchantId(Long merchantId);

    /** 按挂账状态查询 */
    List<SuspenseAccount> findByStatus(SuspenseAccount.SuspenseStatus status);

    /** 按差错类型查询 */
    List<SuspenseAccount> findByDiscrepancyType(SuspenseAccount.DiscrepancyType discrepancyType);

    /** 按商户 ID 和挂账状态查询 */
    List<SuspenseAccount> findByMerchantIdAndStatus(
            Long merchantId, SuspenseAccount.SuspenseStatus status);

    /** 按商户 ID 和差错类型查询 */
    List<SuspenseAccount> findByMerchantIdAndDiscrepancyType(
            Long merchantId, SuspenseAccount.DiscrepancyType discrepancyType);

    /** 按关联差错记录 ID 查询 */
    List<SuspenseAccount> findByDiscrepancyId(Long discrepancyId);
}