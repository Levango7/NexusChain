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
 * 自动冲正调度器 — 定时扫描 PAYING 超时订单，尝试自动发起冲正请求。
 *
 * <p><b>当前这张"安全网"结构上不可能生效，本类只负责把这件事变成可见的告警。</b>
 * 扫描判据是 {@code status = PAYING}（见 {@link #scanTimeoutOrders()}），
 * 而被扫描后要调用的 {@link ReversalService#autoReversal} 经
 * {@code createReversalRequest} 硬校验 <b>订单必须为 PAID</b>，否则抛
 * {@link VoidReversalException}({@code ORDER_NOT_PAID})。
 * 两个条件互斥 ⇒ 每一笔候选订单都必然被拒绝，本调度器至今<b>从未成功创建过任何冲正请求</b>，
 * 对"支付卡住"不提供任何保护。这是结构性缺陷，不是运行期偶发异常。</p>
 *
 * <p>因此这里<b>不</b>给 {@code ORDER_NOT_PAID} 记 ERROR（那只是把 3 副本 × 每 60 秒的噪声刷满日志），
 * 而是每个 tick 聚合出<b>一条</b>带稳定标记 {@link #SAFETY_NET_INERT_MARKER} 的 WARN，
 * 让监控能直接告警。真正的修复（改成扫描 PAID 订单、或给 PAYING 定义一条合法的冲正语义）
 * 属于资金语义变更，需人工裁定，不在本类范围内。</p>
 *
 * <p>调度规则：每 60 秒扫描一次（fixedDelay=60000）；扫描 PAYING 且 createdAt 早于 30 分钟前的订单。
 * 未加 ShedLock：多副本重复执行只会各自回吐同样的告警，不会产生任何副作用
 * （在修复前，这条链路根本不创建记录）。</p>
 */
@Component
public class AutoReversalScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoReversalScheduler.class);

    /**
     * 结构化告警标记 — 自动冲正安全网当前不可用时写入 WARN 的首字段，供日志/监控精确匹配。
     */
    public static final String SAFETY_NET_INERT_MARKER = "AUTO_REVERSAL_SAFETY_NET_INERT";

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
     * 定时扫描 PAYING 超时订单，尝试自动发起冲正请求。
     *
     * <p>扫描条件：status = PAYING 且 createdAt &lt; (now - 30分钟)。
     * 每个候选先做"已有进行中冲正请求"的幂等检查，再调 {@code autoReversal}。</p>
     *
     * <p>由于冲正前置条件要求 PAID，{@code autoReversal} 对 PAYING 订单必然抛
     * {@code ORDER_NOT_PAID}；本方法把这类拒绝计入 {@code preconditionRejected}，
     * 循环结束后若有则输出一条 {@link #SAFETY_NET_INERT_MARKER} 告警。
     * 其它异常仍按错误逐笔记录。</p>
     */
    @Scheduled(fixedDelay = 60000)
    public void scanTimeoutOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(PAYING_TIMEOUT_MINUTES);
        List<PaymentOrder> timeoutOrders =
                paymentOrderRepository.findByStatusAndCreatedAtBefore(PaymentOrder.OrderStatus.PAYING, cutoff);

        if (timeoutOrders.isEmpty()) {
            return;
        }

        log.info("自动冲正扫描：发现 {} 笔超时未确认的支付订单", timeoutOrders.size());

        int preconditionRejected = 0;
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

            } catch (VoidReversalException e) {
                if ("ORDER_NOT_PAID".equals(e.getErrorCode())) {
                    // 结构性互斥：扫描判据是 PAYING，而冲正前置条件要求 PAID ⇒ 必然命中，属预期内。
                    preconditionRejected++;
                    log.debug("自动冲正前置条件不满足（预期内，见类注释）: orderId={}", order.getId());
                    continue;
                }
                log.error("自动冲正请求创建失败: orderId={}, code={}, error={}",
                        order.getId(), e.getErrorCode(), e.getMessage(), e);
            } catch (Exception e) {
                log.error("自动冲正请求创建失败: orderId={}, error={}",
                        order.getId(), e.getMessage(), e);
            }
        }

        if (preconditionRejected > 0) {
            log.warn("{} 自动冲正安全网当前不可用：{} 笔 PAYING 超时订单全部被前置条件（要求 PAID）拒绝，"
                            + "本调度器未对任何订单提供保护。扫描判据与冲正前置条件互斥，属结构性缺陷，需人工修复。",
                    SAFETY_NET_INERT_MARKER, preconditionRejected);
        }
    }
}
