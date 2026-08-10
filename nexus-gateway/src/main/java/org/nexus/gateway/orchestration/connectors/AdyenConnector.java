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
 * Adyen Payment Connector - integrates with Adyen's /payments API.
 * Requires: nexus.connectors.adyen.api-key + merchant-account in config.
 * In sandbox mode without a real key, operates in dry-run (simulates success).
 */
@Component
public class AdyenConnector implements PaymentConnector {

    private static final Logger log = LoggerFactory.getLogger(AdyenConnector.class);
    private static final String ADYEN_API_BASE = "https://checkout-test.adyen.com/v71";

    @Value("${nexus.connectors.adyen.api-key:}")
    private String apiKey;

    @Value("${nexus.connectors.adyen.merchant-account:}")
    private String merchantAccount;

    @Value("${nexus.connectors.adyen.enabled:false}")
    private boolean enabled;

    private final RestTemplate restTemplate = new RestTemplate();
    private final Map<String, PaymentStatus> localState = new ConcurrentHashMap<>();

    @Override
    public String getId() { return "adyen"; }

    @Override
    public String getType() { return "http_psp"; }

    @Override
    public String getDisplayName() { return "Adyen (Global Acquiring)"; }

    @Override
    public boolean isActive() { return enabled; }

    @Override
    public ConnectorPaymentResult createPayment(ConnectorPaymentRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            String id = "adyen_dryrun_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            localState.put(id, PaymentStatus.SUCCEEDED);
            log.info("[Adyen DRY-RUN] Payment created: {} amount={} {}", id, request.getAmount(), request.getCurrency());
            return ConnectorPaymentResult.ok(id, PaymentStatus.SUCCEEDED);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            String json = String.format(
                "{\"amount\":{\"value\":%d,\"currency\":\"%s\"},\"reference\":\"%s\",\"merchantAccount\":\"%s\"}",
                request.getAmount(), request.getCurrency(), request.getPaymentId(), merchantAccount);

            HttpEntity<String> entity = new HttpEntity<>(json, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(ADYEN_API_BASE + "/payments", entity, Map.class);

            if (resp.getBody() != null) {
                String pspRef = String.valueOf(resp.getBody().getOrDefault("pspReference", "unknown"));
                String resultCode = String.valueOf(resp.getBody().getOrDefault("resultCode", "Error"));
                PaymentStatus mapped = mapAdyenResult(resultCode);
                localState.put(pspRef, mapped);
                log.info("[Adyen] Payment created: pspRef={} resultCode={}", pspRef, resultCode);
                return ConnectorPaymentResult.ok(pspRef, mapped);
            }
            return ConnectorPaymentResult.fail("Adyen returned empty response");
        } catch (Exception e) {
            log.error("[Adyen] createPayment failed: {}", e.getMessage());
            return ConnectorPaymentResult.fail("Adyen error: " + e.getMessage());
        }
    }

    @Override
    public PaymentStatus queryPayment(String connectorPaymentId) {
        return localState.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
    }

    @Override
    public ConnectorRefundResult refund(String connectorPaymentId, long amount) {
        if (apiKey == null || apiKey.isBlank()) {
            localState.put(connectorPaymentId, PaymentStatus.REFUNDED);
            return ConnectorRefundResult.ok("adyen_refund_" + connectorPaymentId);
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            String json = String.format(
                "{\"amount\":{\"value\":%d,\"currency\":\"NEX\"},\"merchantAccount\":\"%s\"}", amount, merchantAccount);
            HttpEntity<String> entity = new HttpEntity<>(json, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                ADYEN_API_BASE + "/payments/" + connectorPaymentId + "/refunds", entity, Map.class);
            if (resp.getBody() != null) {
                localState.put(connectorPaymentId, PaymentStatus.REFUNDED);
                return ConnectorRefundResult.ok(String.valueOf(resp.getBody().getOrDefault("pspReference", connectorPaymentId)));
            }
            return ConnectorRefundResult.fail("Adyen refund empty response");
        } catch (Exception e) {
            return ConnectorRefundResult.fail("Adyen refund error: " + e.getMessage());
        }
    }

    @Override
    public ConnectorHealth healthCheck() {
        if (!enabled) return ConnectorHealth.down(getId(), "Connector disabled");
        if (apiKey == null || apiKey.isBlank()) return ConnectorHealth.up(getId(), 0);
        long start = System.currentTimeMillis();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", apiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            restTemplate.exchange(ADYEN_API_BASE + "/paymentMethods", HttpMethod.POST,
                new HttpEntity<>("{\"merchantAccount\":\"" + merchantAccount + "\"}", headers), Map.class);
            return ConnectorHealth.up(getId(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ConnectorHealth.down(getId(), e.getMessage());
        }
    }

    @Override
    public Set<String> supportedCurrencies() { return Set.of(); }

    @Override
    public int feeBasisPoints() { return 250; } // ~2.5% + fixed

    private PaymentStatus mapAdyenResult(String resultCode) {
        return switch (resultCode) {
            case "Authorised", "Received" -> PaymentStatus.SUCCEEDED;
            case "Pending", "RedirectShopper", "IdentifyShopper", "ChallengeShopper" -> PaymentStatus.PROCESSING;
            case "Cancelled" -> PaymentStatus.CANCELLED;
            default -> PaymentStatus.FAILED; // Refused, Error
        };
    }
}