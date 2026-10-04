package org.nexus.gateway.orchestration.connectors;

import org.nexus.gateway.orchestration.connector.*;
import org.nexus.gateway.orchestration.settlement.FinalityPolicy;
import org.nexus.gateway.orchestration.settlement.PspFinalityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stripe Payment Connector - integrates with Stripe's PaymentIntents API.
 * Requires: stripe.api-key in application.yml (or env STRIPE_API_KEY).
 *
 * <p><b>凭证与 dry-run（安全）</b>：默认 {@code nexus.connectors.stripe.dry-run=false}，
 * 此时缺失 api-key 一律 <b>fail-closed</b>（拒绝交易，绝不静默假成功）；仅当显式开启
 * dry-run（sandbox profile）且缺少凭证时，才走模拟成功路径。生产环境严禁开启 dry-run。</p>
 *
 * <p>性能优化（任务 #310）：注入共享的连接池化 RestTemplate。</p>
 */
@Component
public class StripeConnector implements PaymentConnector {

    private static final Logger log = LoggerFactory.getLogger(StripeConnector.class);
    private static final String DEFAULT_STRIPE_API_BASE = "https://api.stripe.com/v1";
    private static final String CREDENTIALS_MISSING =
            "Stripe connector misconfigured: api-key missing and dry-run disabled";

    @Value("${nexus.connectors.stripe.api-key:}")
    private String apiKey;

    @Value("${nexus.connectors.stripe.enabled:false}")
    private boolean enabled;

    /**
     * dry-run 显式开关（默认 false）。仅当显式开启时，缺失 api-key 才允许模拟成功
     * （sandbox/单测场景）；生产必须保持 false，缺失凭证一律 fail-closed。
     */
    @Value("${nexus.connectors.stripe.dry-run:false}")
    private boolean dryRun;

    /**
     * Stripe API base URL — 默认 {@code https://api.stripe.com/v1}，可通过
     * {@code nexus.connectors.stripe.api-base-url} 覆盖（测试用 WireMock 指向本地端口）。
     */
    @Value("${nexus.connectors.stripe.api-base-url:https://api.stripe.com/v1}")
    private String apiBase = DEFAULT_STRIPE_API_BASE;

    private final RestTemplate restTemplate;
    private final Map<String, PaymentStatus> localState = new ConcurrentHashMap<>();

    @Autowired
    public StripeConnector(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 测试用兼容构造器。 */
    public StripeConnector() {
        this.restTemplate = new RestTemplate();
    }

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
        if (isDryRun()) {
            // Dry-run：仅在 nexus.connectors.stripe.dry-run=true 时可达（sandbox/单测）
            String id = "pi_dryrun_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            localState.put(id, PaymentStatus.SUCCEEDED);
            log.info("[Stripe DRY-RUN] PaymentIntent created: {} amount={} {}", id, request.getAmount(), request.getCurrency());
            return ConnectorPaymentResult.ok(id, PaymentStatus.SUCCEEDED);
        }
        if (!hasCredentials()) {
            log.error("[Stripe] api-key 缺失且 dry-run 已关闭，拒绝创建支付（fail-closed）");
            return ConnectorPaymentResult.fail(CREDENTIALS_MISSING);
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
        } catch (RuntimeException e) {
            log.error("[Stripe] createPayment failed: {}", e.getMessage());
            return ConnectorPaymentResult.fail("Stripe error: " + e.getMessage());
        }
    }

    @Override
    public PaymentStatus queryPayment(String connectorPaymentId) {
        if (isDryRun()) {
            return localState.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
        }
        if (!hasCredentials()) {
            log.error("[Stripe] api-key 缺失且 dry-run 已关闭，无法查询（fail-closed）");
            return PaymentStatus.FAILED;
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
        } catch (RuntimeException e) {
            log.warn("[Stripe] query failed for {}: {}", connectorPaymentId, e.getMessage());
        }
        return localState.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
    }

    @Override
    public ConnectorRefundResult refund(String connectorPaymentId, long amount) {
        if (isDryRun()) {
            localState.put(connectorPaymentId, PaymentStatus.REFUNDED);
            return ConnectorRefundResult.ok("re_dryrun_" + connectorPaymentId);
        }
        if (!hasCredentials()) {
            log.error("[Stripe] api-key 缺失且 dry-run 已关闭，拒绝退款（fail-closed）");
            return ConnectorRefundResult.fail(CREDENTIALS_MISSING);
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
        } catch (RuntimeException e) {
            return ConnectorRefundResult.fail("Stripe refund error: " + e.getMessage());
        }
    }

    @Override
    public ConnectorHealth healthCheck() {
        if (!enabled) return ConnectorHealth.down(getId(), "Connector disabled");
        if (isDryRun()) return ConnectorHealth.up(getId(), 0); // dry-run: 无外部依赖
        if (!hasCredentials()) return ConnectorHealth.down(getId(), "misconfigured: api-key missing and dry-run disabled");
        long start = System.currentTimeMillis();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            restTemplate.exchange(apiBase + "/account", HttpMethod.GET, entity, Map.class);
            return ConnectorHealth.up(getId(), System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            return ConnectorHealth.down(getId(), e.getMessage());
        }
    }

    @Override
    public Set<String> supportedCurrencies() { return Set.of(); } // Stripe supports 135+ currencies

    @Override
    public FinalityPolicy getFinalityPolicy() {
        return new PspFinalityPolicy();
    }

    @Override
    public int feeBasisPoints() { return 290; } // 2.9% typical card fee

    /** dry-run 是否被显式开启。 */
    private boolean isDryRun() {
        return dryRun;
    }

    /** 是否具备真实调用所需凭证。 */
    private boolean hasCredentials() {
        return apiKey != null && !apiKey.isBlank();
    }

    private PaymentStatus mapStripeStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "succeeded" -> PaymentStatus.SUCCEEDED;
            case "processing", "requires_capture", "requires_confirmation" -> PaymentStatus.PROCESSING;
            case "canceled" -> PaymentStatus.CANCELLED;
            default -> PaymentStatus.FAILED;
        };
    }
}