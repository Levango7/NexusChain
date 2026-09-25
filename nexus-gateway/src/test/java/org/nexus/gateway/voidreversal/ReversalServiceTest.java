package org.nexus.gateway.voidreversal;

import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReversalService}.
 * Covers reversal request creation (manual/auto), approval, rejection.
 */
@ExtendWith(MockitoExtension.class)
class ReversalServiceTest {

    @Mock private ReversalRequestRepository reversalRequestRepository;
    @Mock private PaymentOrderRepository paymentOrderRepository;
    @Mock private AccountService accountService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ReversalService reversalService;

    private PaymentOrder sampleOrder;

    @BeforeEach
    void setUp() {
        reversalService = new ReversalService(reversalRequestRepository, paymentOrderRepository,
                accountService, eventPublisher);

        sampleOrder = new PaymentOrder();
        sampleOrder.setId(1L);
        sampleOrder.setOrderNo("NEX-ORDER-001");
        sampleOrder.setMerchantId(100L);
        sampleOrder.setAmount(new BigDecimal("1000"));
        sampleOrder.setStatus(PaymentOrder.OrderStatus.PAID);
        sampleOrder.setPaidAt(LocalDateTime.now().minusDays(5));
    }

    // ==================== Exception Scenario Tests ====================

    @Test
    @DisplayName("requestReversal: order not found throws IllegalArgumentException")
    void requestReversal_orderNotFound_throws() {
        when(paymentOrderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> reversalService.requestReversal(99L, "reason", "operator-1"));
    }

    @Test
    @DisplayName("requestReversal: non-PAID order throws IllegalStateException")
    void requestReversal_nonPaidOrder_throws() {
        sampleOrder.setStatus(PaymentOrder.OrderStatus.PENDING);
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        assertThrows(IllegalStateException.class,
                () -> reversalService.requestReversal(1L, "reason", "operator-1"));
    }

    @Test
    @DisplayName("requestReversal: existing PENDING reversal throws IllegalStateException")
    void requestReversal_existingPending_throws() {
        ReversalRequest existing = new ReversalRequest();
        existing.setStatus(ReversalStatus.PENDING);
        existing.setReversalNo("RV001");
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(reversalRequestRepository.findByOrderId(1L)).thenReturn(List.of(existing));

        assertThrows(IllegalStateException.class,
                () -> reversalService.requestReversal(1L, "reason", "operator-1"));
    }

    @Test
    @DisplayName("requestReversal: existing COMPLETED reversal throws IllegalStateException")
    void requestReversal_existingCompleted_throws() {
        ReversalRequest existing = new ReversalRequest();
        existing.setStatus(ReversalStatus.COMPLETED);
        existing.setReversalNo("RV001");
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(reversalRequestRepository.findByOrderId(1L)).thenReturn(List.of(existing));

        assertThrows(IllegalStateException.class,
                () -> reversalService.requestReversal(1L, "reason", "operator-1"));
    }

    @Test
    @DisplayName("approveReversal: not found throws IllegalArgumentException")
    void approveReversal_notFound_throws() {
        when(reversalRequestRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> reversalService.approveReversal(99L));
    }

    @Test
    @DisplayName("approveReversal: non-PENDING/APPROVED status throws IllegalStateException")
    void approveReversal_wrongStatus_throws() {
        ReversalRequest req = createPendingReversal();
        req.setStatus(ReversalStatus.COMPLETED);
        when(reversalRequestRepository.findById(1L)).thenReturn(Optional.of(req));

        assertThrows(IllegalStateException.class,
                () -> reversalService.approveReversal(1L));
    }

