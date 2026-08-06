package org.nexus.gateway;

import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.client.ExchangeWalletClient;
import org.nexus.gateway.compliance.ComplianceService;
import org.nexus.gateway.compliance.KycStatus;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.dto.PaymentResult;
import org.nexus.gateway.event.PaymentConfirmedEvent;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.gateway.repository.RefundRepository;
import org.nexus.gateway.risk.PaymentRiskService;
import org.nexus.gateway.risk.RiskDecision;
import org.nexus.gateway.security.KeyManager;
import org.nexus.gateway.service.PaymentServiceImpl;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PaymentServiceImpl} with mocked dependencies.
 * Covers order state-machine transitions, expiry, chain confirmation, and refund guards.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentOrderRepository orderRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private ChainRpcClient chainRpcClient;
    @Mock private ExchangeWalletClient walletClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private KeyManager keyManager;
    @Mock private PaymentRiskService riskService;
    @Mock private ComplianceService complianceService;

    private GatewayConfig gatewayConfig;
    private PaymentServiceImpl paymentService;

    private PaymentOrder sampleOrder;

    @BeforeEach
    void setUp() {
        gatewayConfig = new GatewayConfig();
        gatewayConfig.getCheckout().setBaseUrl("http://localhost:8080/api/v1/checkout");
        paymentService = new PaymentServiceImpl(
                orderRepository, refundRepository, gatewayConfig,
                chainRpcClient, walletClient, eventPublisher, keyManager,
                riskService, complianceService);

        // Default risk/compliance stance for these unit tests: approve everything.
        // Individual tests override these mocks to verify rejection paths.
        // lenient(): not every test path reaches risk/compliance evaluation.
        lenient().when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        lenient().when(riskService.evaluateRefund(any())).thenReturn(RiskDecision.APPROVED);
        lenient().when(complianceService.checkKyc(any())).thenReturn(KycStatus.NONE);

        sampleOrder = new PaymentOrder();
        sampleOrder.setId(1L);
        sampleOrder.setOrderNo("NEX-ORDER-001");
        sampleOrder.setMerchantId(100L);
        sampleOrder.setAmount(new BigDecimal("1000000"));
        sampleOrder.setPayeeAddress("0xMerchantSettlementAddress");
        sampleOrder.setCheckoutToken("abc123");
        sampleOrder.setStatus(PaymentOrder.OrderStatus.PENDING);
        sampleOrder.setExpiresAt(LocalDateTime.now().plusMinutes(30));
    }

    @Test
    @DisplayName("initiatePayment: PENDING order transitions to PAYING and returns checkout URL")
    void initiatePayment_pendingOrder_transitionsToPaying() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any())).thenReturn(sampleOrder);

        PaymentResult result = paymentService.initiatePayment(1L, "0xPayerAddress");

        assertEquals("PENDING", result.getStatus());
        assertEquals("NEX-ORDER-001", result.getOrderNo());
        assertNotNull(result.getCheckoutUrl());
        assertTrue(result.getCheckoutUrl().contains("abc123"));
        assertEquals(PaymentOrder.OrderStatus.PAYING, sampleOrder.getStatus());
        assertEquals("0xPayerAddress", sampleOrder.getPayerAddress());
    }

    @Test
    @DisplayName("initiatePayment: expired order transitions to EXPIRED and returns failed")
    void initiatePayment_expiredOrder_transitionsToExpired() {
        sampleOrder.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any())).thenReturn(sampleOrder);

        PaymentResult result = paymentService.initiatePayment(1L, "0xPayerAddress");

        assertEquals("FAILED", result.getStatus());
        assertEquals(PaymentOrder.OrderStatus.EXPIRED, sampleOrder.getStatus());
    }

    @Test
    @DisplayName("initiatePayment: non-PENDING order returns failed without transition")
    void initiatePayment_nonPendingOrder_returnsFailed() {
        sampleOrder.setStatus(PaymentOrder.OrderStatus.PAID);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        PaymentResult result = paymentService.initiatePayment(1L, "0xPayerAddress");

        assertEquals("FAILED", result.getStatus());
        assertEquals(PaymentOrder.OrderStatus.PAID, sampleOrder.getStatus());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("initiatePayment: missing order throws IllegalArgumentException")
    void initiatePayment_missingOrder_throws() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> paymentService.initiatePayment(99L, "0xPayer"));
    }

    @Test
    @DisplayName("confirmPayment: chain confirmed transitions to PAID and publishes event")
    void confirmPayment_chainConfirmed_transitionsToPaidAndPublishesEvent() {
        sampleOrder.setStatus(PaymentOrder.OrderStatus.PAYING);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any())).thenReturn(sampleOrder);
        when(chainRpcClient.isTransactionConfirmed("0xTxHash")).thenReturn(true);

        PaymentResult result = paymentService.confirmPayment(1L, "0xTxHash");

        assertEquals("PAID", result.getStatus());
        assertEquals("0xTxHash", result.getChainTxHash());
        assertEquals(PaymentOrder.OrderStatus.PAID, sampleOrder.getStatus());
        assertEquals("0xTxHash", sampleOrder.getChainTxHash());

        // 支付确认后发布两个事件：PaymentConfirmedEvent（webhook）+ PaymentCompletedEvent（analytics 采集）
        verify(eventPublisher, times(2)).publishEvent(any());
        verify(eventPublisher, times(1)).publishEvent(argThat(
                e -> e instanceof PaymentConfirmedEvent
                        && "0xTxHash".equals(((PaymentConfirmedEvent) e).getChainTxHash())));
    }

    @Test
    @DisplayName("confirmPayment: chain not confirmed returns failed, order stays PAYING")
    void confirmPayment_chainNotConfirmed_returnsFailed() {
        sampleOrder.setStatus(PaymentOrder.OrderStatus.PAYING);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(chainRpcClient.isTransactionConfirmed("0xTxHash")).thenReturn(false);

        PaymentResult result = paymentService.confirmPayment(1L, "0xTxHash");

        assertEquals("FAILED", result.getStatus());
        assertEquals(PaymentOrder.OrderStatus.PAYING, sampleOrder.getStatus());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("confirmPayment: already PAID order returns success without re-confirming")
    void confirmPayment_alreadyPaid_returnsSuccessDirectly() {
        sampleOrder.setStatus(PaymentOrder.OrderStatus.PAID);
        sampleOrder.setChainTxHash("0xExistingTx");
        sampleOrder.setPaidAt(LocalDateTime.now());
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        PaymentResult result = paymentService.confirmPayment(1L, "0xNewTx");

        assertEquals("PAID", result.getStatus());
        assertEquals("0xExistingTx", result.getChainTxHash());
        verify(chainRpcClient, never()).isTransactionConfirmed(any());
    }

    @Test
    @DisplayName("isChainConfirmed: null or empty hash returns false")
    void isChainConfirmed_nullOrEmpty_returnsFalse() {
        assertFalse(paymentService.isChainConfirmed(null));
        assertFalse(paymentService.isChainConfirmed(""));
    }

    @Test
    @DisplayName("isChainConfirmed: delegates to ChainRpcClient")
    void isChainConfirmed_delegatesToRpcClient() {
        when(chainRpcClient.isTransactionConfirmed("0xValidTx")).thenReturn(true);
        assertTrue(paymentService.isChainConfirmed("0xValidTx"));
    }

    @Test
    @DisplayName("refund: non-PAID order throws IllegalStateException")
    void refund_nonPaidOrder_throws() {
        sampleOrder.setStatus(PaymentOrder.OrderStatus.PENDING);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        assertThrows(IllegalStateException.class,
                () -> paymentService.refund(1L, new BigDecimal("100"), "reason"));
    }

    @Test
    @DisplayName("refund: amount exceeding order amount throws IllegalArgumentException")
    void refund_amountExceedsOrder_throws() {
        sampleOrder.setStatus(PaymentOrder.OrderStatus.PAID);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        assertThrows(IllegalArgumentException.class,
                () -> paymentService.refund(1L, new BigDecimal("2000000"), "reason"));
    }

    // --- Risk / compliance gate tests ---

    @Test
    @DisplayName("initiatePayment: risk REJECTED transitions order to FAILED and returns failed")
    void initiatePayment_riskRejected_transitionsToFailed() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any())).thenReturn(sampleOrder);
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.REJECTED);

        PaymentResult result = paymentService.initiatePayment(1L, "0xPayerAddress");

        assertEquals("FAILED", result.getStatus());
        assertEquals(PaymentOrder.OrderStatus.FAILED, sampleOrder.getStatus());
        assertTrue(result.getMessage().contains("risk control"));
        verify(orderRepository).save(sampleOrder);
    }

    @Test
    @DisplayName("initiatePayment: risk FROZEN also transitions order to FAILED")
    void initiatePayment_riskFrozen_transitionsToFailed() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any())).thenReturn(sampleOrder);
        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.FROZEN);

        PaymentResult result = paymentService.initiatePayment(1L, "0xPayerAddress");

        assertEquals("FAILED", result.getStatus());
        assertEquals(PaymentOrder.OrderStatus.FAILED, sampleOrder.getStatus());
    }

    @Test
    @DisplayName("refund: risk REJECTED throws IllegalStateException before transfer")
    void refund_riskRejected_throws() {
        sampleOrder.setStatus(PaymentOrder.OrderStatus.PAID);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(riskService.evaluateRefund(any())).thenReturn(RiskDecision.REJECTED);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> paymentService.refund(1L, new BigDecimal("100"), "reason"));
        assertTrue(ex.getMessage().contains("risk control"));
        verify(refundRepository, never()).save(any());
    }
}
