package org.nexus.gateway.escrow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 预授权交易 JPA Repository。
 *
 * <p>提供按编号、订单、商户、状态、超时等维度的查询方法。</p>
 */
@Repository
public interface PreAuthTransactionRepository extends JpaRepository<PreAuthTransaction, Long> {

    /** 按预授权编号查询 */
    Optional<PreAuthTransaction> findByPreauthNo(String preauthNo);

    /** 按订单 ID 查询 */
    Optional<PreAuthTransaction> findByOrderId(Long orderId);

    /** 按商户 ID 和状态查询 */
    List<PreAuthTransaction> findByMerchantIdAndStatus(Long merchantId, PreAuthStatus status);

    /** 按状态查询 */
    List<PreAuthTransaction> findByStatus(PreAuthStatus status);

    /** 按租户 ID 查询 */
    List<PreAuthTransaction> findByTenantId(String tenantId);

    /**
     * 超时扫描 — 查找 AUTHORIZED 状态且授权时间早于指定时间的预授权。
     * 用于超时自动释放调度器。
     *
     * @param status 预授权状态（AUTHORIZED）
     * @param cutoff 超时截止时间
     * @return 超时的预授权列表
     */
    List<PreAuthTransaction> findByStatusAndAuthorizedAtBefore(PreAuthStatus status, LocalDateTime cutoff);
}