package org.nexus.settlement.clearing;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 清算订单仓储（JPA，账务核心持久化）。
 *
 * <p>消费方：SettlementEventCollector（PENDING 落库/drain 取批）、
 * DefaultClearingEngine（settle 终态回填）。</p>
 */
@Repository
public interface ClearingOrderRepository extends JpaRepository<ClearingOrder, String> {

    /**
     * 按状态查询订单（drainStaging 取 PENDING 批次）。
     *
     * @param status 订单状态
     * @return 该状态的订单列表
     */
    List<ClearingOrder> findByStatus(ClearingOrder.OrderStatus status);

    /**
     * P1-1：按状态查询订单并加悲观写锁，确保 drainStaging 的查询和删除原子执行。
     * 使用 @Query + @Lock 避免 Spring Data 方法名解析问题。
     *
     * @param status 订单状态
     * @return 该状态的订单列表（带 PESSIMISTIC_WRITE 锁）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM ClearingOrder o WHERE o.status = :status")
    List<ClearingOrder> findByStatusForUpdate(@Param("status") ClearingOrder.OrderStatus status);
}