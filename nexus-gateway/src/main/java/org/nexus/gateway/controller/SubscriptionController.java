package org.nexus.gateway.controller;

import org.nexus.gateway.SubscriptionService;
import org.nexus.gateway.model.Subscription;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * REST API for subscription management (create, query, charge, cancel).
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    /**
     * Create a new subscription agreement.
     *
     * @param request subscription creation request
     * @return created subscription entity (201)
     */
    @PostMapping
    public ResponseEntity<Subscription> create(@RequestBody CreateSubscriptionRequest request) {
        Subscription subscription = subscriptionService.createSubscription(
                request.getMerchantId(),
                request.getPayerAddress(),
                request.getPayeeAddress(),
                request.getAmount(),
                request.getCycleDays()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(subscription);
    }

    /**
     * Query a subscription by ID.
     *
     * @param id subscription ID
     * @return subscription entity
     */
    @GetMapping("/{id}")
    public ResponseEntity<Subscription> get(@PathVariable Long id) {
        return subscriptionService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Manually trigger a recurring charge for a subscription.
     *
     * @param id subscription ID
     * @return the on-chain transaction hash (200) or 409 if the charge failed
     */
    @PostMapping("/{id}/charge")
    public ResponseEntity<ChargeResponse> charge(@PathVariable Long id) {
        String txHash = subscriptionService.charge(id);
        if (txHash == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ChargeResponse(null, "Charge failed"));
        }
        return ResponseEntity.ok(new ChargeResponse(txHash, "Charge submitted"));
    }

    /**
     * Cancel an active subscription.
     *
     * @param id subscription ID
     * @return updated subscription entity
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Subscription> cancel(@PathVariable Long id) {
        Subscription subscription = subscriptionService.cancel(id);
        return ResponseEntity.ok(subscription);
    }

    // --- Request / Response DTOs ---

    public static class CreateSubscriptionRequest {
        @NotNull
        private Long merchantId;
        @NotBlank
        private String payerAddress;
        @NotBlank
        private String payeeAddress;
        @NotNull
        private BigDecimal amount;
        @NotNull
        private Integer cycleDays;

        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
        public String getPayerAddress() { return payerAddress; }
        public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }
        public String getPayeeAddress() { return payeeAddress; }
        public void setPayeeAddress(String payeeAddress) { this.payeeAddress = payeeAddress; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public Integer getCycleDays() { return cycleDays; }
        public void setCycleDays(Integer cycleDays) { this.cycleDays = cycleDays; }
    }

    public static class ChargeResponse {
        private String chainTxHash;
        private String message;

        public ChargeResponse(String chainTxHash, String message) {
            this.chainTxHash = chainTxHash;
            this.message = message;
        }

        public String getChainTxHash() { return chainTxHash; }
        public void setChainTxHash(String chainTxHash) { this.chainTxHash = chainTxHash; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
