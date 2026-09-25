package org.nexus.gateway.voidreversal;

import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * Unit tests for {@link VoidService}.
 * Covers void request creation, approval, rejection, and void window validation.
 */
@ExtendWith(MockitoExtension.class)
class VoidServiceTest {

    @Mock private VoidRequestRepository voidRequestRepository;
    @Mock private PaymentOrderRepository paymentOrderRepository;
    @Mock private AccountService accountService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private VoidService voidService;

    private PaymentOrder sampleOrder;

    @BeforeEach
    void setUp() {
        voidService = new VoidService(voidRequestRepository, paymentOrderRepository, accountService, eventPublisher);

        sampleOrder = new PaymentOrder();
        sampleOrder.setId(1L);
        sampleOrder.setOrderNo("NEX-ORDER-001");
        sampleOrder.setMerchantId(100L);
        sampleOrder.setAmount(new BigDecimal("1000"));
        sampleOrder.setStatus(PaymentOrder.OrderStatus.PAID);
        sampleOrder.setPaidAt(LocalDateTime.now().minusHours(2));
    }

    // ==================== Exception Scenario Tests ====================

    @Test
    @DisplayName("requestVoid: order not found throws IllegalArgumentException")
    void requestVoid_orderNotFound_throws() {
        when(paymentOrderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> voidService.requestVoid(99L, "reason", "operator-1"));
    }

    @Test
    @DisplayName("requestVoid: non-PAID order throws IllegalStateException")
    void requestVoid_nonPaidOrder_throws() {
        sampleOrder.setStatus(PaymentOrder.OrderStatus.PENDING);
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        assertThrows(IllegalStateException.class,
                () -> voidService.requestVoid(1L, "reason", "operator-1"));
    }

    @Test
    @DisplayName("requestVoid: order with REFUND_PENDING status throws IllegalStateException")
    void requestVoid_refundPendingOrder_throws() {
        sampleOrder.setStatus(PaymentOrder.OrderStatus.REFUND_PENDING);
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        assertThrows(IllegalStateException.class,
                () -> voidService.requestVoid(1L, "reason", "operator-1"));
    }

    @Test
    @DisplayName("requestVoid: order with REFUNDED status throws IllegalStateException")
    void requestVoid_refundedOrder_throws() {
        sampleOrder.setStatus(PaymentOrder.OrderStatus.REFUNDED);
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        assertThrows(IllegalStateException.class,
                () -> voidService.requestVoid(1L, "reason", "operator-1"));
    }

    @Test
    @DisplayName("requestVoid: past void window throws IllegalStateException")
    void requestVoid_pastVoidWindow_throws() {
        // paidAt is 3 days ago, past the T+1 void window
        sampleOrder.setPaidAt(LocalDateTime.now().minusDays(3));
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        assertThrows(IllegalStateException.class,
                () -> voidService.requestVoid(1L, "reason", "operator-1"));
    }

    @Test
    @DisplayName("requestVoid: existing PENDING void request throws IllegalStateException")
    void requestVoid_existingPendingRequest_throws() {
        VoidRequest existing = new VoidRequest();
        existing.setStatus(VoidStatus.PENDING);
        existing.setVoidNo("VD001");
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(voidRequestRepository.findByOrderId(1L)).thenReturn(List.of(existing));

        assertThrows(IllegalStateException.class,
                () -> voidService.requestVoid(1L, "reason", "operator-1"));
    }

    @Test
    @DisplayName("requestVoid: existing COMPLETED void request throws IllegalStateException")
    void requestVoid_existingCompletedRequest_throws() {
        VoidRequest existing = new VoidRequest();
        existing.setStatus(VoidStatus.COMPLETED);
        existing.setVoidNo("VD001");
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(voidRequestRepository.findByOrderId(1L)).thenReturn(List.of(existing));

        assertThrows(IllegalStateException.class,
                () -> voidService.requestVoid(1L, "reason", "operator-1"));
    }

    @Test
    @DisplayName("approveVoid: void request not found throws IllegalArgumentException")
    void approveVoid_notFound_throws() {
        when(voidRequestRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> voidService.approveVoid(99L));
    }

    @Test
    @DisplayName("approveVoid: non-PENDING/APPROVED status throws IllegalStateException")
    void approveVoid_wrongStatus_throws() {
        VoidRequest voidReq = new VoidRequest();
        voidReq.setId(1L);
        voidReq.setStatus(VoidStatus.COMPLETED);
        when(voidRequestRepository.findById(1L)).thenReturn(Optional.of(voidReq));

        assertThrows(IllegalStateException.class,
                () -> voidService.approveVoid(1L));
    }

    @Test
    @DisplayName("rejectVoid: non-PENDING status throws IllegalStateException")
    void rejectVoid_wrongStatus_throws() {
        VoidRequest voidReq = new VoidRequest();
        voidReq.setId(1L);
        voidReq.setStatus(VoidStatus.COMPLETED);
        when(voidRequestRepository.findById(1L)).thenReturn(Optional.of(voidReq));

        assertThrows(IllegalStateException.class,
                () -> voidService.rejectVoid(1L, "reason"));
    }

