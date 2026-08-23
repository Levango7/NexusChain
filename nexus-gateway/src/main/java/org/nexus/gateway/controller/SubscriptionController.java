package org.nexus.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.SubscriptionService;
import org.nexus.gateway.model.Subscription;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * REST API for subscription management (create, query, charge, cancel).
 *
 * <p><b>安全设计（P0-4 IDOR 加固）：</b>所有写操作及查询均通过
 * {@link MerchantOwnershipGuard} 校验资源归属，从 {@code nexus.merchantId}
 * 请求属性获取调用方商户ID，与订阅的 {@code merchantId} 比对，不匹配抛
 * {@code MerchantOwnershipException}（映射 403）。</p>
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
     * <p>请求体中的 {@code merchantId} 必须与认证商户ID一致，否则 403。</p>
     *
     * @param request subscription creation request
     * @param httpRequest HTTP 请求（用于获取认证商户ID）
     * @return created subscription entity (201)
     */
    @PostMapping
    public ResponseEntity<Subscription> create(@RequestBody CreateSubscriptionRequest request,
                                               HttpServletRequest httpRequest) {
        // P0-4：禁止伪造 merchantId 创建他人订阅
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        ownershipGuard.requireOwned(callerMerchantId, request.getMerchantId(),
                "subscription", request.getMerchantId());
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
     * <p>仅返回属于认证商户的订阅，否则 403。</p>
     *
     * @param id subscription ID
     * @param httpRequest HTTP 请求（用于获取认证商户ID）
     * @return subscription entity
     */
    @GetMapping("/{id}")
    public ResponseEntity<Subscription> get(@PathVariable Long id,
                                            HttpServletRequest httpRequest) {
        // P0-4：禁止越权查询他人订阅
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        Subscription subscription = subscriptionService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + id));
        ownershipGuard.requireOwned(callerMerchantId, subscription.getMerchantId(),
                "subscription", id);
        return ResponseEntity.ok(subscription);
    }

    /**
     * Manually trigger a recurring charge for a subscription.
     *
     * <p>仅允许订阅所属商户触发扣款，否则 403。</p>
     *
     * @param id subscription ID
     * @param httpRequest HTTP 请求（用于获取认证商户ID）
     * @return the on-chain transaction hash (200) or 409 if the charge failed
     */
    @PostMapping("/{id}/charge")
    public ResponseEntity<ChargeResponse> charge(@PathVariable Long id,
                                                 HttpServletRequest httpRequest) {
        // P0-4：禁止越权触发他人订阅扣款
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        Subscription subscription = subscriptionService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + id));
        ownershipGuard.requireOwned(callerMerchantId, subscription.getMerchantId(),
                "subscription", id);
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
     * <p>仅允许订阅所属商户取消，否则 403。</p>
     *
     * @param id subscription ID
     * @param httpRequest HTTP 请求（用于获取认证商户ID）
     * @return updated subscription entity
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Subscription> cancel(@PathVariable Long id,
                                               HttpServletRequest httpRequest) {
        // P0-4：禁止越权取消他人订阅
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        Subscription subscription = subscriptionService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + id));
        ownershipGuard.requireOwned(callerMerchantId, subscription.getMerchantId(),
                "subscription", id);
        Subscription cancelled = subscriptionService.cancel(id);
        return ResponseEntity.ok(cancelled);
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
