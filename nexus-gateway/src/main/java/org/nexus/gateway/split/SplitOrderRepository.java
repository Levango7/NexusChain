package org.nexus.gateway.split;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 分账订单 Repository。
 *
 * <p>提供按订单号、支付 ID、状态查询分账明细等方法。</p>
 */
@Repository
public interface SplitOrderRepository extends JpaRepository<SplitOrder, Long> {

    /**
     * 按订单号查询分账明细。
     *
     * @param orderId 订单号（PaymentOrder.orderNo）
     * @return 分账明细列表
     */
    List<SplitOrder> findByOrderId(String orderId);

    /**
     * 按支付 ID 查询分账明细。
     *
     * @param paymentId 支付 ID（PaymentOrder.id）
     * @return 分账明细列表
     */
    List<SplitOrder> findByPaymentId(Long paymentId);

    /**
     * 按状态查询分账订单。
     *
     * @param status 分账状态
     * @return 分账订单列表
     */
    List<SplitOrder> findByStatus(SplitOrder.SplitStatus status);
}