    @Test
    @DisplayName("approveVoid: order status changed to non-PAID → void FAILED")
    void approveVoid_orderStatusChanged_voidFailed() {
        VoidRequest voidReq = createPendingVoidRequest();
        sampleOrder.setStatus(PaymentOrder.OrderStatus.VOIDED);
        when(voidRequestRepository.findById(1L)).thenReturn(Optional.of(voidReq));
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(voidRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        VoidRequest result = voidService.approveVoid(1L);

        assertEquals(VoidStatus.FAILED, result.getStatus());
        assertNotNull(result.getCompletedAt());
    }

    @Test
    @DisplayName("approveVoid: insufficient balance → void FAILED")
    void approveVoid_insufficientBalance_voidFailed() {
        VoidRequest voidReq = createPendingVoidRequest();
        when(voidRequestRepository.findById(1L)).thenReturn(Optional.of(voidReq));
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(accountService.withdraw(100L, new BigDecimal("1000")))
                .thenThrow(new IllegalStateException("余额不足"));
        when(voidRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        VoidRequest result = voidService.approveVoid(1L);

        assertEquals(VoidStatus.FAILED, result.getStatus());
    }

    // ==================== Normal Business Flow Tests ====================

    @Test
    @DisplayName("requestVoid: valid request creates PENDING void")
    void requestVoid_validRequest_createsPending() {
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(voidRequestRepository.findByOrderId(1L)).thenReturn(Collections.emptyList());
        when(voidRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        VoidRequest result = voidService.requestVoid(1L, "customer request", "operator-1");

        assertNotNull(result);
        assertEquals(VoidStatus.PENDING, result.getStatus());
        assertEquals(1L, result.getOrderId());
        assertEquals(100L, result.getMerchantId());
        assertEquals(new BigDecimal("1000"), result.getAmount());
        assertEquals("customer request", result.getReason());
        assertEquals("operator-1", result.getOperatorId());
        assertNotNull(result.getVoidNo());
        assertTrue(result.getVoidNo().startsWith("VD"));
    }

    @Test
    @DisplayName("approveVoid: valid approval completes void and updates order to VOIDED")
    void approveVoid_validApproval_completesVoid() {
        VoidRequest voidReq = createPendingVoidRequest();
        MerchantAccount account = new MerchantAccount();
        account.setBalance(new BigDecimal("0"));

        when(voidRequestRepository.findById(1L)).thenReturn(Optional.of(voidReq));
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(accountService.withdraw(100L, new BigDecimal("1000"))).thenReturn(account);
        when(paymentOrderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(voidRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        VoidRequest result = voidService.approveVoid(1L);

        assertEquals(VoidStatus.COMPLETED, result.getStatus());
        assertNotNull(result.getCompletedAt());
        assertEquals(PaymentOrder.OrderStatus.VOIDED, sampleOrder.getStatus());
        verify(eventPublisher).publishEvent(any(OrderVoidedEvent.class));
    }

    @Test
    @DisplayName("rejectVoid: valid rejection updates status to REJECTED")
    void rejectVoid_validRejection_updatesStatus() {
        VoidRequest voidReq = createPendingVoidRequest();
        when(voidRequestRepository.findById(1L)).thenReturn(Optional.of(voidReq));
        when(voidRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        VoidRequest result = voidService.rejectVoid(1L, "invalid request");

        assertEquals(VoidStatus.REJECTED, result.getStatus());
        assertNotNull(result.getCompletedAt());
        assertTrue(result.getReason().contains("拒绝原因"));
    }

    @Test
    @DisplayName("rejectVoid: null reason still rejects without appending")
    void rejectVoid_nullReason_stillRejects() {
        VoidRequest voidReq = createPendingVoidRequest();
        voidReq.setReason("original reason");
        when(voidRequestRepository.findById(1L)).thenReturn(Optional.of(voidReq));
        when(voidRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        VoidRequest result = voidService.rejectVoid(1L, null);

        assertEquals(VoidStatus.REJECTED, result.getStatus());
        assertEquals("original reason", result.getReason());
    }

    @Test
    @DisplayName("findById: returns void request when found")
    void findById_found_returnsRequest() {
        VoidRequest voidReq = createPendingVoidRequest();
        when(voidRequestRepository.findById(1L)).thenReturn(Optional.of(voidReq));

        VoidRequest result = voidService.findById(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
    }

    @Test
    @DisplayName("findById: returns null when not found")
    void findById_notFound_returnsNull() {
        when(voidRequestRepository.findById(99L)).thenReturn(Optional.empty());

        VoidRequest result = voidService.findById(99L);

        assertNull(result);
    }

    @Test
    @DisplayName("findByVoidNo: returns void request when found")
    void findByVoidNo_found_returnsRequest() {
        VoidRequest voidReq = createPendingVoidRequest();
        when(voidRequestRepository.findByVoidNo("VD001")).thenReturn(Optional.of(voidReq));

        VoidRequest result = voidService.findByVoidNo("VD001");

        assertNotNull(result);
    }

    @Test
    @DisplayName("findByOrderId: returns list of void requests")
    void findByOrderId_returnsList() {
        VoidRequest voidReq = createPendingVoidRequest();
        when(voidRequestRepository.findByOrderId(1L)).thenReturn(List.of(voidReq));

        List<VoidRequest> result = voidService.findByOrderId(1L);

        assertEquals(1, result.size());
    }

    // ==================== Helper Methods ====================

    private VoidRequest createPendingVoidRequest() {
        VoidRequest voidReq = new VoidRequest();
        voidReq.setId(1L);
        voidReq.setVoidNo("VD001");
        voidReq.setOrderId(1L);
        voidReq.setMerchantId(100L);
        voidReq.setAmount(new BigDecimal("1000"));
        voidReq.setStatus(VoidStatus.PENDING);
        voidReq.setReason("test reason");
        voidReq.setOperatorId("operator-1");
        return voidReq;
    }
}