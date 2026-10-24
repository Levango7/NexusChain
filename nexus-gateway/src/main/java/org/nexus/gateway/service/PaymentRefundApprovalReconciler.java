package org.nexus.gateway.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.nexus.gateway.approval.SigningApprovalExecutionService;
import org.nexus.gateway.approval.SigningOutcome;
import org.nexus.gateway.event.RefundCompletedEvent;
import org.nexus.gateway.model.OrderStateMachine;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.model.Refund;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.gateway.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 直通退款（{@link PaymentServiceImpl#refund}）的"等待大额签名审批"推进器。
 *
 * <p>大额退款在阶段2 收到 {@code statusCode=2001} 时，阶段3 把退款置
 * {@link Refund.RefundStatus#AWAITING_APPROVAL}（订单保持 REFUND_PENDING），
 * 资金未动。审批通过后必须有人完成签名/广播并落账；本类按固定周期读取审批链接
 * （{@link SigningApprovalExecutionService#refresh(String)}，键与
 * {@code PaymentServiceImpl#executeRefundTransfer} 一致），按结果收口：</p>
 * <ul>
 *   <li>{@code EXECUTED} → 退款 COMPLETED + 订单 REFUNDED + 发 RefundCompletedEvent；</li>
 *   <li>{@code FAILED} → 退款 FAILED + 订单回 PAID（确定未动账）；</li>
 *   <li>结果不可核对 → RECONCILIATION_NEEDED，订单保持 REFUND_PENDING，人工核对；</li>
 *   <li>仍在审批/执行 → 下一轮再看。</li>
 * </ul>
 *
 * <p>本类不做签名/广播本身，只消费链接状态；执行动作（APPROVED → executeApproved）
 * 由 {@code refresh} 在确认审批通过后发起，并发安全由链接表乐观锁与签名服务 CAS 保证。</p>
 */
@Component
public class PaymentRefundApprovalReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentRefundApprovalReconciler.class);

    /** 刚写入 AWAITING_APPROVAL 的退款先让出一轮（避免与请求线程的链接推进竞争）。 */
    private static final Duration SETTLE_BEFORE = Duration.ofSeconds(30);

    /** 与 PaymentServiceImpl#executeRefundTransfer 保持一致的键前缀。 */
    static final String REQUEST_KEY_PREFIX = "execution:REFUND:refundNo:";

    private final RefundRepository refundRepository;
    private final PaymentOrderRepository orderRepository;
    private final SigningApprovalExecutionService signingApprovalExecutionService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public PaymentRefundApprovalReconciler(RefundRepository refundRepository,
                                           PaymentOrderRepository orderRepository,
                                           SigningApprovalExecutionService signingApprovalExecutionService,
                                           ApplicationEventPublisher eventPublisher,
                                           PlatformTransactionManager transactionManager) {
        this.refundRepository = refundRepository;
        this.orderRepository = orderRepository;
        this.signingApprovalExecutionService = signingApprovalExecutionService;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 每 2 分钟推进一批等待审批的直通退款。
     *
     * <p>cron 可用 {@code nexus.gateway.refund.approval-execution-cron} 覆盖。</p>
     */
    @Scheduled(cron = "${nexus.gateway.refund.approval-execution-cron:0 */2 * * * *}")
    @SchedulerLock(name = "paymentRefundApprovalExecution",
            lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void advanceAwaitingApprovals() {
        List<Refund> awaiting = refundRepository.findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                Refund.RefundStatus.AWAITING_APPROVAL, LocalDateTime.now().minus(SETTLE_BEFORE));
        if (awaiting.isEmpty()) {
            return;
        }

        int completed = 0;
        int failed = 0;
        int manual = 0;
        int waiting = 0;
        for (Refund refund : awaiting) {
            try {
                SigningOutcome outcome = signingApprovalExecutionService.refresh(
                        REQUEST_KEY_PREFIX + refund.getRefundNo());
                if (outcome == null || outcome.requiresManualVerification()) {
                    String reason = outcome == null
                            ? "approval execution link missing"
                            : outcome.error();
                    markManualVerification(refund.getId(), reason);
                    manual++;
                } else if (outcome.isExecuted()) {
                    completeRefund(refund.getId(), outcome.txHash());
                    completed++;
                } else if (outcome.isFailed()) {
                    failRefund(refund.getId(), outcome.error());
                    failed++;
                } else {
                    waiting++;
                }
            } catch (RuntimeException e) {
                log.error("推进待审批直通退款失败（不影响其他退款）: refundId={}, refundNo={}, error={}",
                        refund.getId(), refund.getRefundNo(), e.getMessage(), e);
            }
        }
        log.info("直通退款审批推进完成: candidates={}, completed={}, failed={}, manual={}, waiting={}",
                awaiting.size(), completed, failed, manual, waiting);
        if (manual > 0) {
            log.error("有 {} 笔直通退款结果不可核对，已置 RECONCILIATION_NEEDED，需人工核对链上交易", manual);
        }
    }

    private void completeRefund(Long refundId, String txHash) {
        transactionTemplate.executeWithoutResult(status -> {
            Refund refund = refundRepository.findById(refundId).orElse(null);
            if (refund == null || refund.getStatus() != Refund.RefundStatus.AWAITING_APPROVAL) {
                return;
            }
            if (txHash == null || txHash.isBlank()) {
                // 理论不可达（EXECUTED 必有哈希）；保守起见转人工核对而不是落 COMPLETED。
                refund.setStatus(Refund.RefundStatus.RECONCILIATION_NEEDED);
                refundRepository.save(refund);
                log.error("直通退款审批链接 EXECUTED 但无交易哈希，转人工核对: refundId={}", refundId);
                return;
            }
            refund.setChainTxHash(txHash);
            refund.setStatus(Refund.RefundStatus.COMPLETED);
            refund.setCompletedAt(LocalDateTime.now());
            refundRepository.save(refund);

            PaymentOrder order = orderRepository.findById(refund.getOrderId()).orElse(null);
            if (order != null && order.getStatus() != PaymentOrder.OrderStatus.REFUNDED) {
                OrderStateMachine.transition(order, PaymentOrder.OrderStatus.REFUNDED);
                orderRepository.save(order);
            }
            eventPublisher.publishEvent(new RefundCompletedEvent(
                    this, refund.getOrderId(),
                    order == null ? null : order.getOrderNo(),
                    refund.getMerchantId(), refund.getRefundNo(),
                    refund.getAmount().toPlainString(), txHash));
            log.info("直通退款审批执行完成: refundId={}, refundNo={}, txHash={}",
                    refundId, refund.getRefundNo(), txHash);
        });
    }

    private void failRefund(Long refundId, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            Refund refund = refundRepository.findById(refundId).orElse(null);
            if (refund == null || refund.getStatus() != Refund.RefundStatus.AWAITING_APPROVAL) {
                return;
            }
            refund.setStatus(Refund.RefundStatus.FAILED);
            refundRepository.save(refund);

            PaymentOrder order = orderRepository.findById(refund.getOrderId()).orElse(null);
            if (order != null && order.getStatus() == PaymentOrder.OrderStatus.REFUND_PENDING) {
                OrderStateMachine.transition(order, PaymentOrder.OrderStatus.PAID);
                orderRepository.save(order);
            }
            log.warn("直通退款审批失败（确定未动账）: refundId={}, reason={}", refundId, reason);
        });
    }

    private void markManualVerification(Long refundId, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            Refund refund = refundRepository.findById(refundId).orElse(null);
            if (refund == null || refund.getStatus() != Refund.RefundStatus.AWAITING_APPROVAL) {
                return;
            }
            refund.setStatus(Refund.RefundStatus.RECONCILIATION_NEEDED);
            refundRepository.save(refund);
            log.error("直通退款结果不可核对，转 RECONCILIATION_NEEDED（禁止自动重试）: "
                    + "refundId={}, reason={}", refundId, reason);
        });
    }
}