    @Test
    @DisplayName("approveReversal: order status changed → reversal FAILED")
    void approveReversal_orderStatusChanged_reversalFailed() {
        ReversalRequest req = createPendingReversal();
        sampleOrder.setStatus(PaymentOrder.OrderStatus.REVERSED);
        when(reversalRequestRepository.findById(1L)).thenReturn(Optional.of(req));
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(reversalRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReversalRequest result = reversalService.approveReversal(1L);

        assertEquals(ReversalStatus.FAILED, result.getStatus());
        assertNotNull(result.getCompletedAt());
    }

    @Test
    @DisplayName("approveReversal: insufficient balance → reversal FAILED")
    void approveReversal_insufficientBalance_reversalFailed() {
        ReversalRequest req = createPendingReversal();
        when(reversalRequestRepository.findById(1L)).thenReturn(Optional.of(req));
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(accountService.withdraw(100L, new BigDecimal("1000")))
                .thenThrow(new IllegalStateException("余额不足"));
        when(reversalRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReversalRequest result = reversalService.approveReversal(1L);

        assertEquals(ReversalStatus.FAILED, result.getStatus());
    }

    @Test
    @DisplayName("rejectReversal: non-PENDING status throws IllegalStateException")
    void rejectReversal_wrongStatus_throws() {
        ReversalRequest req = createPendingReversal();
        req.setStatus(ReversalStatus.COMPLETED);
        when(reversalRequestRepository.findById(1L)).thenReturn(Optional.of(req));

        assertThrows(IllegalStateException.class,
                () -> reversalService.rejectReversal(1L, "reason"));
    }

    // ==================== Normal Business Flow Tests ====================

    @Test
    @DisplayName("requestReversal: manual reversal creates PENDING request")
    void requestReversal_manual_createsPending() {
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(reversalRequestRepository.findByOrderId(1L)).thenReturn(Collections.emptyList());
        when(reversalRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReversalRequest result = reversalService.requestReversal(1L, "settlement error", "operator-1");

        assertNotNull(result);
        assertEquals(ReversalStatus.PENDING, result.getStatus());
        assertEquals(ReversalType.MANUAL, result.getReversalType());
        assertEquals(1L, result.getOrderId());
        assertEquals(100L, result.getMerchantId());
        assertEquals(new BigDecimal("1000"), result.getAmount());
        assertNotNull(result.getReversalNo());
        assertTrue(result.getReversalNo().startsWith("RV"));
    }

    @Test
    @DisplayName("autoReversal: auto reversal creates PENDING request with AUTO type")
    void autoReversal_createsAutoType() {
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(reversalRequestRepository.findByOrderId(1L)).thenReturn(Collections.emptyList());
        when(reversalRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReversalRequest result = reversalService.autoReversal(1L, "auto detected anomaly");

        assertNotNull(result);
        assertEquals(ReversalStatus.PENDING, result.getStatus());
        assertEquals(ReversalType.AUTO, result.getReversalType());
        assertEquals("SYSTEM", result.getOperatorId());
    }

    @Test
    @DisplayName("approveReversal: valid approval completes reversal")
    void approveReversal_validApproval_completes() {
        ReversalRequest req = createPendingReversal();
        MerchantAccount account = new MerchantAccount();
        account.setBalance(new BigDecimal("0"));

        when(reversalRequestRepository.findById(1L)).thenReturn(Optional.of(req));
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(accountService.withdraw(100L, new BigDecimal("1000"))).thenReturn(account);
        when(paymentOrderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reversalRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReversalRequest result = reversalService.approveReversal(1L);

        assertEquals(ReversalStatus.COMPLETED, result.getStatus());
        assertNotNull(result.getCompletedAt());
        assertEquals(PaymentOrder.OrderStatus.REVERSED, sampleOrder.getStatus());
        verify(eventPublisher).publishEvent(any(OrderReversedEvent.class));
    }

    @Test
    @DisplayName("rejectReversal: valid rejection updates status to REJECTED")
    void rejectReversal_validRejection_updatesStatus() {
        ReversalRequest req = createPendingReversal();
        when(reversalRequestRepository.findById(1L)).thenReturn(Optional.of(req));
        when(reversalRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReversalRequest result = reversalService.rejectReversal(1L, "invalid request");

        assertEquals(ReversalStatus.REJECTED, result.getStatus());
        assertNotNull(result.getCompletedAt());
        assertTrue(result.getReason().contains("拒绝原因"));
    }

    @Test
    @DisplayName("rejectReversal: blank reason still rejects without appending")
    void rejectReversal_blankReason_stillRejects() {
        ReversalRequest req = createPendingReversal();
        req.setReason("original reason");
        when(reversalRequestRepository.findById(1L)).thenReturn(Optional.of(req));
        when(reversalRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReversalRequest result = reversalService.rejectReversal(1L, "  ");

        assertEquals(ReversalStatus.REJECTED, result.getStatus());
        assertEquals("original reason", result.getReason());
    }

    @Test
    @DisplayName("findById: returns reversal when found")
    void findById_found_returnsRequest() {
        ReversalRequest req = createPendingReversal();
        when(reversalRequestRepository.findById(1L)).thenReturn(Optional.of(req));

        ReversalRequest result = reversalService.findById(1L);

        assertNotNull(result);
    }

    @Test
    @DisplayName("findById: returns null when not found")
    void findById_notFound_returnsNull() {
        when(reversalRequestRepository.findById(99L)).thenReturn(Optional.empty());

        ReversalRequest result = reversalService.findById(99L);

        assertNull(result);
    }

    @Test
    @DisplayName("findByReversalNo: returns reversal when found")
    void findByReversalNo_found_returnsRequest() {
        ReversalRequest req = createPendingReversal();
        when(reversalRequestRepository.findByReversalNo("RV001")).thenReturn(Optional.of(req));

        ReversalRequest result = reversalService.findByReversalNo("RV001");

        assertNotNull(result);
    }

    @Test
    @DisplayName("findByOrderId: returns list of reversal requests")
    void findByOrderId_returnsList() {
        ReversalRequest req = createPendingReversal();
        when(reversalRequestRepository.findByOrderId(1L)).thenReturn(List.of(req));

        List<ReversalRequest> result = reversalService.findByOrderId(1L);

        assertEquals(1, result.size());
    }

    // ==================== Helper Methods ====================

    private ReversalRequest createPendingReversal() {
        ReversalRequest req = new ReversalRequest();
        req.setId(1L);
        req.setReversalNo("RV001");
        req.setOrderId(1L);
        req.setMerchantId(100L);
        req.setAmount(new BigDecimal("1000"));
        req.setStatus(ReversalStatus.PENDING);
        req.setReversalType(ReversalType.MANUAL);
        req.setReason("test reason");
        req.setOperatorId("operator-1");
        return req;
    }
}