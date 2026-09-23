package org.nexus.gateway.sandbox;

import org.nexus.gateway.MerchantService;
import org.nexus.gateway.model.PaymentOrder;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for sandbox simulation endpoints.
 *
 * <p>Provides API endpoints for simulating payments, creating test merchants,
 * generating test data, resetting sandbox state, checking sandbox status,
 * and simulating webhook deliveries. All endpoints are available without
 * authentication in the sandbox profile for ease of testing.</p>
 *
 * <p>Activated by @Profile("sandbox") — only available when the sandbox
 * profile is active. NOT for production use.</p>
 */
@RestController
@RequestMapping("/api/v1/sandbox")
@Profile("sandbox")
public class SandboxController {

    private final SandboxSimulationService sandboxSimulationService;

    /**
     * Constructor-based injection of the sandbox simulation service.
     *
     * @param sandboxSimulationService the sandbox simulation service
     */
    public SandboxController(SandboxSimulationService sandboxSimulationService) {
        this.sandboxSimulationService = sandboxSimulationService;
    }

    /**
     * Simulate a payment with a controlled outcome.
     *
     * <p>Request body: {@code {"merchantId": 1, "amount": 1000, "outcome": "SUCCESS"}}</p>
     *
     * @param request simulation request containing merchantId, amount, and outcome
     * @return the simulated PaymentOrder
     */
    @PostMapping("/simulate-payment")
    public PaymentOrder simulatePayment(@RequestBody SimulatePaymentRequest request) {
        return sandboxSimulationService.simulatePayment(
                request.merchantId(), request.amount(), request.outcome());
    }

    /**
     * Create a test merchant with auto-generated API key.
     *
     * <p>Request body: {@code {"merchantName": "MyTestShop"}}</p>
     *
     * @param request creation request containing merchantName
     * @return the generated API key pair
     */
    @PostMapping("/create-test-merchant")
    public MerchantService.ApiKeyPair createTestMerchant(@RequestBody CreateTestMerchantRequest request) {
        return sandboxSimulationService.createTestMerchant(request.merchantName());
    }

    /**
     * Generate test transaction data for a merchant.
     *
     * <p>Request body: {@code {"merchantId": 1, "count": 10}}</p>
     *
     * @param request generation request containing merchantId and count
     * @return list of generated PaymentOrders
     */
    @PostMapping("/generate-test-data")
    public List<PaymentOrder> generateTestData(@RequestBody GenerateTestDataRequest request) {
        return sandboxSimulationService.generateTestData(request.merchantId(), request.count());
    }

    /**
     * Reset sandbox data for a specific merchant.
     *
     * <p>Deletes all simulated orders (orderNo starting with "SIM-") for the
     * specified merchant.</p>
     *
     * @param merchantId target merchant ID
     * @return 204 No Content on success
     */
    @DeleteMapping("/reset/{merchantId}")
    public ResponseEntity<Void> resetSandboxData(@PathVariable Long merchantId) {
        sandboxSimulationService.resetSandboxData(merchantId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get sandbox status information.
     *
     * @return a map containing sandbox status details
     */
    @GetMapping("/status")
    public Map<String, Object> getSandboxStatus() {
        return sandboxSimulationService.getSandboxStatus();
    }

    /**
     * Simulate webhook delivery for an order.
     *
     * <p>Request body: {@code {"orderNo": "SIM-123-abc", "eventType": "PAYMENT_SUCCESS"}}</p>
     *
     * @param request webhook simulation request containing orderNo and eventType
     * @return the updated PaymentOrder
     */
    @PostMapping("/simulate-webhook")
    public PaymentOrder simulateWebhookDelivery(@RequestBody SimulateWebhookRequest request) {
        return sandboxSimulationService.simulateWebhookDelivery(
                request.orderNo(), request.eventType());
    }

    // --- Request DTOs (Java records) ---

    /**
     * Request body for simulate-payment endpoint.
     *
     * @param merchantId target merchant ID
     * @param amount     payment amount in smallest token unit
     * @param outcome    desired result: "SUCCESS", "FAILURE", or "PENDING"
     */
    public record SimulatePaymentRequest(Long merchantId, long amount, String outcome) {}

    /**
     * Request body for create-test-merchant endpoint.
     *
     * @param merchantName display name for the test merchant
     */
    public record CreateTestMerchantRequest(String merchantName) {}

    /**
     * Request body for generate-test-data endpoint.
     *
     * @param merchantId target merchant ID
     * @param count      number of test orders to generate
     */
    public record GenerateTestDataRequest(Long merchantId, int count) {}

    /**
     * Request body for simulate-webhook endpoint.
     *
     * @param orderNo   target order number
     * @param eventType webhook event type: "PAYMENT_SUCCESS", "PAYMENT_FAILURE", or "REFUND_COMPLETED"
     */
    public record SimulateWebhookRequest(String orderNo, String eventType) {}
}