package org.nexus.gateway.sandbox;

import org.nexus.gateway.MerchantService;
import org.nexus.gateway.model.Merchant;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sandbox simulation service for local development and testing.
 *
 * <p>Provides simulated payment flows, test merchant creation, test data generation,
 * webhook delivery simulation, and sandbox data reset capabilities. All simulated
 * orders are prefixed with "SIM-" for easy identification and cleanup.</p>
 *
 * <p>Activated by @Profile("sandbox") — use with --spring.profiles.active=sandbox.
 * NOT for production use.</p>
 */
@Service
@Profile("sandbox")
public class SandboxSimulationService {

    private static final Logger log = LoggerFactory.getLogger(SandboxSimulationService.class);

    /** Prefix for simulated order numbers, enables easy identification and cleanup. */
    public static final String SIM_ORDER_PREFIX = "SIM-";

    /** Prefix for test merchant names, enables easy identification and cleanup. */
    public static final String TEST_MERCHANT_PREFIX = "TEST-";

    /** Available connector IDs for random assignment in test data generation. */
    private static final String[] CONNECTOR_IDS = {"mock", "chain", "consortium"};

    private final PaymentOrderRepository paymentOrderRepository;
    private final MerchantService merchantService;

    /**
     * Constructor-based injection of dependencies.
     *
     * @param paymentOrderRepository repository for payment order persistence
     * @param merchantService        service for merchant registration and API key management
     */
    public SandboxSimulationService(PaymentOrderRepository paymentOrderRepository,
                                     MerchantService merchantService) {
        this.paymentOrderRepository = paymentOrderRepository;
        this.merchantService = merchantService;
    }

    /**
     * Simulate a payment with a controlled outcome.
     *
     * <p>Creates a PaymentOrder with the specified result:
     * <ul>
     *   <li>SUCCESS — order status set to PAID, paidAt timestamp set</li>
     *   <li>FAILURE — order status set to FAILED</li>
     *   <li>PENDING — order status set to PENDING</li>
     * </ul></p>
     *
     * @param merchantId target merchant ID
     * @param amount     payment amount in smallest token unit
     * @param outcome    desired result: "SUCCESS", "FAILURE", or "PENDING"
     * @return the created PaymentOrder
     */
    public PaymentOrder simulatePayment(Long merchantId, long amount, String outcome) {
        log.info("[SANDBOX] Simulating payment: merchantId={}, amount={}, outcome={}", merchantId, amount, outcome);

        PaymentOrder order = new PaymentOrder();
        order.setOrderNo(generateSimOrderNo());
        order.setMerchantId(merchantId);
        order.setAmount(BigDecimal.valueOf(amount));
        order.setPayeeAddress("0xSandboxPayee" + merchantId);
        order.setExpiresAt(LocalDateTime.now().plusHours(24));
        order.setDescription("Sandbox simulated payment");

        switch (outcome.toUpperCase()) {
            case "SUCCESS":
                order.setStatus(PaymentOrder.OrderStatus.PAID);
                order.setPaidAt(LocalDateTime.now());
                order.setChainTxHash("0xSimTx" + UUID.randomUUID().toString().replace("-", "").substring(0, 32));
                break;
            case "FAILURE":
                order.setStatus(PaymentOrder.OrderStatus.FAILED);
                break;
            case "PENDING":
                order.setStatus(PaymentOrder.OrderStatus.PENDING);
                break;
            default:
                throw new IllegalArgumentException("Invalid outcome: " + outcome + ". Must be SUCCESS, FAILURE, or PENDING");
        }

        return paymentOrderRepository.save(order);
    }

    /**
     * Create a test merchant with auto-generated API key and verified status.
     *
     * <p>The merchant name is automatically prefixed with "TEST-" for identification.
     * The merchant is registered, API key generated, and verification status set
     * to VERIFIED in sequence.</p>
     *
     * @param merchantName display name for the test merchant (will be prefixed with "TEST-")
     * @return the generated API key pair
     */
    public MerchantService.ApiKeyPair createTestMerchant(String merchantName) {
        String prefixedName = TEST_MERCHANT_PREFIX + merchantName;
        String email = prefixedName.toLowerCase().replace(" ", ".") + "@sandbox.test";
        String settlementAddress = "0xSandboxSettlement" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        log.info("[SANDBOX] Creating test merchant: {}", prefixedName);

        Merchant merchant = merchantService.register(prefixedName, email, settlementAddress);
        MerchantService.ApiKeyPair apiKeyPair = merchantService.generateApiKey(merchant.getId());
        merchantService.verify(merchant.getId(), Merchant.VerificationStatus.VERIFIED);

        log.info("[SANDBOX] Test merchant created: id={}, name={}", merchant.getId(), prefixedName);
        return apiKeyPair;
    }

