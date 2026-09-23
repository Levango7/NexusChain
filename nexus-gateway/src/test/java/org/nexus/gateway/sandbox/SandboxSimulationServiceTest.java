package org.nexus.gateway.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.MerchantService;
import org.nexus.gateway.model.Merchant;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SandboxSimulationService unit tests — using Mockito to mock dependencies.
 *
 * <p>Covers all core sandbox simulation operations: payment simulation with
 * different outcomes, test merchant creation, test data generation, sandbox
 * data reset, sandbox status retrieval, and webhook delivery simulation.</p>
 */
class SandboxSimulationServiceTest {

    private PaymentOrderRepository paymentOrderRepository;
    private MerchantService merchantService;
    private SandboxSimulationService sandboxSimulationService;

    private static final Long MERCHANT_ID = 100L;

    @BeforeEach
    void setUp() {
        paymentOrderRepository = mock(PaymentOrderRepository.class);
        merchantService = mock(MerchantService.class);
        sandboxSimulationService = new SandboxSimulationService(paymentOrderRepository, merchantService);
    }

    // ==================== simulatePayment ====================

    @Test
    @DisplayName("simulatePayment - SUCCESS 结果创建 PAID 订单")
    void simulatePayment_success_createsPaidOrder() {
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(inv -> {
            PaymentOrder order = inv.getArgument(0);
            order.setId(1L);
            return order;
        });

        PaymentOrder order = sandboxSimulationService.simulatePayment(MERCHANT_ID, 5000L, "SUCCESS");

        assertNotNull(order);
        assertEquals(PaymentOrder.OrderStatus.PAID, order.getStatus());
        assertNotNull(order.getPaidAt());
        assertEquals(MERCHANT_ID, order.getMerchantId());
        assertEquals(BigDecimal.valueOf(5000L), order.getAmount());
        verify(paymentOrderRepository).save(any(PaymentOrder.class));
    }

    @Test
    @DisplayName("simulatePayment - FAILURE 结果创建 FAILED 订单")
    void simulatePayment_failure_createsFailedOrder() {
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(inv -> {
            PaymentOrder order = inv.getArgument(0);
            order.setId(2L);
            return order;
        });

        PaymentOrder order = sandboxSimulationService.simulatePayment(MERCHANT_ID, 3000L, "FAILURE");

        assertNotNull(order);
        assertEquals(PaymentOrder.OrderStatus.FAILED, order.getStatus());
        assertNull(order.getPaidAt());
        assertEquals(MERCHANT_ID, order.getMerchantId());
        assertEquals(BigDecimal.valueOf(3000L), order.getAmount());
    }

    @Test
    @DisplayName("simulatePayment - PENDING 结果创建 PENDING 订单")
    void simulatePayment_pending_createsPendingOrder() {
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(inv -> {
            PaymentOrder order = inv.getArgument(0);
            order.setId(3L);
            return order;
        });

        PaymentOrder order = sandboxSimulationService.simulatePayment(MERCHANT_ID, 1000L, "PENDING");

        assertNotNull(order);
        assertEquals(PaymentOrder.OrderStatus.PENDING, order.getStatus());
        assertNull(order.getPaidAt());
        assertEquals(MERCHANT_ID, order.getMerchantId());
        assertEquals(BigDecimal.valueOf(1000L), order.getAmount());
    }

    @Test
    @DisplayName("simulatePayment - orderNo 以 SIM- 开头")
    void simulatePayment_orderNoStartsWithSimPrefix() {
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(inv -> {
            PaymentOrder order = inv.getArgument(0);
            order.setId(4L);
            return order;
        });

        PaymentOrder order = sandboxSimulationService.simulatePayment(MERCHANT_ID, 2000L, "SUCCESS");

        assertNotNull(order.getOrderNo());
        assertTrue(order.getOrderNo().startsWith(SandboxSimulationService.SIM_ORDER_PREFIX),
                "Order number should start with 'SIM-'");
    }

    // ==================== createTestMerchant ====================

    @Test
    @DisplayName("createTestMerchant - 创建商户并返回 API Key")
    void createTestMerchant_createsMerchantAndReturnsApiKey() {
        Merchant merchant = new Merchant();
        merchant.setId(200L);
        merchant.setMerchantName("TEST-ShopA");

        when(merchantService.register(any(String.class), any(String.class), any(String.class)))
                .thenReturn(merchant);
        when(merchantService.generateApiKey(200L))
                .thenReturn(new MerchantService.ApiKeyPair("api-key-123", "secret-456"));
        when(merchantService.verify(200L, Merchant.VerificationStatus.VERIFIED))
                .thenReturn(merchant);

        MerchantService.ApiKeyPair result = sandboxSimulationService.createTestMerchant("ShopA");

        assertNotNull(result);
        assertEquals("api-key-123", result.getApiKey());
        assertEquals("secret-456", result.getSecret());
        verify(merchantService).register(eq("TEST-ShopA"), any(String.class), any(String.class));
        verify(merchantService).generateApiKey(200L);
        verify(merchantService).verify(200L, Merchant.VerificationStatus.VERIFIED);
    }

