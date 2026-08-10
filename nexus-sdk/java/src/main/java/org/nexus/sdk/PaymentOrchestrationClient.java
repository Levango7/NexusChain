package org.nexus.sdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * NexusChain Payment Orchestration Client.
 * Provides a simple Java interface to the Gateway's orchestration API.
 *
 * Usage:
 *   var client = new PaymentOrchestrationClient("http://localhost:8080", "your-api-key");
 *   var result = client.createPayment(10000, "NEX", "Order #123");
 *   System.out.println(result.get("id"));
 */
public class PaymentOrchestrationClient {

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    public PaymentOrchestrationClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Create a payment through the orchestration engine.
     * @param amount amount in smallest unit
     * @param currency e.g. "NEX", "USD"
     * @param description payment description
     * @return parsed JSON response as Map
     */
    public Map<String, Object> createPayment(long amount, String currency, String description) {
        return createPayment(amount, currency, description, null, null);
    }

    /**
     * Create a payment with full options.
     */
    public Map<String, Object> createPayment(long amount, String currency, String description,
                                              String preferredConnector, String notifyUrl) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"amount\":").append(amount).append(",");
        json.append("\"currency\":\"").append(currency).append("\",");
        json.append("\"description\":\"").append(escapeJson(description)).append("\"");
        if (preferredConnector != null) {
            json.append(",\"routing\":{\"preferred_connector\":\"").append(preferredConnector).append("\"}");
        }
        if (notifyUrl != null) {
            json.append(",\"notify_url\":\"").append(escapeJson(notifyUrl)).append("\"");
        }
        json.append("}");

        HttpResponse<String> resp = post("/api/v1/payments", json.toString());
        return parseJson(resp.body());
    }

    /**
     * Query payment status.
     */
    public Map<String, Object> getPayment(String paymentId) {
        HttpResponse<String> resp = get("/api/v1/payments/" + paymentId);
        return parseJson(resp.body());
    }

    /**
     * Refresh payment status (poll connector for latest state).
     */
    public Map<String, Object> refreshPayment(String paymentId) {
        HttpResponse<String> resp = post("/api/v1/payments/" + paymentId + "/refresh", "{}");
        return parseJson(resp.body());
    }

    /**
     * List all registered connectors.
     */
    public Map<String, Object> listConnectors() {
        HttpResponse<String> resp = get("/api/v1/payments/connectors");
        return parseJson(resp.body());
    }

    /**
     * Check connector health.
     */
    public Map<String, Object> connectorHealth(String connectorId) {
        HttpResponse<String> resp = get("/api/v1/payments/connectors/" + connectorId + "/health");
        return parseJson(resp.body());
    }

    /**
     * List routing rules.
     */
    public Map<String, Object> listRoutingRules() {
        HttpResponse<String> resp = get("/api/v1/payments/routing-rules");
        return parseJson(resp.body());
    }

    // === HTTP helpers ===

    private HttpResponse<String> post(String path, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("NexusChain SDK: POST " + path + " failed: " + e.getMessage(), e);
        }
    }

    private HttpResponse<String> get(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();
            return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("NexusChain SDK: GET " + path + " failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        // Minimal JSON parsing - in production use Jackson/Gson
        // For SDK simplicity, return raw string wrapped in a map
        return Map.of("_raw", json != null ? json : "{}");
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}