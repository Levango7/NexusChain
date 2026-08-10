package org.nexus.gateway.event;

import org.nexus.gateway.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Listens for payment lifecycle events and sends webhook notifications to merchants.
 * Runs asynchronously to avoid blocking the payment confirmation flow.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final GatewayConfig gatewayConfig;

    /** Deterministic (sorted-key) JSON mapper; must match WebhookController's canonical form. */
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public PaymentEventListener(GatewayConfig gatewayConfig) {
        this.gatewayConfig = gatewayConfig;
    }

    @Async
    @EventListener
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        log.info("Event received: {} order={}", event.getEventType(), event.getOrderNo());
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventType", event.getEventType());
        payload.put("orderId", event.getOrderId());
        payload.put("orderNo", event.getOrderNo());
        payload.put("chainTxHash", event.getChainTxHash());
        payload.put("payerAddress", event.getPayerAddress());
        payload.put("amount", event.getAmount());
        payload.put("timestamp", System.currentTimeMillis());

        sendWebhook(event.getMerchantId(), payload);
    }

    @Async
    @EventListener
    public void onRefundCompleted(RefundCompletedEvent event) {
        log.info("Event received: {} order={} refund={}", event.getEventType(), event.getOrderNo(), event.getRefundNo());
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventType", event.getEventType());
        payload.put("orderId", event.getOrderId());
        payload.put("orderNo", event.getOrderNo());
        payload.put("refundNo", event.getRefundNo());
        payload.put("amount", event.getAmount());
        payload.put("chainTxHash", event.getChainTxHash());
        payload.put("timestamp", System.currentTimeMillis());

        sendWebhook(event.getMerchantId(), payload);
    }

    /**
     * Send webhook callback to the merchant's notify URL with HMAC signature.
     */
    private void sendWebhook(Long merchantId, Map<String, Object> payload) {
        String callbackUrl = gatewayConfig.getWebhook().getCallbackUrl();
        String secret = gatewayConfig.getWebhook().getCallbackSecret();

        if (callbackUrl == null || callbackUrl.isEmpty()) {
            log.debug("No webhook callback URL configured, skipping notification");
            return;
        }

        try {
            // Sign the deterministic, sorted-key canonical form so verification
            // (WebhookController.canonicalize) produces an identical string.
            String canonical = canonicalize(payload);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Sign the payload with HMAC-SHA256
            if (secret != null && !secret.isEmpty()) {
                String signature = computeSignature(canonical, secret);
                headers.set("X-NexusChain-Signature", signature);
            }
            headers.set("X-NexusChain-Event", (String) payload.get("eventType"));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            ResponseEntity<String> resp = restTemplate.postForEntity(callbackUrl, request, String.class);

            log.info("Webhook sent: merchant={}, event={}, status={}",
                    merchantId, payload.get("eventType"), resp.getStatusCodeValue());
        } catch (Exception e) {
            log.error("Webhook delivery failed: merchant={}, error={}", merchantId, e.getMessage());
            retryWebhook(callbackUrl, payload, secret, 1);
        }
    }


    /**
     * Retry webhook delivery with exponential backoff (max 3 attempts).
     */
    private void retryWebhook(String url, Map<String, Object> payload, String secret, int attempt) {
        if (attempt > 3) {
            log.error("Webhook delivery permanently failed after 3 retries, url={}", url);
            return;
        }
        try {
            Thread.sleep((long) Math.pow(2, attempt) * 1000);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (secret != null && !secret.isEmpty()) {
                headers.set("X-NexusChain-Signature", computeSignature(canonicalize(payload), secret));
            }
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            restTemplate.postForEntity(url, request, String.class);
            log.info("Webhook retry succeeded on attempt {}", attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Webhook retry attempt {} failed: {}", attempt, e.getMessage());
            retryWebhook(url, payload, secret, attempt + 1);
        }
    }
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
     * Serialize the payload to a deterministic, sorted-key JSON string. Must match
     * the canonical form used by WebhookController so signing and verification are symmetric.
     */
    private String canonicalize(Map<String, Object> payload) {
        try {
            return CANONICAL_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to canonicalize webhook payload", e);
        }
    }
}