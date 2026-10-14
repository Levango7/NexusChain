package org.nexus.gateway.service;

import org.nexus.gateway.event.PaymentConfirmedEvent;
import org.nexus.analytics.event.PaymentCompletedEvent;
import org.nexus.gateway.model.FinalityStatus;
import org.nexus.gateway.model.OrderStateMachine;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.orchestration.settlement.FinalityService;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.gateway.transaction.ReplayableTccAction;
import org.nexus.gateway.transaction.TransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 支付确认 TCC 参与方 —— {@link PaymentServiceImpl} 支付确认链路的<b>无状态、可重放</b>实现
 * （#31-Step2b，#102 PLAN §4.1）。
 *
 * <p>此前它是 {@code PaymentServiceImpl} 里的一个匿名 {@code TccAction} 闭包，捕获方法局部变量
 * {@code orderId}/{@code chainTxHash} ⇒ 进程重启后闭包与 {@code TccTransactionManager} 的进程内
 * {@code actionRegistry} 同时消失，恢复调度器对超时的 TRY/CONFIRM 无从重试（#102 PLAN §3.1 的
 * "冻结永不释放"）。本 bean 把同样的三阶段逻辑改为<b>只从 {@link TransactionContext} 读输入</b>
 * （{@code orderId}/{@code chainTxHash} 均来自 {@code TransactionLog.payload}），
 * 于是任意副本重启后 {@link org.nexus.gateway.transaction.TccActionResolver} 都能按
 * {@code participantId} 取回本 bean、用持久化上下文重建并继续 Confirm/Cancel。</p>
 *
 * <p>行为与原闭包<b>逐条等价</b>（幂等守卫、状态迁移、事件发布、{@code finalityService} 为 null 的降级）：
 * 见 {@code PaymentConfirmTccActionTest}。</p>
 */
@Component
public class PaymentConfirmTccAction implements ReplayableTccAction {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfirmTccAction.class);

    /** 稳定 participantId：作为 {@code TccActionResolver} 的查找键，跨重启/跨副本一致。 */
    public static final String PARTICIPANT_ID = "payment-confirm";

    private final PaymentOrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;
    /** 最终性推导服务，可为 null（测试/降级环境）——与原闭包一致：null 时 finality 记 UNKNOWN。 */
    private final FinalityService finalityService;

    @Autowired
    public PaymentConfirmTccAction(PaymentOrderRepository orderRepository,
                                   ApplicationEventPublisher eventPublisher,
                                   @Autowired(required = false) FinalityService finalityService) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.finalityService = finalityService;
    }

    @Override
    public String getParticipantId() {
        return PARTICIPANT_ID;
    }

    /** 从上下文读 orderId，兼容 JSON 反序列化后可能是 Integer/Long/String 的情形。 */
    private static long readOrderId(TransactionContext ctx) {
        Object raw = ctx.get("orderId");
        if (raw == null) {
            throw new IllegalArgumentException("TCC ctx 缺少 orderId，无法定位订单");
        }
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        return Long.parseLong(String.valueOf(raw));
    }

    @Override
    public boolean tryAction(TransactionContext ctx) {
        long orderId = readOrderId(ctx);
        String chainTxHash = ctx.get("chainTxHash", String.class);
        // Try：锁定订单状态（PAYING → SUBMITTED）
        PaymentOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found in TCC try: " + orderId));
        // 幂等：已确认的订单无需再次锁定
        if (order.getStatus() == PaymentOrder.OrderStatus.PAID) {
            return true;
        }
        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.SUBMITTED);
        order.setChainTxHash(chainTxHash);
        orderRepository.save(order);
        log.info("TCC Try: order locked, orderNo={}", order.getOrderNo());
        return true;
    }

    @Override
    public void confirmAction(TransactionContext ctx) {
        long orderId = readOrderId(ctx);
        String chainTxHash = ctx.get("chainTxHash", String.class);
        // Confirm：确认支付（SUBMITTED → PAID）
        PaymentOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found in TCC confirm: " + orderId));
        // 幂等：Confirm 重试（最多 3 次）或重复确认时直接返回，
        // 避免 PAID → PAID 非法状态转换导致重试放大失败。
        if (order.getStatus() == PaymentOrder.OrderStatus.PAID) {
            log.info("TCC Confirm: order already PAID, skipping, orderNo={}", order.getOrderNo());
            return;
        }
        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.PAID);
        order.setPaidAt(LocalDateTime.now());
        order.setChainTxHash(chainTxHash);

        // 关联最终性状态
        if (finalityService != null) {
            FinalityService.FinalityInfo finalityInfo = finalityService.getFinality(chainTxHash);
            order.setFinalityStatus(finalityInfo.status());
        } else {
            order.setFinalityStatus(FinalityStatus.UNKNOWN);
        }

        orderRepository.save(order);
        log.info("TCC Confirm: payment confirmed, orderNo={}, txHash={}", order.getOrderNo(), chainTxHash);

        // 发布支付确认事件
        eventPublisher.publishEvent(new PaymentConfirmedEvent(
                this, order.getId(), order.getOrderNo(), order.getMerchantId(),
                chainTxHash, order.getPayerAddress(),
                order.getAmount().toPlainString(),
                order.getFinalityStatus()));

        // 发布 PaymentCompletedEvent 供 analytics 采集
        eventPublisher.publishEvent(new PaymentCompletedEvent(
                this,
                order.getId(),
                order.getAmount(),
                order.getTokenSymbol(),
                "NEXUS-CORE",
                order.getMerchantId(),
                chainTxHash,
                order.getPayerAddress(),
                order.getPayeeAddress(),
                Instant.now(),
                0L,
                0));
    }

    @Override
    public void cancelAction(TransactionContext ctx) {
        long orderId = readOrderId(ctx);
        // Cancel：释放锁定（SUBMITTED → FAILED）
        PaymentOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != PaymentOrder.OrderStatus.SUBMITTED) {
            // 幂等：无可释放的锁定
            return;
        }
        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.FAILED);
        orderRepository.save(order);
        log.warn("TCC Cancel: order released, orderNo={}", order.getOrderNo());
    }
}
