package org.nexus.gateway.repository;

import org.nexus.gateway.model.PaymentOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByOrderNo(String orderNo);

    Optional<PaymentOrder> findByCheckoutToken(String checkoutToken);

    /**
     * 按链上交易哈希查询订单（P0-5 修复，v2.27.0）。
     *
     * <p>用于支付确认时校验交易-订单绑定唯一性：同一 txHash 不得绑定到多个订单。
     * 配合 DB 层唯一约束 {@code uk_payment_orders_chain_tx_hash}（V12 migration）双重防护。</p>
     *
     * @param chainTxHash 链上交易哈希
     * @return 已绑定该 txHash 的订单（可能为空）
     */
    Optional<PaymentOrder> findByChainTxHash(String chainTxHash);

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

    /**
     * 悲观写锁查询订单（P0-2 修复：防止并发双花）。
     *
     * <p>使用 {@code SELECT FOR UPDATE} 锁定订单行，保证同一时刻只有一个事务
     * 能读取并修改该订单的退款状态。在退款请求场景下，两个并发 requestRefund
     * 调用会被串行化，第二个事务等待第一个提交后才能读取到最新的退款总和。</p>
     *
     * @param id 订单 ID
     * @return 锁定后的订单（可能为空）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM PaymentOrder o WHERE o.id = :id")
    Optional<PaymentOrder> findByIdForUpdate(@Param("id") Long id);

    /**
     * 批量按 ID 查询订单（性能优化任务 #310：修复 N+1 查询）。
     *
     * <p>用于 {@link org.nexus.gateway.clearing.DefaultSettlementService#toEngineBatch}
     * 替代循环调用 {@code findById} 的 N+1 查询模式。一次 IN 查询取回所有订单，
     * 将 N 次 SQL 降为 1 次。</p>
     *
     * @param ids 订单 ID 集合
     * @return 匹配的订单列表（顺序不保证，调用方按需排序）
     */
    @Query("SELECT o FROM PaymentOrder o WHERE o.id IN :ids")
    List<PaymentOrder> findByIdIn(@Param("ids") List<Long> ids);
}
