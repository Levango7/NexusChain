package org.nexus.gateway.orchestration.connectors;

import org.nexus.gateway.orchestration.connector.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stripe Payment Connector - integrates with Stripe's PaymentIntents API.
 * Requires: stripe.api-key in application.yml (or env STRIPE_API_KEY).
 * In sandbox mode without a real key, operates in dry-run (simulates success).
 */
@Component
public class StripeConnector implements PaymentConnector {

    private static final Logger log = LoggerFactory.getLogger(StripeConnector.class);
    private static final String DEFAULT_STRIPE_API_BASE = "https://api.stripe.com/v1";

    @Value("${nexus.connectors.stripe.api-key:}")
    private String apiKey;

    @Value("${nexus.connectors.stripe.enabled:false}")
    private boolean enabled;

    /**
     * Stripe API base URL — 默认 {@code https://api.stripe.com/v1}，可通过
     * {@code nexus.connectors.stripe.api-base-url} 覆盖（测试用 WireMock 指向本地端口）。
     */
    @Value("${nexus.connectors.stripe.api-base-url:https://api.stripe.com/v1}")
    private String apiBase = DEFAULT_STRIPE_API_BASE;

    private final RestTemplate restTemplate = new RestTemplate();
    private final Map<String, PaymentStatus> localState = new ConcurrentHashMap<>();

    @Override
    public String getId() { return "stripe"; }

    @Override
    public String getType() { return "http_psp"; }

    @Override
    public String getDisplayName() { return "Stripe (Card / Wallet / BNPL)"; }

    @Override
    public boolean isActive() { return enabled; }

    @Override
    public ConnectorPaymentResult createPayment(ConnectorPaymentRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            // Dry-run mode: simulate success for development
            String id = "pi_dryrun_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            localState.put(id, PaymentStatus.SUCCEEDED);
            log.info("[Stripe DRY-RUN] PaymentIntent created: {} amount={} {}", id, request.getAmount(), request.getCurrency());
            return ConnectorPaymentResult.ok(id, PaymentStatus.SUCCEEDED);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            String body = String.format("amount=%d&currency=%s&description=%s&automatic_capture=true",
                    request.getAmount(), request.getCurrency().toLowerCase(), request.getDescription() != null ? request.getDescription() : "");

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiBase + "/payment_intents", entity, Map.class);

            if (resp.getBody() != null) {
                String piId = String.valueOf(resp.getBody().get("id"));
                String status = String.valueOf(resp.getBody().get("status"));
                PaymentStatus mapped = mapStripeStatus(status);
                localState.put(piId, mapped);
                log.info("[Stripe] PaymentIntent created: {} status={}", piId, status);
                return ConnectorPaymentResult.ok(piId, mapped);
            }
            return ConnectorPaymentResult.fail("Stripe returned empty response");
        } catch (Exception e) {
            log.error("[Stripe] createPayment failed: {}", e.getMessage());
            return ConnectorPaymentResult.fail("Stripe error: " + e.getMessage());
        }
    }

    @Override
    public PaymentStatus queryPayment(String connectorPaymentId) {
        if (apiKey == null || apiKey.isBlank()) {
            return localState.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> resp = restTemplate.exchange(
                    apiBase + "/payment_intents/" + connectorPaymentId, HttpMethod.GET, entity, Map.class);
            if (resp.getBody() != null) {
                PaymentStatus s = mapStripeStatus(String.valueOf(resp.getBody().get("status")));
                localState.put(connectorPaymentId, s);
                return s;
            }
        } catch (Exception e) {
            log.warn("[Stripe] query failed for {}: {}", connectorPaymentId, e.getMessage());
        }
        return localState.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
    }

    @Override
    public ConnectorRefundResult refund(String connectorPaymentId, long amount) {
        if (apiKey == null || apiKey.isBlank()) {
            localState.put(connectorPaymentId, PaymentStatus.REFUNDED);
            return ConnectorRefundResult.ok("re_dryrun_" + connectorPaymentId);
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            String body = "payment_intent=" + connectorPaymentId + "&amount=" + amount;
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiBase + "/refunds", entity, Map.class);
            if (resp.getBody() != null) {
                localState.put(connectorPaymentId, PaymentStatus.REFUNDED);
                return ConnectorRefundResult.ok(String.valueOf(resp.getBody().get("id")));
            }
            return ConnectorRefundResult.fail("Stripe refund returned empty");
        } catch (Exception e) {
            return ConnectorRefundResult.fail("Stripe refund error: " + e.getMessage());
        }
    }

    @Override
    public ConnectorHealth healthCheck() {
        if (!enabled) return ConnectorHealth.down(getId(), "Connector disabled");
        if (apiKey == null || apiKey.isBlank()) return ConnectorHealth.up(getId(), 0); // dry-run always healthy
        long start = System.currentTimeMillis();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            restTemplate.exchange(apiBase + "/account", HttpMethod.GET, entity, Map.class);
            return ConnectorHealth.up(getId(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ConnectorHealth.down(getId(), e.getMessage());
        }
    }

    @Override
    public Set<String> supportedCurrencies() { return Set.of(); } // Stripe supports 135+ currencies

    @Override
    public int feeBasisPoints() { return 290; } // 2.9% typical card fee

    private PaymentStatus mapStripeStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "succeeded" -> PaymentStatus.SUCCEEDED;
            case "processing", "requires_capture", "requires_confirmation" -> PaymentStatus.PROCESSING;
            case "canceled" -> PaymentStatus.CANCELLED;
            default -> PaymentStatus.FAILED;
        };
    }
}