    /**
     * Generate test transaction data for a merchant.
     *
     * <p>Creates the specified number of test orders with random status distribution:
     * <ul>
     *   <li>~70% PAID</li>
     *   <li>~15% PENDING</li>
     *   <li>~10% FAILED</li>
     *   <li>~5% REFUNDED</li>
     * </ul>
     * Random amounts range from 1 to 10000. Random connector IDs are chosen
     * from "mock", "chain", "consortium".</p>
     *
     * @param merchantId target merchant ID
     * @param count      number of test orders to generate
     * @return list of created PaymentOrders
     */
    public List<PaymentOrder> generateTestData(Long merchantId, int count) {
        log.info("[SANDBOX] Generating {} test orders for merchantId={}", count, merchantId);

        List<PaymentOrder> orders = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            PaymentOrder order = new PaymentOrder();
            order.setOrderNo(generateSimOrderNo());
            order.setMerchantId(merchantId);
            order.setAmount(BigDecimal.valueOf(ThreadLocalRandom.current().nextLong(1, 10001)));
            order.setPayeeAddress("0xSandboxPayee" + merchantId);
            order.setExpiresAt(LocalDateTime.now().plusHours(24));

            String connectorId = CONNECTOR_IDS[ThreadLocalRandom.current().nextInt(CONNECTOR_IDS.length)];
            order.setDescription("Sandbox test data via " + connectorId + " connector");
            order.setChainTxHash("0xSim" + connectorId + UUID.randomUUID().toString().replace("-", "").substring(0, 20));

            order.setStatus(randomOrderStatus());

            if (order.getStatus() == PaymentOrder.OrderStatus.PAID) {
                order.setPaidAt(LocalDateTime.now().minusMinutes(ThreadLocalRandom.current().nextLong(0, 1440)));
            }

            orders.add(paymentOrderRepository.save(order));
        }

        log.info("[SANDBOX] Generated {} test orders for merchantId={}", orders.size(), merchantId);
        return orders;
    }

    /**
     * Reset sandbox data for a specific merchant.
     *
     * <p>Deletes all simulated orders (orderNo starting with "SIM-") for the
     * specified merchant. Used for sandbox data cleanup between test sessions.</p>
     *
     * @param merchantId target merchant ID
     */
    public void resetSandboxData(Long merchantId) {
        log.info("[SANDBOX] Resetting sandbox data for merchantId={}", merchantId);

        List<PaymentOrder> merchantOrders = paymentOrderRepository.findByMerchantId(merchantId);
        List<PaymentOrder> simOrders = merchantOrders.stream()
                .filter(o -> o.getOrderNo() != null && o.getOrderNo().startsWith(SIM_ORDER_PREFIX))
                .toList();

        paymentOrderRepository.deleteAll(simOrders);

        log.info("[SANDBOX] Deleted {} simulated orders for merchantId={}", simOrders.size(), merchantId);
    }

    /**
     * Get sandbox status information.
     *
     * @return a map containing:
     * <ul>
     *   <li>totalSimulatedOrders — count of all SIM- prefixed orders</li>
     *   <li>totalTestMerchants — count of merchants with TEST- prefixed names</li>
     *   <li>activeConnectors — list of active connector IDs</li>
     *   <li>sandboxMode — always true</li>
     * </ul>
     */
    public Map<String, Object> getSandboxStatus() {
        List<PaymentOrder> allOrders = paymentOrderRepository.findAll();
        long simOrderCount = allOrders.stream()
                .filter(o -> o.getOrderNo() != null && o.getOrderNo().startsWith(SIM_ORDER_PREFIX))
                .count();

        return Map.of(
                "totalSimulatedOrders", simOrderCount,
                "totalTestMerchants", 0L,
                "activeConnectors", List.of(CONNECTOR_IDS),
                "sandboxMode", true
        );
    }

    /**
     * Simulate webhook delivery for an order.
     *
     * <p>Updates the order status based on the event type:
     * <ul>
     *   <li>PAYMENT_SUCCESS — sets status to PAID, sets paidAt</li>
     *   <li>PAYMENT_FAILURE — sets status to FAILED</li>
     *   <li>REFUND_COMPLETED — sets status to REFUNDED</li>
     * </ul></p>
     *
     * @param orderNo   target order number
     * @param eventType webhook event type: "PAYMENT_SUCCESS", "PAYMENT_FAILURE", or "REFUND_COMPLETED"
     * @return the updated PaymentOrder
     * @throws IllegalArgumentException if the order is not found or event type is invalid
     */
    public PaymentOrder simulateWebhookDelivery(String orderNo, String eventType) {
        log.info("[SANDBOX] Simulating webhook delivery: orderNo={}, eventType={}", orderNo, eventType);

        PaymentOrder order = paymentOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderNo));

        switch (eventType.toUpperCase()) {
            case "PAYMENT_SUCCESS":
                order.setStatus(PaymentOrder.OrderStatus.PAID);
                order.setPaidAt(LocalDateTime.now());
                break;
            case "PAYMENT_FAILURE":
                order.setStatus(PaymentOrder.OrderStatus.FAILED);
                break;
            case "REFUND_COMPLETED":
                order.setStatus(PaymentOrder.OrderStatus.REFUNDED);
                break;
            default:
                throw new IllegalArgumentException("Invalid event type: " + eventType
                        + ". Must be PAYMENT_SUCCESS, PAYMENT_FAILURE, or REFUND_COMPLETED");
        }

        return paymentOrderRepository.save(order);
    }

    // --- Private helpers ---

    /**
     * Generate a simulated order number with SIM- prefix.
     * Format: SIM-{timestamp}-{random}
     */
    private String generateSimOrderNo() {
        return SIM_ORDER_PREFIX + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * Randomly select an order status based on the distribution:
     * ~70% PAID, ~15% PENDING, ~10% FAILED, ~5% REFUNDED.
     */
    private PaymentOrder.OrderStatus randomOrderStatus() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 70) {
            return PaymentOrder.OrderStatus.PAID;
        } else if (roll < 85) {
            return PaymentOrder.OrderStatus.PENDING;
        } else if (roll < 95) {
            return PaymentOrder.OrderStatus.FAILED;
        } else {
            return PaymentOrder.OrderStatus.REFUNDED;
        }
    }
}