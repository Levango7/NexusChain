package org.nexus.gateway.controller;

import org.nexus.gateway.OrderService;
import org.nexus.gateway.PaymentService;
import org.nexus.gateway.dto.CreateOrderRequest;
import org.nexus.gateway.dto.PaymentResult;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.model.Refund;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.nexus.gateway.security.MerchantOwnershipException;
import org.nexus.gateway.security.MerchantOwnershipGuard;

/**
 * REST API for order creation, payment, refund, and the cashier (checkout) redirect flow.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Payment", description = "Order creation, payment, refund, and checkout")
public class PaymentController {

    private final OrderService orderService;
    private final PaymentService paymentService;
    private final MerchantOwnershipGuard ownershipGuard;

    public PaymentController(OrderService orderService, PaymentService paymentService,
                             MerchantOwnershipGuard ownershipGuard) {
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * Create a new payment order.
     *
     * <p>P0-4：订单归属以认证上下文为准，请求体中的 merchantId 被忽略并覆盖，
     * 防止商户伪造他人身份创建订单。</p>
     *
     * @param request order creation request
     * @return created order entity (201)
     */
    @Operation(summary = "Create a new payment order")
    @PostMapping("/orders")
    public ResponseEntity<PaymentOrder> createOrder(@Valid @RequestBody CreateOrderRequest request,
                                                    HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        request.setMerchantId(String.valueOf(callerMerchantId));
        PaymentOrder order = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    /**
     * Query an order by ID.
     *
     * @param id order ID
     * @return order entity
     */
    @Operation(summary = "Query an order by ID")
    @GetMapping("/orders/{id}")
    public ResponseEntity<PaymentOrder> getOrder(@PathVariable Long id, HttpServletRequest httpRequest) {
        requireOrderOwnership(id, httpRequest);
        return orderService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Initiate a payment for an order.
     *
     * @param id      order ID
     * @param request contains the payer wallet address
     * @return payment result with checkout URL
     */
    @Operation(summary = "Initiate payment for an order")
    @PostMapping("/orders/{id}/pay")
    public ResponseEntity<PaymentResult> pay(@PathVariable Long id, @RequestBody PayRequest request,
                                             HttpServletRequest httpRequest) {
        requireOrderOwnership(id, httpRequest);
        PaymentResult result = paymentService.initiatePayment(id, request.getPayerAddress());
        return ResponseEntity.ok(result);
    }

    /**
     * Confirm a payment after receiving a chain event or manual callback.
     *
     * @param id      order ID
     * @param request contains the on-chain transaction hash
     * @return payment result
     */
    @Operation(summary = "Confirm payment after chain event")
    @PostMapping("/orders/{id}/confirm")
    public ResponseEntity<PaymentResult> confirm(@PathVariable Long id, @RequestBody ConfirmRequest request,
                                                 HttpServletRequest httpRequest) {
        requireOrderOwnership(id, httpRequest);
        PaymentResult result = paymentService.confirmPayment(id, request.getChainTxHash());
        return ResponseEntity.ok(result);
    }

    /**
     * Initiate a refund for a paid order.
     *
     * @param id      order ID
     * @param request contains refund amount and optional reason
     * @return created refund entity (201)
     */
    @Operation(summary = "Initiate a refund")
    @PostMapping("/orders/{id}/refund")
    public ResponseEntity<Refund> refund(@PathVariable Long id, @RequestBody RefundRequest request,
                                         HttpServletRequest httpRequest) {
        requireOrderOwnership(id, httpRequest);
        Refund refund = paymentService.refund(id, request.getAmount(), request.getReason());
        return ResponseEntity.status(HttpStatus.CREATED).body(refund);
    }

    /**
     * Cashier (checkout) redirect endpoint.
     *
     * <p>Given a checkout token, this redirects the payer's browser to the
     * NexusChain cashier page for completing the payment.</p>
     *
     * @param token    checkout token
     * @param response servlet response for redirect
     */
    @GetMapping("/checkout/{token}")
    public void checkout(@PathVariable String token, HttpServletResponse response) throws IOException {
        java.util.Optional<PaymentOrder> opt = orderService.findByCheckoutToken(token);
        if (opt.isPresent()) {
            // Redirect to the hosted cashier page with order context
            response.sendRedirect("/checkout.html?token=" + token);
        } else {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Invalid or expired checkout token");
        }
    }

    /**
     * P0-4 修复：商户归属校验（fail-closed）。
     *
     * <p>从请求属性 {@code nexus.merchantId}（由 ApiKeyInterceptor 鉴权后设置）
     * 取认证商户，校验订单归属。属性缺失、无法解析或不一致均拒绝访问——
     * 不存在"无鉴权上下文则放行"的降级路径。校验失败抛出
     * {@link MerchantOwnershipException}，由 GlobalExceptionHandler 映射为 403。</p>
     *
     * @param orderId      目标订单 ID
     * @param httpRequest HTTP 请求
     */
    private void requireOrderOwnership(Long orderId, HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        PaymentOrder order = orderService.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        ownershipGuard.requireOwned(callerMerchantId, order.getMerchantId(), "order", orderId);
    }

    // --- Request DTOs ---

    public static class PayRequest {
        private String payerAddress;

        public String getPayerAddress() { return payerAddress; }
        public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }
    }

    public static class ConfirmRequest {
        private String chainTxHash;

        public String getChainTxHash() { return chainTxHash; }
        public void setChainTxHash(String chainTxHash) { this.chainTxHash = chainTxHash; }
    }

    public static class RefundRequest {
        private BigDecimal amount;
        private String reason;

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
