package org.nexus.gateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.approval.ApprovalExecutionLink;
import org.nexus.gateway.approval.SigningApprovalExecutionService;
import org.nexus.gateway.approval.SigningOutcome;
import org.nexus.gateway.event.RefundCompletedEvent;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.model.Refund;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.gateway.repository.RefundRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PaymentRefundApprovalReconciler} 单元测试：直通退款在审批通过/拒绝/
 * 结果不可核对三种结局下的落账与订单状态。
 */
@ExtendWith(MockitoExtension.class)
class PaymentRefundApprovalReconcilerTest {

    private static final String REFUND_NO = "RF-123";

    @Mock private RefundRepository refundRepository;
    @Mock private PaymentOrderRepository orderRepository;
    @Mock private SigningApprovalExecutionService signingApprovalExecutionService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PlatformTransactionManager transactionManager;

    private PaymentRefundApprovalReconciler reconciler;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any()))
                .thenAnswer(inv -> new SimpleTransactionStatus());
        reconciler = new PaymentRefundApprovalReconciler(refundRepository, orderRepository,
                signingApprovalExecutionService, eventPublisher, transactionManager);
    }

    @Test
    @DisplayName("EXECUTED -> 退款 COMPLETED + 订单 REFUNDED + 发布完成事件")
    void executedCompletesRefundAndOrder() {
        Refund refund = awaitingRefund();
        PaymentOrder order = order(PaymentOrder.OrderStatus.REFUND_PENDING);
        stubCandidate(refund);
        when(orderRepository.findById(2L)).thenReturn(Optional.of(order));
        when(signingApprovalExecutionService.refresh(
                PaymentRefundApprovalReconciler.REQUEST_KEY_PREFIX + REFUND_NO))
                .thenReturn(new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED,
                        "ap-1", "TX-OK-1", null));

        reconciler.advanceAwaitingApprovals();

        assertEquals(Refund.RefundStatus.COMPLETED, refund.getStatus());
        assertEquals("TX-OK-1", refund.getChainTxHash());
        assertEquals(PaymentOrder.OrderStatus.REFUNDED, order.getStatus());
        verify(eventPublisher).publishEvent(any(RefundCompletedEvent.class));
    }

    @Test
    @DisplayName("FAILED -> 退款 FAILED + 订单回 PAID（确定未动账）")
    void failedMarksRefundFailedAndRestoresOrder() {
        Refund refund = awaitingRefund();
        PaymentOrder order = order(PaymentOrder.OrderStatus.REFUND_PENDING);
        stubCandidate(refund);
        when(orderRepository.findById(2L)).thenReturn(Optional.of(order));
        when(signingApprovalExecutionService.refresh(any()))
                .thenReturn(new SigningOutcome(ApprovalExecutionLink.Status.FAILED,
                        "ap-1", null, "approval rejected"));

        reconciler.advanceAwaitingApprovals();

        assertEquals(Refund.RefundStatus.FAILED, refund.getStatus());
        assertEquals(PaymentOrder.OrderStatus.PAID, order.getStatus());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("EXECUTED_UNVERIFIED -> RECONCILIATION_NEEDED，订单保持 REFUND_PENDING，不发完成事件")
    void unverifiedGoesManual() {
        Refund refund = awaitingRefund();
        stubCandidate(refund);
        when(signingApprovalExecutionService.refresh(any()))
                .thenReturn(new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED_UNVERIFIED,
                        "ap-1", null, "no txHash"));

        reconciler.advanceAwaitingApprovals();

        assertEquals(Refund.RefundStatus.RECONCILIATION_NEEDED, refund.getStatus());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("链接缺失 -> 保守转 RECONCILIATION_NEEDED（不猜成功/失败）")
    void missingLinkGoesManual() {
        Refund refund = awaitingRefund();
        stubCandidate(refund);
        when(signingApprovalExecutionService.refresh(any())).thenReturn(null);

        reconciler.advanceAwaitingApprovals();

        assertEquals(Refund.RefundStatus.RECONCILIATION_NEEDED, refund.getStatus());
    }

    @Test
    @DisplayName("仍在审批 -> 状态不变")
    void stillAwaitingLeavesRefundUntouched() {
        Refund refund = awaitingRefund();
        stubCandidate(refund);
        when(signingApprovalExecutionService.refresh(any()))
                .thenReturn(new SigningOutcome(ApprovalExecutionLink.Status.AWAITING_APPROVAL,
                        "ap-1", null, null));

        reconciler.advanceAwaitingApprovals();

        assertEquals(Refund.RefundStatus.AWAITING_APPROVAL, refund.getStatus());
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("已不在 AWAITING_APPROVAL 的退款（被其他实例推进）不覆盖")
    void statusChangedByAnotherInstanceIsSkipped() {
        Refund refund = awaitingRefund();
        refund.setStatus(Refund.RefundStatus.COMPLETED);
        stubCandidate(refund);
        when(signingApprovalExecutionService.refresh(any()))
                .thenReturn(new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED,
                        "ap-1", "TX-OK-1", null));

        reconciler.advanceAwaitingApprovals();

        assertEquals(Refund.RefundStatus.COMPLETED, refund.getStatus());
        verify(refundRepository, never()).save(any());
    }

    private void stubCandidate(Refund refund) {
        when(refundRepository.findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                eq(Refund.RefundStatus.AWAITING_APPROVAL), any()))
                .thenReturn(List.of(refund));
        lenient().when(refundRepository.findById(1L)).thenReturn(Optional.of(refund));
    }

    private static Refund awaitingRefund() {
        Refund refund = new Refund();
        refund.setId(1L);
        refund.setRefundNo(REFUND_NO);
        refund.setOrderId(2L);
        refund.setMerchantId(3L);
        refund.setAmount(new BigDecimal("20000"));
        refund.setStatus(Refund.RefundStatus.AWAITING_APPROVAL);
        refund.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        refund.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        return refund;
    }

    private static PaymentOrder order(PaymentOrder.OrderStatus status) {
        PaymentOrder order = new PaymentOrder();
        order.setId(2L);
        order.setOrderNo("NEX-ORDER-2");
        order.setStatus(status);
        return order;
    }
}
