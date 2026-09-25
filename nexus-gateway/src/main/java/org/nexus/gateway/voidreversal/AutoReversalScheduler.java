package org.nexus.gateway.voidreversal;

import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动冲正调度器 — 定时扫描超时未确认的支付订单，自动发起冲正请求。
 *
 * <p>调度规则：</p>
 * <ul>
 *   <li>每 60 秒扫描一次（fixedDelay=60000）</li>
 *   <li>查找 PAYING 状态超过 30 分钟的订单</li>
 *   <li>对每个超时订单自动发起冲正请求（类型为 AUTO）</li>
 *   <li>通过 orderId 去重保证幂等：已有冲正请求的订单不会重复发起</li>
 * </ul>
 *
 * <p>注意：自动冲正请求也需要人工审批确认后才会执行，确保系统安全。
 * 自动冲正仅针对 PAYING 状态（支付中但未确认），PAID 状态的交易不在此调度器范围内。</p>
 */
@Component
public class AutoReversalScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoReversalScheduler.class);

    /** PAYING 状态超时阈值：30 分钟 */
    private static final int PAYING_TIMEOUT_MINUTES = 30;

    private final PaymentOrderRepository paymentOrderRepository;
    private final ReversalService reversalService;
    private final ReversalRequestRepository reversalRequestRepository;

    public AutoReversalScheduler(PaymentOrderRepository paymentOrderRepository,
                                  ReversalService reversalService,
                                  ReversalRequestRepository reversalRequestRepository) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.reversalService = reversalService;
        this.reversalRequestRepository = reversalRequestRepository;
    }

    /**
     * 定时扫描超时未确认的支付订单，自动发起冲正请求。
     *
     * <p>扫描条件：status = PAYING 且 createdAt < (now - 30分钟)。
     * 对每个超时订单，检查是否已有冲正请求（幂等），若无则自动发起。</p>
     */
    @Scheduled(fixedDelay = 60000)
    public void scanTimeoutOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(PAYING_TIMEOUT_MINUTES);
        List<PaymentOrder> timeoutOrders =
                paymentOrderRepository.findByStatusAndExpiresAtBefore(PaymentOrder.OrderStatus.PAYING, cutoff);

        if (timeoutOrders.isEmpty()) {
            return;
        }

        log.info("自动冲正扫描：发现 {} 笔超时未确认的支付订单", timeoutOrders.size());

        for (PaymentOrder order : timeoutOrders) {
            try {
                // 幂等检查：已有冲正请求的订单不重复发起
                List<ReversalRequest> existingRequests =
                        reversalRequestRepository.findByOrderId(order.getId());
                boolean hasActiveReversal = existingRequests.stream()
                        .anyMatch(r -> r.getStatus() == ReversalStatus.PENDING
                                || r.getStatus() == ReversalStatus.APPROVED
                                || r.getStatus() == ReversalStatus.COMPLETED);

                if (hasActiveReversal) {
                    log.debug("订单已有冲正请求，跳过: orderId={}", order.getId());
                    continue;
                }

                // 自动发起冲正请求
                String reason = "自动冲正：PAYING 状态超时 " + PAYING_TIMEOUT_MINUTES + " 分钟未确认";
                ReversalRequest reversalRequest = reversalService.autoReversal(order.getId(), reason);

                log.info("自动冲正请求已创建: orderId={}, reversalNo={}",
                        order.getId(), reversalRequest.getReversalNo());

            } catch (Exception e) {
                log.error("自动冲正请求创建失败: orderId={}, error={}",
                        order.getId(), e.getMessage(), e);
            }
        }
    }
}