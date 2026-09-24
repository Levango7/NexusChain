package org.nexus.gateway.split;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 延迟分账服务。
 *
 * <p>支持 T+N 延迟分账模式：在支付完成后，分账不立即执行，
 * 而是创建延迟分账计划，在预定时间（T+N）到达后才执行分账。</p>
 *
 * <h3>延迟分账状态流转</h3>
 * <ol>
 *   <li>SCHEDULED：创建延迟分账计划，设置 scheduledAt = 支付时间 + N 天</li>
 *   <li>READY：定时任务检测到 scheduledAt 已到达，将状态从 SCHEDULED 改为 READY</li>
 *   <li>EXECUTING：开始执行分账，状态从 READY 改为 EXECUTING</li>
 *   <li>COMPLETED：分账成功完成</li>
 *   <li>FAILED：分账执行失败，记录 failureReason</li>
 * </ol>
 */
@Service
public class DelayedSplitService {

    private static final Logger log = LoggerFactory.getLogger(DelayedSplitService.class);

    private final DelayedSplitOrderRepository delayedSplitOrderRepository;
    private final SplitService splitService;

    public DelayedSplitService(DelayedSplitOrderRepository delayedSplitOrderRepository,
                                SplitService splitService) {
        this.delayedSplitOrderRepository = delayedSplitOrderRepository;
        this.splitService = splitService;
    }