    @Test
    @DisplayName("createTestMerchant - 商户名称以 TEST- 开头")
    void createTestMerchant_merchantNameHasTestPrefix() {
        Merchant merchant = new Merchant();
        merchant.setId(201L);
        merchant.setMerchantName("TEST-ShopB");

        when(merchantService.register(any(String.class), any(String.class), any(String.class)))
                .thenReturn(merchant);
        when(merchantService.generateApiKey(201L))
                .thenReturn(new MerchantService.ApiKeyPair("api-key-789", "secret-012"));
        when(merchantService.verify(201L, Merchant.VerificationStatus.VERIFIED))
                .thenReturn(merchant);

        sandboxSimulationService.createTestMerchant("ShopB");

        verify(merchantService).register(eq("TEST-ShopB"), any(String.class), any(String.class));
    }

    // ==================== generateTestData ====================

    @Test
    @DisplayName("generateTestData - 生成指定数量的测试订单")
    void generateTestData_generatesCorrectCount() {
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(inv -> {
            PaymentOrder order = inv.getArgument(0);
            order.setId(System.nanoTime());
            return order;
        });

        List<PaymentOrder> orders = sandboxSimulationService.generateTestData(MERCHANT_ID, 10);

        assertNotNull(orders);
        assertEquals(10, orders.size());
        verify(paymentOrderRepository, times(10)).save(any(PaymentOrder.class));

        for (PaymentOrder order : orders) {
            assertTrue(order.getOrderNo().startsWith(SandboxSimulationService.SIM_ORDER_PREFIX));
            assertEquals(MERCHANT_ID, order.getMerchantId());
            assertNotNull(order.getAmount());
            assertTrue(order.getAmount().compareTo(BigDecimal.ZERO) > 0);
        }
    }

    @Test
    @DisplayName("generateTestData - 生成的订单状态分布合理")
    void generateTestData_statusDistributionIsReasonable() {
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(inv -> {
            PaymentOrder order = inv.getArgument(0);
            order.setId(System.nanoTime());
            return order;
        });

        // Generate a large enough sample for statistical validity
        List<PaymentOrder> orders = sandboxSimulationService.generateTestData(MERCHANT_ID, 100);

        long paidCount = orders.stream().filter(o -> o.getStatus() == PaymentOrder.OrderStatus.PAID).count();
        long pendingCount = orders.stream().filter(o -> o.getStatus() == PaymentOrder.OrderStatus.PENDING).count();
        long failedCount = orders.stream().filter(o -> o.getStatus() == PaymentOrder.OrderStatus.FAILED).count();
        long refundedCount = orders.stream().filter(o -> o.getStatus() == PaymentOrder.OrderStatus.REFUNDED).count();

        // PAID should be the majority (~70%), so at least 50%
        assertTrue(paidCount >= 50, "PAID should be majority, got: " + paidCount);
        // PENDING should be ~15%, at least 5%
        assertTrue(pendingCount >= 5, "PENDING should be non-trivial, got: " + pendingCount);
        // FAILED should be ~10%, at least 2%
        assertTrue(failedCount >= 2, "FAILED should be present, got: " + failedCount);
        // REFUNDED should be ~5%, at least 1
        assertTrue(refundedCount >= 1, "REFUNDED should be present, got: " + refundedCount);
        // Total should sum to 100
        assertEquals(100, paidCount + pendingCount + failedCount + refundedCount);
    }

    // ==================== resetSandboxData ====================

    @Test
    @DisplayName("resetSandboxData - 删除指定商户的模拟订单")
    void resetSandboxData_deletesSimulatedOrders() {
        PaymentOrder simOrder1 = new PaymentOrder();
        simOrder1.setId(1L);
        simOrder1.setOrderNo("SIM-100-aaa");

        PaymentOrder simOrder2 = new PaymentOrder();
        simOrder2.setId(2L);
        simOrder2.setOrderNo("SIM-101-bbb");

        PaymentOrder realOrder = new PaymentOrder();
        realOrder.setId(3L);
        realOrder.setOrderNo("ORD-100-ccc");

        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(simOrder1, simOrder2, realOrder));

        sandboxSimulationService.resetSandboxData(MERCHANT_ID);

