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
import java.util.Random;
import java.util.function.IntSupplier;

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

    /**
     * 入库桩：save() 回填 id 后返回同一对象（与既有用例保持一致）。
     */
    private void stubSaveReturnsInput() {
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(inv -> {
            PaymentOrder order = inv.getArgument(0);
            order.setId(System.nanoTime());
            return order;
        });
    }

    /**
     * 边界探测 roll 源：每 8 次依次给出 0/69/70/84/85/94/95/99，
     * 恰好覆盖 70/15/10/5 四个分段的两端（段内最后一位与下一段第一位）。
     */
    private static IntSupplier boundaryRolls() {
        int[] rolls = {0, 69, 70, 84, 85, 94, 95, 99};
        return new IntSupplier() {
            private int index = 0;

            @Override
            public int getAsInt() {
                return rolls[index++ % rolls.length];
            }
        };
    }

    @Test
    @DisplayName("generateTestData - 状态分段边界映射确定（roll=0/69/70/84/85/94/95/99）")
    void generateTestData_statusBucketsMatchDocumentedBoundaries() {
        stubSaveReturnsInput();
        SandboxSimulationService deterministicService = new SandboxSimulationService(
                paymentOrderRepository, merchantService, boundaryRolls());

        // 80 = 10 轮边界序列 → 每段各出现 20 次（2 个 roll × 10 轮）
        List<PaymentOrder> orders = deterministicService.generateTestData(MERCHANT_ID, 80);

        assertEquals(80, orders.size());
        assertEquals(20, orders.stream()
                .filter(o -> o.getStatus() == PaymentOrder.OrderStatus.PAID).count());
        assertEquals(20, orders.stream()
                .filter(o -> o.getStatus() == PaymentOrder.OrderStatus.PENDING).count());
        assertEquals(20, orders.stream()
                .filter(o -> o.getStatus() == PaymentOrder.OrderStatus.FAILED).count());
        assertEquals(20, orders.stream()
                .filter(o -> o.getStatus() == PaymentOrder.OrderStatus.REFUNDED).count());
    }

    @Test
    @DisplayName("generateTestData - 大样本分布落在 70/15/10/5 容忍区间（固定种子，确定性）")
    void generateTestData_statusDistributionIsReasonable() {
        stubSaveReturnsInput();
        // 固定种子：断言结果确定，消除原实现 ~0.59%（0.95^100）的随机翻车概率
        Random seededRolls = new Random(20261001L);
        SandboxSimulationService deterministicService = new SandboxSimulationService(
                paymentOrderRepository, merchantService, () -> seededRolls.nextInt(100));

        int sampleSize = 10_000;
        List<PaymentOrder> orders = deterministicService.generateTestData(MERCHANT_ID, sampleSize);

        long paidCount = orders.stream().filter(o -> o.getStatus() == PaymentOrder.OrderStatus.PAID).count();
        long pendingCount = orders.stream().filter(o -> o.getStatus() == PaymentOrder.OrderStatus.PENDING).count();
        long failedCount = orders.stream().filter(o -> o.getStatus() == PaymentOrder.OrderStatus.FAILED).count();
        long refundedCount = orders.stream().filter(o -> o.getStatus() == PaymentOrder.OrderStatus.REFUNDED).count();

        // 四类状态之和必须覆盖整个样本
        assertEquals(sampleSize, paidCount + pendingCount + failedCount + refundedCount);
        // 容忍区间：PAID ~70%、PENDING ~15%、FAILED ~10%、REFUNDED ~5%
        assertTrue(paidCount >= 6500 && paidCount <= 7500, "PAID should be ~70%, got: " + paidCount);
        assertTrue(pendingCount >= 1000 && pendingCount <= 2000, "PENDING should be ~15%, got: " + pendingCount);
        assertTrue(failedCount >= 600 && failedCount <= 1400, "FAILED should be ~10%, got: " + failedCount);
        assertTrue(refundedCount >= 300 && refundedCount <= 800, "REFUNDED should be ~5%, got: " + refundedCount);
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