package org.nexus.gateway.orchestration.connectors;

import org.nexus.gateway.orchestration.connector.*;
import org.nexus.gateway.orchestration.settlement.FinalityPolicy;
import org.nexus.gateway.orchestration.settlement.PspFinalityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic HTTP PSP Connector - adapts any REST-based payment service provider.
 *
 * <p>Configure with base URL + API key, and it maps to the standard connector interface.
 * The contract follows a generic REST schema:
 * <ul>
 *   <li>{@code POST {baseUrl}/payments} — create payment; response body fields:
 *       {@code id} (connector payment id), {@code status} (one of
 *       succeeded/processing/cancelled/failed)</li>
 *   <li>{@code GET  {baseUrl}/payments/{id}} — query payment; response field {@code status}</li>
 *   <li>{@code POST {baseUrl}/payments/{id}/refund} — refund; response field {@code id} (refund id)</li>
 *   <li>{@code GET  {baseUrl}/health} — liveness probe; 2xx ⇒ UP, otherwise DOWN</li>
 * </ul></p>
 *
 * <h2>Dry-run fallback（显式开关，默认关闭）</h2>
 * dry-run 仅在构造时显式传入 {@code dryRun=true} 时启用：不发 HTTP，生成合成 id 并记录
 * {@link PaymentStatus#PROCESSING}。默认 {@code dryRun=false}，此时 apiKey 为空一律
 * <b>fail-closed</b>，避免“未配置密钥却支付成功”的静默假成功。
 */
public class HttpPspConnector implements PaymentConnector {

    private static final Logger log = LoggerFactory.getLogger(HttpPspConnector.class);

    private final String id;
    private final String displayName;
    private final String baseUrl;
    private final String apiKey;
    private final int feeBps;
    private final Set<String> currencies;
    private final RestTemplate restTemplate;
    private final boolean dryRun;
    private final Map<String, PaymentStatus> payments = new ConcurrentHashMap<>();
    private volatile boolean active = true;

    public HttpPspConnector(String id, String displayName, String baseUrl, String apiKey, int feeBps, Set<String> currencies) {
        this(id, displayName, baseUrl, apiKey, feeBps, currencies, false, new RestTemplate());
    }

    /**
     * Constructor with explicit RestTemplate — primarily for unit tests to inject a mock.
     * dry-run 默认关闭（fail-closed）。
     */
    public HttpPspConnector(String id, String displayName, String baseUrl, String apiKey,
                            int feeBps, Set<String> currencies, RestTemplate restTemplate) {
        this(id, displayName, baseUrl, apiKey, feeBps, currencies, false, restTemplate);
    }

    /**
     * Full constructor. {@code dryRun=true} 时才允许在 apiKey 为空时模拟成功
     * （sandbox/单测）；默认 false 表示无凭证一律 fail-closed。
     */
    public HttpPspConnector(String id, String displayName, String baseUrl, String apiKey,
                            int feeBps, Set<String> currencies, boolean dryRun, RestTemplate restTemplate) {
        this.id = id;
        this.displayName = displayName;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
        this.feeBps = feeBps;
        this.currencies = currencies;
        this.dryRun = dryRun;
        this.restTemplate = restTemplate;
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

    /**
     * @return true only when dry-run was explicitly enabled at construction.
     *         开启了 dry-run 才允许无 HTTP 模拟成功；否则无凭证必须 fail-closed。
     */
    private boolean isDryRun() {
        return dryRun;
    }

    /** 是否具备真实调用所需凭证。 */
    private boolean hasCredentials() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ConnectorPaymentResult createPayment(ConnectorPaymentRequest request) {
        // Dry-run: keep backward-compatible behaviour (synthetic id + PROCESSING).
        if (isDryRun()) {
            String connectorPaymentId = id + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            payments.put(connectorPaymentId, PaymentStatus.PROCESSING);
            log.info("HTTP PSP [{}] (dry-run) payment created: {} amount={} {}",
                    id, connectorPaymentId, request.getAmount(), request.getCurrency());
            return ConnectorPaymentResult.ok(connectorPaymentId, PaymentStatus.PROCESSING);
        }
        if (!hasCredentials()) {
            log.error("HTTP PSP [{}] apiKey 缺失且 dry-run 已关闭，拒绝创建支付（fail-closed）", id);
            return ConnectorPaymentResult.fail("PSP [" + id + "] misconfigured: apiKey missing and dry-run disabled");
        }

        try {
            HttpHeaders headers = jsonHeadersWithAuth();
            String body = String.format(
                    "{\"amount\":%d,\"currency\":\"%s\",\"reference\":\"%s\",\"description\":\"%s\"}",
                    request.getAmount(),
                    request.getCurrency() == null ? "" : request.getCurrency(),
                    request.getPaymentId() == null ? "" : request.getPaymentId(),
                    request.getDescription() == null ? "" : request.getDescription());
            HttpEntity<String> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> resp = restTemplate.postForEntity(baseUrl + "/payments", entity, Map.class);
            if (resp.getBody() == null) {
                return ConnectorPaymentResult.fail("PSP [" + id + "] returned empty response");
            }
            String connectorPaymentId = String.valueOf(resp.getBody().getOrDefault("id", ""));
            String status = String.valueOf(resp.getBody().getOrDefault("status", "processing"));
            PaymentStatus mapped = mapStatus(status);
            payments.put(connectorPaymentId, mapped);
            log.info("HTTP PSP [{}] payment created: {} status={}", id, connectorPaymentId, status);
            return ConnectorPaymentResult.ok(connectorPaymentId, mapped);
        } catch (RestClientException e) {
            log.error("HTTP PSP [{}] createPayment failed: {}", id, e.getMessage());
            return ConnectorPaymentResult.fail("PSP error: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("HTTP PSP [{}] createPayment unexpected error: {}", id, e.getMessage());
            return ConnectorPaymentResult.fail("PSP error: " + e.getMessage());
        }
    }

    @Override
    public PaymentStatus queryPayment(String connectorPaymentId) {
        if (isDryRun()) {
            return payments.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
        }
        if (!hasCredentials()) {
            log.error("HTTP PSP [{}] apiKey 缺失且 dry-run 已关闭，无法查询（fail-closed）", id);
            return PaymentStatus.FAILED;
        }
        try {
            HttpHeaders headers = jsonHeadersWithAuth();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl + "/payments/" + connectorPaymentId, HttpMethod.GET, entity, Map.class);
            if (resp.getBody() != null) {
                PaymentStatus s = mapStatus(String.valueOf(resp.getBody().getOrDefault("status", "failed")));
                payments.put(connectorPaymentId, s);
                return s;
            }
        } catch (RestClientException e) {
            log.warn("HTTP PSP [{}] query failed for {}: {}", id, connectorPaymentId, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("HTTP PSP [{}] query unexpected error for {}: {}", id, connectorPaymentId, e.getMessage());
        }
        return payments.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
    }

    @Override
    public ConnectorRefundResult refund(String connectorPaymentId, long amount) {
        if (isDryRun()) {
            if (payments.containsKey(connectorPaymentId)) {
                payments.put(connectorPaymentId, PaymentStatus.REFUNDED);
                return ConnectorRefundResult.ok("refund_" + connectorPaymentId);
            }
            return ConnectorRefundResult.fail("Payment not found at PSP");
        }
        if (!hasCredentials()) {
            log.error("HTTP PSP [{}] apiKey 缺失且 dry-run 已关闭，拒绝退款（fail-closed）", id);
            return ConnectorRefundResult.fail("PSP [" + id + "] misconfigured: apiKey missing and dry-run disabled");
        }
        try {
            HttpHeaders headers = jsonHeadersWithAuth();
            String body = String.format("{\"amount\":%d}", amount);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    baseUrl + "/payments/" + connectorPaymentId + "/refund", entity, Map.class);
            if (resp.getBody() != null) {
                payments.put(connectorPaymentId, PaymentStatus.REFUNDED);
                String refundId = String.valueOf(resp.getBody().getOrDefault("id", "refund_" + connectorPaymentId));
                return ConnectorRefundResult.ok(refundId);
            }
            return ConnectorRefundResult.fail("PSP [" + id + "] refund returned empty response");
        } catch (RestClientException e) {
            return ConnectorRefundResult.fail("PSP refund error: " + e.getMessage());
        } catch (RuntimeException e) {
            return ConnectorRefundResult.fail("PSP refund error: " + e.getMessage());
        }
    }

    @Override
    public ConnectorHealth healthCheck() {
        if (isDryRun()) {
            // Dry-run: 无外部依赖，视为健康
            return ConnectorHealth.up(id, 0);
        }
        if (!hasCredentials()) {
            return ConnectorHealth.down(id, "misconfigured: apiKey missing and dry-run disabled");
        }
        long start = System.currentTimeMillis();
        try {
            HttpHeaders headers = jsonHeadersWithAuth();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            restTemplate.exchange(baseUrl + "/health", HttpMethod.GET, entity, Map.class);
            return ConnectorHealth.up(id, System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            return ConnectorHealth.down(id, e.getMessage());
        }
    }

    @Override
    public Set<String> supportedCurrencies() { return currencies; }

    @Override
    public FinalityPolicy getFinalityPolicy() {
        return new PspFinalityPolicy();
    }

    @Override
    public int feeBasisPoints() { return feeBps; }

    // ---------- helpers ----------

    private HttpHeaders jsonHeadersWithAuth() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        return headers;
    }

    /**
     * Map a generic PSP status string to {@link PaymentStatus}. PSPs that follow the
     * convention (succeeded/processing/cancelled/failed) map directly; unknown strings
     * default to {@link PaymentStatus#FAILED} (fail-closed).
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
