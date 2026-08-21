package org.nexus.gateway.refund;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.settlement.execution.OnChainExecutionChannel;
import org.nexus.settlement.execution.TransactionRequest;
import org.nexus.settlement.execution.TransactionResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link DefaultRefundApprovalService} 与 {@link DefaultRefundPolicy} 单元测试：
 * 验证退款请求校验、审批/拒绝状态流转与执行。
 */
@ExtendWith(MockitoExtension.class)
class DefaultRefundApprovalServiceTest {

    @Mock private RefundRequestRepository refundRequestRepository;
    @Mock private PaymentOrderRepository paymentOrderRepository;
    @Mock private OnChainExecutionChannel executionChannel;

    private DefaultRefundPolicy policy;
    private DefaultRefundApprovalService service;

    private PaymentOrder paidOrder;

    @BeforeEach
    void setUp() throws Exception {
        policy = new DefaultRefundPolicy();
        setField(policy, "refundWindowDays", 7L);
        service = new DefaultRefundApprovalService(refundRequestRepository, paymentOrderRepository,
                policy, executionChannel, "PLATFORM_HOT_WALLET");

        paidOrder = new PaymentOrder();
        paidOrder.setId(1L);
        paidOrder.setOrderNo("NEX-ORDER-001");
        paidOrder.setMerchantId(100L);
        paidOrder.setAmount(new BigDecimal("1000000"));
        paidOrder.setStatus(PaymentOrder.OrderStatus.PAID);
        paidOrder.setPaidAt(LocalDateTime.now().minusDays(1));
    }

    // --- DefaultRefundPolicy ---

    @Test
    void policy_canRefund_paidOrder() {
        assertTrue(policy.canRefund(paidOrder));
    }

    @Test
    void policy_canRefund_pendingOrderFalse() {
        PaymentOrder pending = new PaymentOrder();
        pending.setStatus(PaymentOrder.OrderStatus.PENDING);
        assertFalse(policy.canRefund(pending));
    }

    @Test
    void policy_maxRefundAmount_equalsOrderAmount() {
        assertEquals(0, new BigDecimal("1000000").compareTo(policy.getMaxRefundAmount(paidOrder)));
    }

    @Test
    void policy_refundWindow_withinWindow() {
        assertFalse(policy.getRefundWindow(paidOrder).isZero());
    }

    @Test
    void policy_refundWindow_expired() {
        paidOrder.setPaidAt(LocalDateTime.now().minusDays(30));
        assertTrue(policy.getRefundWindow(paidOrder).isZero());
    }

    // --- requestRefund ---

    @Test
    void requestRefund_validCreatesPending() {
        when(paymentOrderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(paidOrder));
        when(refundRequestRepository.sumPendingRefundsByOrderId(1L)).thenReturn(BigDecimal.ZERO);
        when(refundRequestRepository.save(any())).thenAnswer(inv -> {
            RefundRequest r = inv.getArgument(0);
            r.setId(10L);
            return r;
        });

        RefundRequest result = service.requestRefund(1L, new BigDecimal("500"), "damaged");

        assertEquals(RefundRequest.RefundStatus.PENDING, result.getStatus());
        assertEquals(1L, result.getOrderId());
        assertEquals(100L, result.getMerchantId());
        assertNotNull(result.getRefundNo());
    }

    @Test
    void requestRefund_amountExceedsMaxThrows() {
        when(paymentOrderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(paidOrder));
        assertThrows(IllegalArgumentException.class,
                () -> service.requestRefund(1L, new BigDecimal("2000000"), "too much"));
    }

    @Test
    void requestRefund_ineligibleOrderThrows() {
        PaymentOrder pending = new PaymentOrder();
        pending.setId(2L);
        pending.setStatus(PaymentOrder.OrderStatus.PENDING);
        when(paymentOrderRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(pending));

        assertThrows(IllegalStateException.class,
                () -> service.requestRefund(2L, new BigDecimal("100"), "x"));
    }

    @Test
    void requestRefund_windowExpiredThrows() {
        paidOrder.setPaidAt(LocalDateTime.now().minusDays(30));
        when(paymentOrderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(paidOrder));
        when(refundRequestRepository.sumPendingRefundsByOrderId(1L)).thenReturn(BigDecimal.ZERO);

        assertThrows(IllegalStateException.class,
                () -> service.requestRefund(1L, new BigDecimal("100"), "late"));
    }

    // --- approveRefund / rejectRefund ---

    @Test
    void approveRefund_pendingBecomesApproved() {
        RefundRequest pending = newPendingRequest();
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(pending));
        when(refundRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RefundRequest result = service.approveRefund(10L, "approver-1");

        assertEquals(RefundRequest.RefundStatus.APPROVED, result.getStatus());
        assertEquals("approver-1", result.getApproverId());
        assertNotNull(result.getApprovedAt());
    }

    @Test
    void approveRefund_nonPendingThrows() {
        RefundRequest approved = newPendingRequest();
        approved.setStatus(RefundRequest.RefundStatus.APPROVED);
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(approved));

        assertThrows(IllegalStateException.class, () -> service.approveRefund(10L, "approver-1"));
    }

    @Test
    void rejectRefund_pendingBecomesRejected() {
        RefundRequest pending = newPendingRequest();
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(pending));
        when(refundRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RefundRequest result = service.rejectRefund(10L, "approver-1", "fraud");

        assertEquals(RefundRequest.RefundStatus.REJECTED, result.getStatus());
        assertEquals("fraud", result.getRejectionReason());
    }

    // --- executeRefund ---

    @Test
    void executeRefund_onChainSuccessMarksExecuted() {
        RefundRequest approved = newPendingRequest();
        approved.setStatus(RefundRequest.RefundStatus.APPROVED);
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(approved));
        when(refundRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(executionChannel.execute(any(TransactionRequest.class)))
                .thenReturn(TransactionResult.success("TX-ABC-123", 12, false));

        RefundRequest result = service.executeRefund(10L);

        // 链上退款执行成功：请求置 EXECUTED 并记录真实交易哈希
        assertEquals(RefundRequest.RefundStatus.EXECUTED, result.getStatus());
        assertEquals("TX-ABC-123", result.getChainTxHash());
        assertNotNull(result.getExecutedAt());
    }

    @Test
    void executeRefund_onChainFailureMarksFailed() {
        RefundRequest approved = newPendingRequest();
        approved.setStatus(RefundRequest.RefundStatus.APPROVED);
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(approved));
        when(refundRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(executionChannel.execute(any(TransactionRequest.class)))
                .thenReturn(TransactionResult.failure("broadcast rejected", false));

        RefundRequest result = service.executeRefund(10L);

        // 链上退款执行失败：请求置 FAILED 且不产生交易哈希
        assertEquals(RefundRequest.RefundStatus.FAILED, result.getStatus());
        assertNull(result.getChainTxHash());
        assertNotNull(result.getRejectionReason());
        assertTrue(result.getRejectionReason().contains("broadcast rejected"));
    }

    @Test
    void executeRefund_pendingThrows() {
        RefundRequest pending = newPendingRequest();
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(pending));

        assertThrows(IllegalStateException.class, () -> service.executeRefund(10L));
    }

    private RefundRequest newPendingRequest() {
        RefundRequest r = new RefundRequest();
        r.setId(10L);
        r.setRefundNo("RF123");
        r.setOrderId(1L);
        r.setMerchantId(100L);
        r.setAmount(new BigDecimal("500"));
        r.setStatus(RefundRequest.RefundStatus.PENDING);
        return r;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
