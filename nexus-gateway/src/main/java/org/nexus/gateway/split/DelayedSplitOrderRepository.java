package org.nexus.gateway.split;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 延迟分账订单 Repository。
 *
 * <p>提供按状态、商户、预定时间等查询延迟分账订单的方法。</p>
 */
@Repository
public interface DelayedSplitOrderRepository extends JpaRepository<DelayedSplitOrder, Long> {

    /**
     * 按商户 ID 查询延迟分账订单。
     *
     * @param merchantId 商户 ID
     * @return 延迟分账订单列表
     */
    List<DelayedSplitOrder> findByMerchantId(Long merchantId);

    /**
     * 按订单号查询延迟分账订单。
     *
     * @param orderId 订单号
     * @return 延迟分账订单列表
     */
    List<DelayedSplitOrder> findByOrderId(String orderId);

    /**
     * 按延迟状态查询延迟分账订单。
     *
     * @param delayStatus 延迟状态
     * @return 延迟分账订单列表
     */
    List<DelayedSplitOrder> findByDelayStatus(DelayedSplitOrder.DelayStatus delayStatus);

    /**
     * 查询已到达预定执行时间且状态为 SCHEDULED 的延迟分账订单。
     * 用于定时任务批量触发状态流转 SCHEDULED → READY。
     *
     * @param now 当前时间
     * @return 已到期的 SCHEDULED 状态延迟分账订单列表
     */
    List<DelayedSplitOrder> findByDelayStatusAndScheduledAtBefore(
            DelayedSplitOrder.DelayStatus delayStatus, LocalDateTime now);

    /**
     * 按商户 ID 和延迟状态查询延迟分账订单。
     *
     * @param merchantId   商户 ID
     * @param delayStatus  延迟状态
     * @return 延迟分账订单列表
     */
    List<DelayedSplitOrder> findByMerchantIdAndDelayStatus(Long merchantId,
                                                            DelayedSplitOrder.DelayStatus delayStatus);
}