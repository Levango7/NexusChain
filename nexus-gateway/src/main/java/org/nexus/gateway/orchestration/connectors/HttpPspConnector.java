package org.nexus.gateway.orchestration.connectors;

import org.nexus.gateway.orchestration.connector.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic HTTP PSP Connector - adapts any REST-based payment service provider.
 * Configure with base URL + API key, and it maps to the standard connector interface.
 * This is a template; real PSPs (Stripe, Adyen, etc.) would extend this with specific mappings.
 */
public class HttpPspConnector implements PaymentConnector {

    private static final Logger log = LoggerFactory.getLogger(HttpPspConnector.class);

    private final String id;
    private final String displayName;
    private final String baseUrl;
    private final String apiKey;
    private final int feeBps;
    private final Set<String> currencies;
    private final RestTemplate restTemplate = new RestTemplate();
    private final Map<String, PaymentStatus> payments = new ConcurrentHashMap<>();
    private volatile boolean active = true;

    public HttpPspConnector(String id, String displayName, String baseUrl, String apiKey, int feeBps, Set<String> currencies) {
        this.id = id;
        this.displayName = displayName;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.feeBps = feeBps;
        this.currencies = currencies;
    }

    @Override
    public String getId() { return id; }

    @Override
    public String getType() { return "http_psp"; }

    @Override
    public String getDisplayName() { return displayName; }

    @Override
    public boolean isActive() { return active; }

    public void setActive(boolean active) { this.active = active; }

    @Override
    public ConnectorPaymentResult createPayment(ConnectorPaymentRequest request) {
        try {
            // Generic: POST to {baseUrl}/payments with standard payload
            // Real implementations would map to PSP-specific formats
            String connectorPaymentId = id + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            payments.put(connectorPaymentId, PaymentStatus.PROCESSING);
            log.info("HTTP PSP [{}] payment created: {} amount={} {}", id, connectorPaymentId, request.getAmount(), request.getCurrency());
            // In production: restTemplate.postForObject(baseUrl + "/payments", body, Map.class)
            return ConnectorPaymentResult.ok(connectorPaymentId, PaymentStatus.PROCESSING);
        } catch (Exception e) {
            log.error("HTTP PSP [{}] payment failed: {}", id, e.getMessage());
            return ConnectorPaymentResult.fail("PSP error: " + e.getMessage());
        }
    }

    @Override
    public PaymentStatus queryPayment(String connectorPaymentId) {
        // In production: GET {baseUrl}/payments/{id}
        return payments.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
    }

    @Override
    public ConnectorRefundResult refund(String connectorPaymentId, long amount) {
        if (payments.containsKey(connectorPaymentId)) {
            payments.put(connectorPaymentId, PaymentStatus.REFUNDED);
            return ConnectorRefundResult.ok("refund_" + connectorPaymentId);
        }
        return ConnectorRefundResult.fail("Payment not found at PSP");
    }

    @Override
    public ConnectorHealth healthCheck() {
        long start = System.currentTimeMillis();
        try {
            // In production: GET {baseUrl}/health
            long latency = System.currentTimeMillis() - start;
            return ConnectorHealth.up(id, latency);
        } catch (Exception e) {
            return ConnectorHealth.down(id, e.getMessage());
        }
    }

    @Override
    public Set<String> supportedCurrencies() { return currencies; }

    @Override
    public int feeBasisPoints() { return feeBps; }
}