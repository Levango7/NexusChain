package org.nexus.gateway.orchestration.connectors;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.nexus.gateway.orchestration.connector.ConnectorHealth;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.ConnectorRefundResult;
import org.nexus.gateway.orchestration.connector.PaymentStatus;

import java.lang.reflect.Field;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link StripeConnector} WireMock 集成测试 — 用 WireMock 启动本地 HTTP server 模拟
 * Stripe PaymentIntents / Refunds / Account API，验证：
 * <ul>
 *   <li>请求格式：URL 路径、Bearer token、form-urlencoded body、字段映射</li>
 *   <li>响应状态映射：succeeded/processing/canceled/unknown → SUCCEEDED/PROCESSING/CANCELLED/FAILED</li>
 *   <li>错误处理：4xx/5xx/网络异常 → fail</li>
 *   <li>dry-run 行为：apiKey 为空时不发 HTTP，返回 pi_dryrun_ 前缀</li>
 *   <li>queryPayment / refund / healthCheck 端到端</li>
 * </ul>
 *
 * <p>WireMock 3.5.2 standalone（含 Jetty）通过 build.gradle testImplementation 引入。
 * 测试不启动 Spring 上下文，直接 new StripeConnector + 反射注入 @Value 字段。</p>
 */
@DisplayName("StripeConnector WireMock 集成测试")
class StripeConnectorWireMockTest {

    private static WireMockServer wireMock;
    private static String apiBase;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
        apiBase = wireMock.baseUrl() + "/v1";
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void resetStubs() {
        WireMock.reset();
    }

    private StripeConnector newConnector(String apiKey, boolean enabled) throws Exception {
        StripeConnector c = new StripeConnector();
        setField(c, "apiKey", apiKey);
        setField(c, "enabled", enabled);
        setField(c, "apiBase", apiBase);
        return c;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private ConnectorPaymentRequest sampleRequest() {
        return new ConnectorPaymentRequest("pay_1", 5000L, "USD", "test order");
    }

    // ---------- dry-run mode ----------

    @Test
    @DisplayName("dry-run: apiKey 为空时不发 HTTP，返回 pi_dryrun_ 前缀 + SUCCEEDED")
    void dryRun_noHttpCall() throws Exception {
        StripeConnector c = newConnector("", true);
        ConnectorPaymentResult r = c.createPayment(sampleRequest());

        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.SUCCEEDED, r.getStatus());
        assertTrue(r.getConnectorPaymentId().startsWith("pi_dryrun_"));
        // WireMock 不应有任何请求
        wireMock.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    }

    @Test
    @DisplayName("dry-run healthCheck: enabled=true + apiKey 空 -> up（不调 /account）")
    void dryRun_healthCheck_up() throws Exception {
        StripeConnector c = newConnector("", true);
        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertEquals(0L, h.getLatencyMs());
        wireMock.verify(0, WireMock.anyRequestedFor(WireMock.urlPathMatching("/v1/account")));
    }

    // ---------- real mode: createPayment ----------

    @Test
    @DisplayName("real createPayment: POST /v1/payment_intents + Bearer + form body；succeeded → SUCCEEDED")
    void real_createPayment_succeeded() throws Exception {
        stubFor(post(urlPathMatching("/v1/payment_intents"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"pi_test_123\",\"status\":\"succeeded\"}")));

        StripeConnector c = newConnector("sk_test_abc", true);
        ConnectorPaymentResult r = c.createPayment(sampleRequest());

        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.SUCCEEDED, r.getStatus());
        assertEquals("pi_test_123", r.getConnectorPaymentId());