    /**
     * 创建延迟分账计划。
     *
     * <p>根据商户的分账规则计算分账明细，但不立即执行，
     * 而是创建 DelayedSplitOrder 记录，设置 scheduledAt = 当前时间 + delayDays 天。</p>
     *
     * @param orderNo     订单号
     * @param paymentId   支付 ID
     * @param merchantId  商户 ID
     * @param totalAmount 订单总金额
     * @param delayDays   延迟天数（T+N 中的 N）
     * @return 创建的 DelayedSplitOrder 列表
     * @throws IllegalArgumentException 如果 delayDays < 0
     */
    @Transactional
    public List<DelayedSplitOrder> scheduleDelayedSplits(String orderNo, Long paymentId, Long merchantId,
                                                          BigDecimal totalAmount, int delayDays) {
        if (delayDays < 0) {
            throw new IllegalArgumentException("delayDays must be >= 0");
        }

        // 计算分账明细（不持久化）
        List<SplitOrder> splits = splitService.calculateSplits(orderNo, paymentId, merchantId, totalAmount);

        if (splits.isEmpty()) {
            log.info("No splits to schedule for orderNo={}, merchantId={}", orderNo, merchantId);
            return List.of();
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime scheduledAt = now.plusDays(delayDays);

        List<DelayedSplitOrder> delayedOrders = new ArrayList<>();
        for (SplitOrder split : splits) {
            DelayedSplitOrder delayedOrder = new DelayedSplitOrder();
            delayedOrder.setOrderId(split.getOrderId());
            delayedOrder.setPaymentId(split.getPaymentId());
            delayedOrder.setMerchantId(split.getMerchantId());
            delayedOrder.setReceiverAddress(split.getReceiverAddress());
            delayedOrder.setAmount(split.getAmount());
            delayedOrder.setSplitType(split.getSplitType());
            delayedOrder.setSplitValue(split.getSplitValue());
            delayedOrder.setSplitRuleId(split.getSplitRuleId());
            delayedOrder.setDescription(split.getDescription());
            delayedOrder.setDelayStatus(DelayedSplitOrder.DelayStatus.SCHEDULED);
            delayedOrder.setScheduledAt(scheduledAt);
            delayedOrder.setDelayDays(delayDays);

            delayedOrders.add(delayedSplitOrderRepository.save(delayedOrder));
        }

        log.info("Scheduled delayed splits: orderNo={}, merchantId={}, delayDays={}, scheduledAt={}, count={}",
                orderNo, merchantId, delayDays, scheduledAt, delayedOrders.size());
        return delayedOrders;
    }

    /**
     * 定时任务：将已到期的 SCHEDULED 状态延迟分账订单转为 READY。
     *
     * <p>查询所有 scheduledAt <= 当前时间 且状态为 SCHEDULED 的延迟分账订单，
     * 将其状态更新为 READY。</p>
     *
     * @return 被转为 READY 的延迟分账订单数量
     */
    @Transactional
    public int transitionScheduledToReady() {
        LocalDateTime now = LocalDateTime.now();
        List<DelayedSplitOrder> scheduledOrders = delayedSplitOrderRepository
                .findByDelayStatusAndScheduledAtBefore(DelayedSplitOrder.DelayStatus.SCHEDULED, now);

        for (DelayedSplitOrder order : scheduledOrders) {
            order.setDelayStatus(DelayedSplitOrder.DelayStatus.READY);
            delayedSplitOrderRepository.save(order);
        }

        log.info("Transitioned {} delayed split orders from SCHEDULED to READY", scheduledOrders.size());
        return scheduledOrders.size();
    }

    /**
     * 执行 READY 状态的延迟分账订单。
     *
     * <p>将状态从 READY 改为 EXECUTING，然后执行分账逻辑，
     * 成功则改为 COMPLETED，失败则改为 FAILED 并记录原因。</p>
     *
     * @param orderId 延迟分账订单 ID
     * @return 执行后的延迟分账订单
     * @throws IllegalArgumentException 如果订单不存在或状态不是 READY
     */
    @Transactional
    public DelayedSplitOrder executeDelayedSplit(Long orderId) {
        DelayedSplitOrder order = delayedSplitOrderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Delayed split order not found: " + orderId));

        if (order.getDelayStatus() != DelayedSplitOrder.DelayStatus.READY) {
            throw new IllegalArgumentException(
                    "Delayed split order must be in READY state, current: " + order.getDelayStatus());
        }

        // 状态流转：READY → EXECUTING
        order.setDelayStatus(DelayedSplitOrder.DelayStatus.EXECUTING);
        order.setExecutedAt(LocalDateTime.now());
        delayedSplitOrderRepository.save(order);

        try {
            // 执行分账逻辑（这里模拟执行，实际应调用链上分账或结算服务）
            // 状态流转：EXECUTING → COMPLETED
            order.setDelayStatus(DelayedSplitOrder.DelayStatus.COMPLETED);
            delayedSplitOrderRepository.save(order);

            log.info("Delayed split executed successfully: id={}, orderNo={}", orderId, order.getOrderId());
        } catch (Exception e) {
            // 状态流转：EXECUTING → FAILED
            order.setDelayStatus(DelayedSplitOrder.DelayStatus.FAILED);
            order.setFailureReason(e.getMessage());
            delayedSplitOrderRepository.save(order);

            log.error("Delayed split execution failed: id={}, orderNo={}, error={}",
                    orderId, order.getOrderId(), e.getMessage());
        }

        return order;
    }

    /**
     * 批量执行所有 READY 状态的延迟分账订单。
     *
     * @return 执行结果列表
     */
    @Transactional
    public List<DelayedSplitOrder> executeAllReadyDelayedSplits() {
        List<DelayedSplitOrder> readyOrders = delayedSplitOrderRepository
                .findByDelayStatus(DelayedSplitOrder.DelayStatus.READY);

        List<DelayedSplitOrder> results = new ArrayList<>();
        for (DelayedSplitOrder order : readyOrders) {
            try {
                results.add(executeDelayedSplit(order.getId()));
            } catch (Exception e) {
                log.error("Failed to execute delayed split: id={}, error={}",
                        order.getId(), e.getMessage());
            }
        }

        log.info("Executed {} READY delayed split orders", results.size());
        return results;
    }

    /**
     * 查询商户的延迟分账订单。
     *
     * @param merchantId 商户 ID
     * @return 延迟分账订单列表
     */
    public List<DelayedSplitOrder> getDelayedSplitsByMerchant(Long merchantId) {
        return delayedSplitOrderRepository.findByMerchantId(merchantId);
    }

    /**
     * 查询某订单的延迟分账明细。
     *
     * @param orderId 订单号
     * @return 延迟分账订单列表
     */
    public List<DelayedSplitOrder> getDelayedSplitsByOrder(String orderId) {
        return delayedSplitOrderRepository.findByOrderId(orderId);
    }

    /**
     * 查询指定状态的延迟分账订单。
     *
     * @param delayStatus 延迟状态
     * @return 延迟分账订单列表
     */
    public List<DelayedSplitOrder> getDelayedSplitsByStatus(DelayedSplitOrder.DelayStatus delayStatus) {
        return delayedSplitOrderRepository.findByDelayStatus(delayStatus);
    }
}