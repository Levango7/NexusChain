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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * REST API for order creation, payment, refund, and the cashier (checkout) redirect flow.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Payment", description = "Order creation, payment, refund, and checkout")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final OrderService orderService;
    private final PaymentService paymentService;

    public PaymentController(OrderService orderService, PaymentService paymentService) {
        this.orderService = orderService;
        this.paymentService = paymentService;
    }

    /**
     * Create a new payment order.
     *
     * @param request order creation request
     * @return created order entity (201)
     */
    @Operation(summary = "Create a new payment order")
    @PostMapping("/orders")
    public ResponseEntity<PaymentOrder> createOrder(@Valid @RequestBody CreateOrderRequest request) {
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
        Optional<PaymentOrder> opt = orderService.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        PaymentOrder order = opt.get();
        // P0-4 修复（v2.27.0）：商户归属校验，防止 IDOR
        ResponseEntity<PaymentOrder> denial = verifyMerchantOwnership(order, httpRequest);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.ok(order);
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
        // P0-4 修复（v2.27.0）：商户归属校验，防止 IDOR
        Optional<PaymentOrder> opt = orderService.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ResponseEntity<PaymentResult> denial = verifyMerchantOwnership(opt.get(), httpRequest);
        if (denial != null) {
            return denial;
        }
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
        // P0-4 修复（v2.27.0）：商户归属校验，防止 IDOR
        Optional<PaymentOrder> opt = orderService.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ResponseEntity<PaymentResult> denial = verifyMerchantOwnership(opt.get(), httpRequest);
        if (denial != null) {
            return denial;
        }
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
        // P0-4 修复（v2.27.0）：商户归属校验，防止 IDOR
        Optional<PaymentOrder> opt = orderService.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ResponseEntity<Refund> denial = verifyMerchantOwnership(opt.get(), httpRequest);
        if (denial != null) {
            return denial;
        }
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
     * P0-4 修复（v2.27.0）：商户归属校验，防止 IDOR（Insecure Direct Object Reference）。
     *
     * <p>从 HTTP 请求属性 {@code nexus.merchantId} 提取当前认证商户 ID（由鉴权拦截器设置），
     * 校验订单的 {@code merchantId} 与之一致。若请求属性未设置（无鉴权拦截器或匿名访问），
     * 记录安全告警并放行（向后兼容；部署鉴权拦截器后自动启用 IDOR 防护）。</p>
     *
     * @param order       待校验的订单
     * @param httpRequest HTTP 请求
     * @param <T>         响应体类型
     * @return 校验失败时返回 403 响应；通过时返回 {@code null}（调用方继续正常流程）
     */
    private <T> ResponseEntity<T> verifyMerchantOwnership(PaymentOrder order, HttpServletRequest httpRequest) {
        Object merchantIdAttr = httpRequest.getAttribute("nexus.merchantId");
        if (merchantIdAttr == null) {
            // 无鉴权拦截器设置商户 ID，记录告警并放行（向后兼容）
            log.warn("SECURITY: nexus.merchantId attribute not set on request; "
                    + "IDOR protection is inactive for orderNo={}, orderId={}. "
                    + "Deploy an auth filter that sets this attribute to enable protection.",
                    order.getOrderNo(), order.getId());
            return null;
        }
        Long requestMerchantId;
        try {
            requestMerchantId = Long.valueOf(merchantIdAttr.toString());
        } catch (NumberFormatException e) {
            log.error("Invalid nexus.merchantId attribute value: {}", merchantIdAttr);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!order.getMerchantId().equals(requestMerchantId)) {
            log.warn("SECURITY: IDOR attempt blocked: requested orderId={}, orderMerchantId={}, "
                    + "authenticatedMerchantId={}",
                    order.getId(), order.getMerchantId(), requestMerchantId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return null;
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
