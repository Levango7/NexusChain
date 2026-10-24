package org.nexus.gateway.refund;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RefundApprovalExecutionReconciler} 单元测试：验证批量推进的驱动与容错。
 */
@ExtendWith(MockitoExtension.class)
class RefundApprovalExecutionReconcilerTest {

    @Mock
    private RefundRequestRepository refundRequestRepository;

    @Mock
    private RefundApprovalService refundApprovalService;

    private RefundApprovalExecutionReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new RefundApprovalExecutionReconciler(
                refundRequestRepository, refundApprovalService);
    }

    @Test
    @DisplayName("无待审批退款 -> 不调用执行服务")
    void noCandidates() {
        when(refundRequestRepository.findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                eq(RefundRequest.RefundStatus.AWAITING_APPROVAL), any()))
                .thenReturn(List.of());

        reconciler.advanceAwaitingApprovals();

        verify(refundApprovalService, never()).executeRefund(any());
    }

    @Test
    @DisplayName("逐笔推进；单笔异常不阻断后续")
    void advancesEachRefundAndIsolatesFailures() {
        RefundRequest first = refund(1L, "RF-1");
        RefundRequest second = refund(2L, "RF-2");
        when(refundRequestRepository.findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                eq(RefundRequest.RefundStatus.AWAITING_APPROVAL), any()))
                .thenReturn(List.of(first, second));

        when(refundApprovalService.executeRefund(1L)).thenThrow(new IllegalStateException("claim lost"));
        RefundRequest executed = refund(2L, "RF-2");
        executed.setStatus(RefundRequest.RefundStatus.EXECUTED);
        when(refundApprovalService.executeRefund(2L)).thenReturn(executed);

        reconciler.advanceAwaitingApprovals();

        verify(refundApprovalService, times(2)).executeRefund(any());
    }

    private static RefundRequest refund(Long id, String refundNo) {
        RefundRequest request = new RefundRequest();
        request.setId(id);
        request.setRefundNo(refundNo);
        request.setOrderId(100L);
        request.setMerchantId(200L);
        request.setAmount(new BigDecimal("50000"));
        request.setStatus(RefundRequest.RefundStatus.AWAITING_APPROVAL);
        request.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        request.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        return request;
    }
}
