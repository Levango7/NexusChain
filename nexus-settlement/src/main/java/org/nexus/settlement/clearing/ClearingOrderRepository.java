package org.nexus.settlement.clearing;

import org.springframework.data.jpa.repository.JpaRepository;
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
}