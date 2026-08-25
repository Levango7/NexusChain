package org.nexus.gateway.orchestration.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nexus.gateway.orchestration.connector.ConnectorHealth;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.ConnectorRefundResult;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.connector.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamically registered HTTP PSP Connector (admin-provisioned at runtime via
 * {@code POST /api/v1/payments/connectors}).
 *
 * <p>Unlike {@link HttpPspConnector} (statically configured, plain-text API key,
 * RestTemplate), this adapter:</p>
 * <ul>
 *   <li>takes the <b>name of an environment variable</b> ({@code apiKeyEnv}) and
 *       resolves the bearer token lazily via {@code System.getenv} on each call;</li>
 *   <li>uses JDK {@link HttpClient} with a hard 5s timeout;</li>
 *   <li>never throws out of {@link #healthCheck()} — network failures degrade to DOWN.</li>
 * </ul>
 *
 * <p>REST contract (same generic schema as {@code HttpPspConnector}):</p>
 * <ul>
 *   <li>{@code POST {baseUrl}/payments} — create; response: {@code id}, {@code status}</li>
 *   <li>{@code GET  {baseUrl}/payments/{id}} — query; response: {@code status}</li>
 *   <li>{@code POST {baseUrl}/payments/{id}/refund} — refund; response: {@code id}</li>
 *   <li>{@code GET  {baseUrl}/health} — liveness probe</li>
 * </ul>
 */
public class DynamicHttpPspConnector implements PaymentConnector {

    private static final Logger log = LoggerFactory.getLogger(DynamicHttpPspConnector.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String id;
    private final String displayName;
    private final String baseUrl;
    private final String apiKeyEnv;
    private final int feeBps;
    private final Set<String> currencies;
    private final HttpClient httpClient;
    /** Local status cache so queryPayment can fall back when PSP is unreachable. */
    private final Map<String, PaymentStatus> payments = new ConcurrentHashMap<>();

    public DynamicHttpPspConnector(String id, String displayName, String baseUrl,
                                   String apiKeyEnv, Set<String> currencies, int feeBps) {
        this.id = id;
        this.displayName = displayName == null ? id : displayName;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
        this.apiKeyEnv = apiKeyEnv;
        this.currencies = currencies == null ? Set.of() : Set.copyOf(currencies);
        this.feeBps = feeBps;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    @Override
    public String getId() { return id; }

    @Override
    public String getType() { return "http_psp"; }

    @Override
    public String getDisplayName() { return displayName; }

    @Override
    public boolean isActive() { return true; }

    @Override
    public Set<String> supportedCurrencies() { return currencies; }

    @Override
    public int feeBasisPoints() { return feeBps; }

    @Override
    public ConnectorPaymentResult createPayment(ConnectorPaymentRequest request) {
        String apiKey = resolveApiKey();
        if (apiKey == null) {
            return ConnectorPaymentResult.fail(
                    "API key env '" + apiKeyEnv + "' is not set for connector [" + id + "]");
        }
        try {
            Map<String, Object> payload = Map.of(
                    "amount", request.getAmount(),
                    "currency", request.getCurrency() == null ? "" : request.getCurrency(),
                    "reference", request.getPaymentId() == null ? "" : request.getPaymentId(),
                    "description", request.getDescription() == null ? "" : request.getDescription());
            HttpRequest httpRequest = newBuilder(apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                    .uri(URI.create(baseUrl + "/payments"))
                    .build();
            HttpResponse<String> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return ConnectorPaymentResult.fail(
                        "PSP [" + id + "] create returned HTTP " + resp.statusCode());
            }
            JsonNode node = MAPPER.readTree(resp.body());
            String connectorPaymentId = node.path("id").asText("");
            if (connectorPaymentId.isBlank()) {
                return ConnectorPaymentResult.fail("PSP [" + id + "] returned empty payment id");
            }
            PaymentStatus status = mapStatus(node.path("status").asText("processing"));
            payments.put(connectorPaymentId, status);
            log.info("Dynamic HTTP PSP [{}] payment created: {} status={}", id, connectorPaymentId, status);
            return ConnectorPaymentResult.ok(connectorPaymentId, status);
        } catch (Exception e) {
            log.error("Dynamic HTTP PSP [{}] createPayment failed: {}", id, e.getMessage());
            return ConnectorPaymentResult.fail("PSP error: " + e.getMessage());
        }
    }

    @Override
    public PaymentStatus queryPayment(String connectorPaymentId) {
        String apiKey = resolveApiKey();
        if (apiKey == null) {
            return payments.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
        }
        try {
            HttpRequest httpRequest = newBuilder(apiKey)
                    .GET()
                    .uri(URI.create(baseUrl + "/payments/" + connectorPaymentId))
                    .build();
            HttpResponse<String> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JsonNode node = MAPPER.readTree(resp.body());
                PaymentStatus s = mapStatus(node.path("status").asText("failed"));
                payments.put(connectorPaymentId, s);
                return s;
            }
        } catch (Exception e) {
            log.warn("Dynamic HTTP PSP [{}] query failed for {}: {}", id, connectorPaymentId, e.getMessage());
        }
        return payments.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
    }

    @Override
    public ConnectorRefundResult refund(String connectorPaymentId, long amount) {
        String apiKey = resolveApiKey();
        if (apiKey == null) {
            return ConnectorRefundResult.fail(
                    "API key env '" + apiKeyEnv + "' is not set for connector [" + id + "]");
        }
        try {
            HttpRequest httpRequest = newBuilder(apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"amount\":" + amount + "}"))
                    .uri(URI.create(baseUrl + "/payments/" + connectorPaymentId + "/refund"))
                    .build();
            HttpResponse<String> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return ConnectorRefundResult.fail(
                        "PSP [" + id + "] refund returned HTTP " + resp.statusCode());
            }
            JsonNode node = MAPPER.readTree(resp.body());
            String refundId = node.path("id").asText("refund_" + connectorPaymentId);
            payments.put(connectorPaymentId, PaymentStatus.REFUNDED);
            return ConnectorRefundResult.ok(refundId);
        } catch (Exception e) {
            return ConnectorRefundResult.fail("PSP refund error: " + e.getMessage());
        }
    }

    /** Liveness probe — never throws; any failure degrades to DOWN. */
    @Override
    public ConnectorHealth healthCheck() {
        long start = System.currentTimeMillis();
        try {
            String apiKey = resolveApiKey();
            if (apiKey == null) {
                return ConnectorHealth.down(id, "API key env '" + apiKeyEnv + "' is not set");
            }
            HttpRequest httpRequest = newBuilder(apiKey)
                    .GET()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(TIMEOUT)
                    .build();
            HttpResponse<String> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return ConnectorHealth.up(id, System.currentTimeMillis() - start);
            }
            return ConnectorHealth.down(id, "HTTP " + resp.statusCode());
        } catch (Exception e) {
            return ConnectorHealth.down(id, e.getMessage());
        }
    }

    // ---------- helpers ----------

    /** Lazily resolve the bearer token from the environment; null when unset. */
    private String resolveApiKey() {
        if (apiKeyEnv == null || apiKeyEnv.isBlank()) return null;
        return System.getenv(apiKeyEnv);
    }

    private HttpRequest.Builder newBuilder(String apiKey) {
        return HttpRequest.newBuilder()
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey);
    }

    /**
     * Map a generic PSP status string to {@link PaymentStatus}; unknown values are
     * fail-closed to {@link PaymentStatus#FAILED}.
     */
    private PaymentStatus mapStatus(String status) {
        if (status == null) return PaymentStatus.FAILED;
        return switch (status.toLowerCase()) {
            case "succeeded", "success", "authorised" -> PaymentStatus.SUCCEEDED;
            case "processing", "pending", "requires_capture", "requires_confirmation" -> PaymentStatus.PROCESSING;
            case "cancelled", "canceled" -> PaymentStatus.CANCELLED;
            case "refunded" -> PaymentStatus.REFUNDED;
            default -> PaymentStatus.FAILED;
        };
    }

}