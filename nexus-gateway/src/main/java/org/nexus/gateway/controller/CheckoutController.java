package org.nexus.gateway.controller;

import org.nexus.gateway.OrderService;
import org.nexus.gateway.model.PaymentOrder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Checkout API for the cashier (checkout.html) frontend page.
 * Provides order info and payment status polling endpoints.
 */
@RestController
@RequestMapping("/api/v1/checkout")
public class CheckoutController {

    private final OrderService orderService;

    public CheckoutController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Get order info by checkout token (used by cashier page on load).
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getInfo(@RequestParam String token) {
        return orderService.findByCheckoutToken(token)
                .map(order -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("id", order.getId());
                    info.put("orderNo", order.getOrderNo());
                    info.put("amount", order.getAmount());
                    info.put("tokenSymbol", order.getTokenSymbol());
                    info.put("description", order.getDescription());
                    info.put("payeeAddress", order.getPayeeAddress());
                    info.put("status", order.getStatus().name());
                    info.put("expiresAt", order.getExpiresAt() != null ? order.getExpiresAt().toString() : null);
                    return ResponseEntity.ok(info);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Poll payment status by checkout token (used by cashier page for auto-refresh).
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(@RequestParam String token) {
        return orderService.findByCheckoutToken(token)
                .map(order -> {
                    Map<String, Object> status = new HashMap<>();
                    status.put("orderNo", order.getOrderNo());
                    status.put("status", order.getStatus().name());
                    status.put("chainTxHash", order.getChainTxHash());
                    status.put("paidAt", order.getPaidAt() != null ? order.getPaidAt().toString() : null);
                    return ResponseEntity.ok(status);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