        // 验证请求格式
        var req = wireMock.findAll(postRequestedFor(urlPathMatching("/v1/payment_intents")));
        assertEquals(1, req.size());
        // Bearer auth
        assertEquals("Bearer sk_test_abc", req.get(0).getHeader("Authorization"));
        // form-urlencoded body 包含 amount/currency
        String body = req.get(0).getBodyAsString();
        assertTrue(body.contains("amount=5000"));
        assertTrue(body.contains("currency=usd"));
    }

    @Test
    @DisplayName("real createPayment: requires_capture → PROCESSING")
    void real_createPayment_processing() throws Exception {
        stubFor(post(urlPathMatching("/v1/payment_intents"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"pi_x\",\"status\":\"requires_capture\"}")));

        StripeConnector c = newConnector("sk_test_abc", true);
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.PROCESSING, r.getStatus());
    }

    @Test
    @DisplayName("real createPayment: canceled → CANCELLED")
    void real_createPayment_cancelled() throws Exception {
        stubFor(post(urlPathMatching("/v1/payment_intents"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"pi_x\",\"status\":\"canceled\"}")));

        StripeConnector c = newConnector("sk_test_abc", true);
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.CANCELLED, r.getStatus());
    }

    @Test
    @DisplayName("real createPayment: 未知 status → FAILED")
    void real_createPayment_unknownStatus() throws Exception {
        stubFor(post(urlPathMatching("/v1/payment_intents"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"pi_x\",\"status\":\"weird\"}")));

        StripeConnector c = newConnector("sk_test_abc", true);
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.FAILED, r.getStatus());
    }

    @Test
    @DisplayName("real createPayment: 402 Stripe 错误 → fail")
    void real_createPayment_stripeError() throws Exception {
        stubFor(post(urlPathMatching("/v1/payment_intents"))
                .willReturn(aResponse().withStatus(402)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"type\":\"card_error\",\"message\":\"Insufficient funds\"}}")));

        StripeConnector c = newConnector("sk_test_abc", true);
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertFalse(r.isSuccess());
        assertNotNull(r.getErrorMessage());
    }

    @Test
    @DisplayName("real createPayment: 5xx 服务器错误 → fail")
    void real_createPayment_serverError() throws Exception {
        stubFor(post(urlPathMatching("/v1/payment_intents"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        StripeConnector c = newConnector("sk_test_abc", true);
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("real createPayment: 空 body → fail")
    void real_createPayment_emptyBody() throws Exception {
        stubFor(post(urlPathMatching("/v1/payment_intents"))
                .willReturn(aResponse().withStatus(200).withBody("")));

        StripeConnector c = newConnector("sk_test_abc", true);
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertFalse(r.isSuccess());
    }

    // ---------- real mode: queryPayment ----------

    @Test
    @DisplayName("real queryPayment: GET /v1/payment_intents/{id}；succeeded → SUCCEEDED")
    void real_queryPayment_succeeded() throws Exception {
        stubFor(get(urlPathMatching("/v1/payment_intents/pi_test_123"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"pi_test_123\",\"status\":\"succeeded\"}")));

        StripeConnector c = newConnector("sk_test_abc", true);
        PaymentStatus s = c.queryPayment("pi_test_123");
        assertEquals(PaymentStatus.SUCCEEDED, s);

        var req = wireMock.findAll(getRequestedFor(urlPathMatching("/v1/payment_intents/pi_test_123")));
        assertEquals(1, req.size());
        assertEquals("Bearer sk_test_abc", req.get(0).getHeader("Authorization"));
    }

    @Test
    @DisplayName("real queryPayment: 404 → 回退 localState FAILED")
    void real_queryPayment_notFound() throws Exception {
        stubFor(get(urlPathMatching("/v1/payment_intents/pi_unknown"))
                .willReturn(aResponse().withStatus(404)));

        StripeConnector c = newConnector("sk_test_abc", true);
        PaymentStatus s = c.queryPayment("pi_unknown");
        assertEquals(PaymentStatus.FAILED, s);
    }

    // ---------- real mode: refund ----------

    @Test
    @DisplayName("real refund: POST /v1/refunds + payment_intent + amount；返回 id")
    void real_refund_success() throws Exception {
        stubFor(post(urlPathMatching("/v1/refunds"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"re_test_456\",\"object\":\"refund\",\"amount\":1000}")));

        StripeConnector c = newConnector("sk_test_abc", true);
        ConnectorRefundResult r = c.refund("pi_test_123", 1000L);

        assertTrue(r.isSuccess());
        assertEquals("re_test_456", r.getRefundId());

        var req = wireMock.findAll(postRequestedFor(urlPathMatching("/v1/refunds")));
        assertEquals(1, req.size());
        String body = req.get(0).getBodyAsString();
        assertTrue(body.contains("payment_intent=pi_test_123"));
        assertTrue(body.contains("amount=1000"));
    }

    @Test
    @DisplayName("real refund: 400 → fail")
    void real_refund_error() throws Exception {
        stubFor(post(urlPathMatching("/v1/refunds"))
                .willReturn(aResponse().withStatus(400)
                        .withBody("{\"error\":{\"message\":\"Already refunded\"}}")));

        StripeConnector c = newConnector("sk_test_abc", true);
        ConnectorRefundResult r = c.refund("pi_test_123", 1000L);
        assertFalse(r.isSuccess());
    }

    // ---------- real mode: healthCheck ----------

    @Test
    @DisplayName("real healthCheck: GET /v1/account 200 → up")
    void real_healthCheck_up() throws Exception {
        stubFor(get(urlPathMatching("/v1/account"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"acct_1\"}")));

        StripeConnector c = newConnector("sk_test_abc", true);
        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertTrue(h.getLatencyMs() >= 0);
    }

    @Test
    @DisplayName("real healthCheck: 401 → down")
    void real_healthCheck_down() throws Exception {
        stubFor(get(urlPathMatching("/v1/account"))
                .willReturn(aResponse().withStatus(401)));

        StripeConnector c = newConnector("sk_test_abc", true);
        ConnectorHealth h = c.healthCheck();
        assertFalse(h.isHealthy());
    }

    @Test
    @DisplayName("healthCheck: enabled=false → down（不调 /account）")
    void healthCheck_disabled() throws Exception {
        StripeConnector c = newConnector("sk_test_abc", false);
        ConnectorHealth h = c.healthCheck();
        assertFalse(h.isHealthy());
        wireMock.verify(0, WireMock.anyRequestedFor(WireMock.urlPathMatching("/v1/account")));
    }
}