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

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * REST API for order creation, payment, refund, and the cashier (checkout) redirect flow.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Payment", description = "Order creation, payment, refund, and checkout")
public class PaymentController {

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
    public ResponseEntity<PaymentOrder> getOrder(@PathVariable Long id) {
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
    public ResponseEntity<PaymentResult> pay(@PathVariable Long id, @RequestBody PayRequest request) {
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
    public ResponseEntity<PaymentResult> confirm(@PathVariable Long id, @RequestBody ConfirmRequest request) {
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
    public ResponseEntity<Refund> refund(@PathVariable Long id, @RequestBody RefundRequest request) {
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
