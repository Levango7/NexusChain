package org.nexus.gateway.controller;

import org.nexus.gateway.SubscriptionService;
import org.nexus.gateway.model.Subscription;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * REST API for subscription management (create, query, charge, cancel).
 *
 * <p>IDOR 加固（质量审查 Top2，2026-09-10）：与 PaymentController 的 P0-4
 * 模式对齐——create 强制从认证上下文取 merchantId（覆盖请求体伪造值），
 * get/charge/cancel 校验订阅归属（callerMerchantId == subscription.merchantId），
 * 杜绝已认证商户操作任意商户的订阅。</p>
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final MerchantOwnershipGuard ownershipGuard;

    public SubscriptionController(SubscriptionService subscriptionService,
                                  MerchantOwnershipGuard ownershipGuard) {
        this.subscriptionService = subscriptionService;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * Create a new subscription agreement.
     *
     * <p>merchantId 强制取认证上下文（ApiKeyInterceptor 注入的 request
     * attribute），请求体中的 merchantId 字段被覆盖——防止跨商户创建。</p>
     *
     * @param request subscription creation request
     * @return created subscription entity (201)
     */
    @PostMapping
    public ResponseEntity<Subscription> create(@RequestBody CreateSubscriptionRequest request,
                                               HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        Subscription subscription = subscriptionService.createSubscription(
                callerMerchantId,
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
    public ResponseEntity<Subscription> get(@PathVariable Long id, HttpServletRequest httpRequest) {
        requireSubscriptionOwnership(id, httpRequest);
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
    public ResponseEntity<ChargeResponse> charge(@PathVariable Long id, HttpServletRequest httpRequest) {
        requireSubscriptionOwnership(id, httpRequest);
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
    public ResponseEntity<Subscription> cancel(@PathVariable Long id, HttpServletRequest httpRequest) {
        requireSubscriptionOwnership(id, httpRequest);
        Subscription subscription = subscriptionService.cancel(id);
        return ResponseEntity.ok(subscription);
    }

    /**
     * 加载订阅并校验归属（P0-4 同模式）：找不到视为 404 语义（交由全局
     * 异常处理）；非本人订阅抛 MerchantOwnershipException（不泄露归属者）。
     */
    private void requireSubscriptionOwnership(Long subscriptionId, HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        Subscription subscription = subscriptionService.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + subscriptionId));
        ownershipGuard.requireOwned(callerMerchantId, subscription.getMerchantId(),
                "subscription", subscriptionId);
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
