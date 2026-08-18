package org.nexus.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.nexus.gateway.PaymentService;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.webhook.AdyenWebhookVerifier;
import org.nexus.gateway.webhook.StripeWebhookVerifier;
import org.nexus.gateway.webhook.WebhookVerifyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Webhook controller for receiving chain event callbacks and PSP notifications.
 *
 * <p>The chain listener (via nexus-sdk) posts events here when a payment
 * transaction reaches sufficient confirmations. The controller verifies the
 * callback signature, then delegates to {@link PaymentService} for confirmation.</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/v1/webhooks/chain-events} — NexusChain 内部链事件回调（HMAC-SHA256 over canonical JSON）</li>
 *   <li>{@code POST /api/v1/webhooks/stripe} — Stripe webhook（{@code Stripe-Signature} 头验签）</li>
 *   <li>{@code POST /api/v1/webhooks/adyen} — Adyen webhook（{@code additionalData.hmacSignature} 验签）</li>
 * </ul>
 *
 * <p>PSP webhook 验签后仅记录事件并返回 200（实际订单状态由后续异步对账任务推进）。
 * 这样设计避免 webhook 处理逻辑与 PSP 内部状态机耦合，且符合 PSP 官方建议：
 * "respond 200 fast, process asynchronously"。</p>
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final PaymentService paymentService;
    private final GatewayConfig gatewayConfig;
    private final StripeWebhookVerifier stripeVerifier;
    private final AdyenWebhookVerifier adyenVerifier;

    /** Deterministic (sorted-key) JSON mapper for stable webhook signatures. */
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public WebhookController(PaymentService paymentService,
                             GatewayConfig gatewayConfig,
                             StripeWebhookVerifier stripeVerifier,
                             AdyenWebhookVerifier adyenVerifier) {
        this.paymentService = paymentService;
        this.gatewayConfig = gatewayConfig;
        this.stripeVerifier = stripeVerifier;
        this.adyenVerifier = adyenVerifier;
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
     * Stripe webhook 接收端点 — 验证 {@code Stripe-Signature} 头后记录事件。
     *
     * <p>Stripe 要求验签必须使用 raw request body（不能反序列化后再序列化），
     * 因此本方法接收 {@code byte[]} 而非 {@code Map}。验签通过后，body
     * 才会被解析为 Map 供后续处理。</p>
     *
     * @param rawBody       原始请求体字节
     * @param stripeSignature {@code Stripe-Signature} 头值
     * @return 200 验签通过；401 验签失败
     */
    @PostMapping(value = "/stripe", consumes = "application/json")
    public ResponseEntity<String> handleStripeWebhook(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "Stripe-Signature", required = false) String stripeSignature) {

        WebhookVerifyResult result = stripeVerifier.verify(rawBody, stripeSignature);
        if (!result.isValid()) {
            log.warn("Stripe webhook rejected: {}", result.getReason());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result.getReason());
        }

        // 验签通过：解析并记录事件。后续异步对账任务会查询 Stripe API 推进订单状态。
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = new ObjectMapper().readValue(rawBody, Map.class);
            String type = String.valueOf(event.getOrDefault("type", "unknown"));
            String eventId = String.valueOf(event.getOrDefault("id", "unknown"));
            log.info("Stripe webhook accepted: type={} id={}", type, eventId);
        } catch (Exception e) {
            // 验签已通过，body 解析失败不影响 200 响应（Stripe 会重试，但已确认是 Stripe 发出）
            log.warn("Stripe webhook body parse error (signature was valid): {}", e.getMessage());
        }
        return ResponseEntity.ok("OK");
    }

    /**
     * Adyen webhook 接收端点 — 验证 {@code additionalData.hmacSignature} 后记录事件。
     *
     * @param rawBody 原始请求体字节
     * @return 200 验签通过；401 验签失败
     */
    @PostMapping(value = "/adyen", consumes = "application/json")
    public ResponseEntity<String> handleAdyenWebhook(@RequestBody byte[] rawBody) {

        // Adyen 把签名放在 body.additionalData.hmacSignature，先解析 body 提取签名
        String hmacSignature = null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = new ObjectMapper().readValue(rawBody, Map.class);
            Object additionalData = event.get("additionalData");
            if (additionalData instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ad = (Map<String, Object>) additionalData;
                Object sig = ad.get("hmacSignature");
                if (sig != null) hmacSignature = String.valueOf(sig);
            }
        } catch (Exception e) {
            log.warn("Adyen webhook body parse error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Malformed body");
        }

        WebhookVerifyResult result = adyenVerifier.verify(rawBody, hmacSignature);
        if (!result.isValid()) {
            log.warn("Adyen webhook rejected: {}", result.getReason());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result.getReason());
        }

        log.info("Adyen webhook accepted");
        return ResponseEntity.ok("OK");
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
