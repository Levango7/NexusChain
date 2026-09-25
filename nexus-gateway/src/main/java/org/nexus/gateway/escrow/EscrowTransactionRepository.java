package org.nexus.gateway.escrow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 担保交易 JPA Repository。
 *
 * <p>提供按编号、订单、商户、状态、超时等维度的查询方法。</p>
 */
@Repository
public interface EscrowTransactionRepository extends JpaRepository<EscrowTransaction, Long> {

    /** 按担保交易编号查询 */
    Optional<EscrowTransaction> findByEscrowNo(String escrowNo);

    /** 按订单 ID 查询 */
    Optional<EscrowTransaction> findByOrderId(Long orderId);

    /** 按商户 ID 和状态查询 */
    List<EscrowTransaction> findByMerchantIdAndStatus(Long merchantId, EscrowStatus status);

    /** 按状态查询 */
    List<EscrowTransaction> findByStatus(EscrowStatus status);

    /** 按租户 ID 查询 */
    List<EscrowTransaction> findByTenantId(String tenantId);

    /**
     * 超时扫描 — 查找 FUNDED 状态且付款时间早于指定时间的担保交易。
     * 用于超时自动确认调度器。
     *
     * @param status 担保状态（FUNDED）
     * @param cutoff 超时截止时间
     * @return 超时的担保交易列表
     */
    List<EscrowTransaction> findByStatusAndFundedAtBefore(EscrowStatus status, LocalDateTime cutoff);
}