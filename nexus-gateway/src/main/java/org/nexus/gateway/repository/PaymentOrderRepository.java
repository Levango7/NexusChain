package org.nexus.gateway.repository;

import org.nexus.gateway.model.PaymentOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByOrderNo(String orderNo);

    Optional<PaymentOrder> findByCheckoutToken(String checkoutToken);

    List<PaymentOrder> findByMerchantIdAndStatus(Long merchantId, PaymentOrder.OrderStatus status);

    List<PaymentOrder> findByMerchantId(Long merchantId);

    List<PaymentOrder> findByStatusAndExpiresAtBefore(PaymentOrder.OrderStatus status, LocalDateTime cutoff);

    /**
     * Sum order amounts for a merchant paid within the given window.
     * Used by the risk service to enforce daily/monthly merchant limits.
     * Includes PAID and PAYING orders so in-flight payments count against the limit.
     */
    @Query("SELECT COALESCE(SUM(o.amount), 0) FROM PaymentOrder o "
            + "WHERE o.merchantId = :merchantId "
            + "AND o.status IN (org.nexus.gateway.model.PaymentOrder$OrderStatus.PAID, "
            + "org.nexus.gateway.model.PaymentOrder$OrderStatus.PAYING) "
            + "AND o.createdAt >= :since")
    BigDecimal sumMerchantAmountSince(@Param("merchantId") Long merchantId,
                                      @Param("since") LocalDateTime since);

    /**
     * Find all PAID orders for a merchant within a time window.
     * Used by the settlement service to build settlement batches.
     */
    List<PaymentOrder> findByMerchantIdAndStatusAndPaidAtBetween(
            Long merchantId, PaymentOrder.OrderStatus status,
            LocalDateTime windowStart, LocalDateTime windowEnd);

    // === P4-T7 v2 API 游标分页查询 ===

    /** 游标分页：查询 id 大于 afterId 的订单（按 id 升序） */
    Page<PaymentOrder> findByIdGreaterThan(Long afterId, Pageable pageable);

    /** 游标分页：查询指定商户 id 大于 afterId 的订单（按 id 升序） */
    Page<PaymentOrder> findByMerchantIdAndIdGreaterThan(Long merchantId, Long afterId, Pageable pageable);

    /** 游标分页：查询指定商户的订单（按 id 升序，首页请求） */
    Page<PaymentOrder> findByMerchantId(Long merchantId, Pageable pageable);

    // === P4-T6 多租户改造：按 tenantId 过滤的查询方法 ===

    /** 按租户 + 订单号查询（数据隔离：租户只能查到自己的订单）。 */
    Optional<PaymentOrder> findByTenantIdAndOrderNo(String tenantId, String orderNo);

    /** 按租户 + 订单 ID 查询。 */
    Optional<PaymentOrder> findByTenantIdAndId(String tenantId, Long id);

    /** 按租户 + checkout token 查询。 */
    Optional<PaymentOrder> findByTenantIdAndCheckoutToken(String tenantId, String checkoutToken);

    /** 按租户 + 商户查询所有订单。 */
    List<PaymentOrder> findByTenantIdAndMerchantId(String tenantId, Long merchantId);

    /** 按租户 + 商户 + 状态查询。 */
    List<PaymentOrder> findByTenantIdAndMerchantIdAndStatus(
            String tenantId, Long merchantId, PaymentOrder.OrderStatus status);

    /**
     * 按租户汇总指定时间窗口内的订单金额（计费/风控用）。
     */
    @Query("SELECT COALESCE(SUM(o.amount), 0) FROM PaymentOrder o "
            + "WHERE o.tenantId = :tenantId "
            + "AND o.status IN (org.nexus.gateway.model.PaymentOrder$OrderStatus.PAID, "
            + "org.nexus.gateway.model.PaymentOrder$OrderStatus.PAYING) "
            + "AND o.createdAt >= :since")
    BigDecimal sumTenantAmountSince(@Param("tenantId") String tenantId,
                                     @Param("since") LocalDateTime since);

    /**
     * 按租户统计指定时间窗口内的交易笔数（计费报表用）。
     */
    @Query("SELECT COUNT(o) FROM PaymentOrder o "
            + "WHERE o.tenantId = :tenantId "
            + "AND o.status = org.nexus.gateway.model.PaymentOrder$OrderStatus.PAID "
            + "AND o.paidAt >= :start AND o.paidAt < :end")
    long countTenantPaidInWindow(@Param("tenantId") String tenantId,
                                  @Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end);

    /**
     * 按租户汇总指定时间窗口内 PAID 订单的总金额（计费报表用）。
     */
    @Query("SELECT COALESCE(SUM(o.amount), 0) FROM PaymentOrder o "
            + "WHERE o.tenantId = :tenantId "
            + "AND o.status = org.nexus.gateway.model.PaymentOrder$OrderStatus.PAID "
            + "AND o.paidAt >= :start AND o.paidAt < :end")
    BigDecimal sumTenantPaidInWindow(@Param("tenantId") String tenantId,
                                      @Param("start") LocalDateTime start,
                                      @Param("end") LocalDateTime end);

    /** 游标分页：按租户查询 id 大于 afterId 的订单。 */
    Page<PaymentOrder> findByTenantIdAndIdGreaterThan(String tenantId, Long afterId, Pageable pageable);
}
