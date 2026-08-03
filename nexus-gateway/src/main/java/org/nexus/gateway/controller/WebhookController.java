package org.nexus.gateway.controller;

import org.nexus.gateway.PaymentService;
import org.nexus.gateway.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Webhook controller for receiving chain event callbacks.
 *
 * <p>The chain listener (via nexus-sdk) posts events here when a payment
 * transaction reaches sufficient confirmations. The controller verifies the
 * callback signature, then delegates to {@link PaymentService} for confirmation.</p>
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final PaymentService paymentService;
    private final GatewayConfig gatewayConfig;

    /** Deterministic (sorted-key) JSON mapper for stable webhook signatures. */
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public WebhookController(PaymentService paymentService, GatewayConfig gatewayConfig) {
        this.paymentService = paymentService;
        this.gatewayConfig = gatewayConfig;
    }

    /**
     * Handle a chain event callback.
     *
     * <p>Expected payload fields:
     * <ul>
     *   <li>{@code orderId} - the payment order ID</li>
     *   <li>{@code chainTxHash} - the on-chain transaction hash</li>
     *   <li>{@code eventType} - e.g. {@code PAYMENT_CONFIRMED}</li>
     * </ul></p>
     *
     * @param payload     callback body
     * @param signature   HMAC-SHA256 signature header (X-NexusChain-Signature)
     * @return 200 on success, 401 on invalid signature
     */
    @PostMapping("/chain-events")
    public ResponseEntity<String> handleChainEvent(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-NexusChain-Signature", required = false) String signature) {

        // Verify callback signature. FAIL-CLOSED: this endpoint drives payment
        // confirmation (a financial state change), yet is excluded from API-key
        // auth in WebConfig, so its signature check is the ONLY gate. An
        // unconfigured secret or a missing/invalid signature must never let an
        // unauthenticated event through — otherwise anyone can forge a
        // PAYMENT_CONFIRMED event and mark orders paid without on-chain funds.
        String secret = gatewayConfig.getWebhook().getCallbackSecret();
        if (secret == null || secret.isEmpty()) {
            log.error("Webhook callback secret (nexus.webhook.callbackSecret) is not configured; "
                    + "rejecting chain-event callback to avoid unauthenticated payment confirmation");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Callback secret not configured");
        }
        if (signature == null || signature.isEmpty()) {
            log.warn("Missing webhook signature header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing signature");
        }
        String expectedSig = computeSignature(canonicalize(payload), secret);
        if (!constantTimeEquals(expectedSig, signature)) {
            log.warn("Invalid webhook signature, expected={}, actual={}", expectedSig, signature);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }

        // Process the event
        String eventType = (String) payload.get("eventType");
        log.info("Received chain event callback: type={}, payload={}", eventType, payload);

        if ("PAYMENT_CONFIRMED".equalsIgnoreCase(eventType)) {
            Long orderId = toLong(payload.get("orderId"));
            String chainTxHash = (String) payload.get("chainTxHash");
            if (orderId != null && chainTxHash != null) {
                paymentService.confirmPayment(orderId, chainTxHash);
                return ResponseEntity.ok("Confirmed");
            }
            log.warn("PAYMENT_CONFIRMED event missing orderId or chainTxHash");
            return ResponseEntity.badRequest().body("Missing orderId or chainTxHash");
        }

        log.info("Unhandled chain event type: {}", eventType);
        return ResponseEntity.ok("Ignored");
    }

    /**
     * Compute HMAC-SHA256 signature for the payload.
     */
    private String computeSignature(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute webhook signature", e);
        }
    }

    /**
     * Serialize the payload to a deterministic, sorted-key JSON string so the HMAC
     * signature is stable and symmetric with any signer that uses the same canonical form.
     */
    private String canonicalize(Map<String, Object> payload) {
        try {
            return CANONICAL_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to canonicalize webhook payload", e);
        }
    }

    /**
     * Constant-time string comparison to avoid timing side-channels on the signature.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        int diff = ab.length ^ bb.length;
        int len = Math.min(ab.length, bb.length);
        for (int i = 0; i < len; i++) {
            diff |= (ab[i] ^ bb[i]);
        }
        for (int i = len; i < bb.length; i++) {
            diff |= bb[i];
        }
        for (int i = len; i < ab.length; i++) {
            diff |= ab[i];
        }
        return diff == 0;
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