        verify(paymentOrderRepository).findByMerchantId(MERCHANT_ID);
        // Only SIM- prefixed orders should be deleted
        verify(paymentOrderRepository).deleteAll(List.of(simOrder1, simOrder2));
    }

    // ==================== getSandboxStatus ====================

    @Test
    @DisplayName("getSandboxStatus - 返回正确的状态信息")
    void getSandboxStatus_returnsCorrectStatusInfo() {
        PaymentOrder simOrder = new PaymentOrder();
        simOrder.setOrderNo("SIM-100-aaa");

        PaymentOrder realOrder = new PaymentOrder();
        realOrder.setOrderNo("ORD-100-bbb");

        when(paymentOrderRepository.findAll())
                .thenReturn(List.of(simOrder, realOrder));

        Map<String, Object> status = sandboxSimulationService.getSandboxStatus();

        assertNotNull(status);
        assertEquals(true, status.get("sandboxMode"));
        assertEquals(1L, status.get("totalSimulatedOrders"));
        assertNotNull(status.get("activeConnectors"));
        assertTrue(status.get("activeConnectors") instanceof List);
        @SuppressWarnings("unchecked")
        List<String> connectors = (List<String>) status.get("activeConnectors");
        assertTrue(connectors.contains("mock"));
        assertTrue(connectors.contains("chain"));
        assertTrue(connectors.contains("consortium"));
    }

    // ==================== simulateWebhookDelivery ====================

    @Test
    @DisplayName("simulateWebhookDelivery - PAYMENT_SUCCESS 事件更新订单为 PAID")
    void simulateWebhookDelivery_paymentSuccess_updatesToPaid() {
        PaymentOrder order = new PaymentOrder();
        order.setId(10L);
        order.setOrderNo("SIM-200-xxx");
        order.setStatus(PaymentOrder.OrderStatus.PENDING);

        when(paymentOrderRepository.findByOrderNo("SIM-200-xxx"))
                .thenReturn(Optional.of(order));
        when(paymentOrderRepository.save(any(PaymentOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PaymentOrder result = sandboxSimulationService.simulateWebhookDelivery("SIM-200-xxx", "PAYMENT_SUCCESS");

        assertNotNull(result);
        assertEquals(PaymentOrder.OrderStatus.PAID, result.getStatus());
        assertNotNull(result.getPaidAt());
        verify(paymentOrderRepository).save(order);
    }

    @Test
    @DisplayName("simulateWebhookDelivery - REFUND_COMPLETED 事件更新订单为 REFUNDED")
    void simulateWebhookDelivery_refundCompleted_updatesToRefunded() {
        PaymentOrder order = new PaymentOrder();
        order.setId(11L);
        order.setOrderNo("SIM-201-yyy");
        order.setStatus(PaymentOrder.OrderStatus.PAID);
        order.setPaidAt(LocalDateTime.now());

        when(paymentOrderRepository.findByOrderNo("SIM-201-yyy"))
                .thenReturn(Optional.of(order));
        when(paymentOrderRepository.save(any(PaymentOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PaymentOrder result = sandboxSimulationService.simulateWebhookDelivery("SIM-201-yyy", "REFUND_COMPLETED");

        assertNotNull(result);
        assertEquals(PaymentOrder.OrderStatus.REFUNDED, result.getStatus());
        verify(paymentOrderRepository).save(order);
    }

    // ==================== Edge cases ====================

    @Test
    @DisplayName("simulatePayment - 无效 outcome 抛出 IllegalArgumentException")
    void simulatePayment_invalidOutcome_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> sandboxSimulationService.simulatePayment(MERCHANT_ID, 1000L, "INVALID"));
    }

    @Test
    @DisplayName("simulateWebhookDelivery - 订单不存在抛出 IllegalArgumentException")
    void simulateWebhookDelivery_orderNotFound_throwsException() {
        when(paymentOrderRepository.findByOrderNo("NONEXISTENT"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> sandboxSimulationService.simulateWebhookDelivery("NONEXISTENT", "PAYMENT_SUCCESS"));
    }

    @Test
    @DisplayName("simulateWebhookDelivery - PAYMENT_FAILURE 事件更新订单为 FAILED")
    void simulateWebhookDelivery_paymentFailure_updatesToFailed() {
        PaymentOrder order = new PaymentOrder();
        order.setId(12L);
        order.setOrderNo("SIM-202-zzz");
        order.setStatus(PaymentOrder.OrderStatus.PENDING);

        when(paymentOrderRepository.findByOrderNo("SIM-202-zzz"))
                .thenReturn(Optional.of(order));
        when(paymentOrderRepository.save(any(PaymentOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PaymentOrder result = sandboxSimulationService.simulateWebhookDelivery("SIM-202-zzz", "PAYMENT_FAILURE");

        assertNotNull(result);
        assertEquals(PaymentOrder.OrderStatus.FAILED, result.getStatus());
        verify(paymentOrderRepository).save(order);
    }

    @Test
    @DisplayName("resetSandboxData - 没有模拟订单时不删除任何数据")
    void resetSandboxData_noSimOrders_deletesNothing() {
        PaymentOrder realOrder = new PaymentOrder();
        realOrder.setId(5L);
        realOrder.setOrderNo("ORD-100-ccc");

        when(paymentOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(realOrder));

        sandboxSimulationService.resetSandboxData(MERCHANT_ID);

        verify(paymentOrderRepository).deleteAll(List.of());
    }
}