package org.nexus.gateway.orchestration.connectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.nexus.gateway.orchestration.connector.ConnectorHealth;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.ConnectorRefundResult;
import org.nexus.gateway.orchestration.connector.PaymentStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link StripeConnector} 单元测试：覆盖 dry-run（无 apiKey）与 real（有 apiKey + mock
 * RestTemplate）两条路径，包含支付创建、查询、退款、健康检查与状态映射。
 */
class StripeConnectorTest {

    private StripeConnector newConnector() {
        return new StripeConnector();
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private RestTemplate injectRestTemplate(StripeConnector c) throws Exception {
        RestTemplate rt = mock(RestTemplate.class);
        setField(c, "restTemplate", rt);
        return rt;
    }

    private ConnectorPaymentRequest sampleRequest() {
        ConnectorPaymentRequest req = new ConnectorPaymentRequest("pay_1", 5000L, "USD", "test");
        return req;
    }

    // ---------- metadata ----------

    @Test
    @DisplayName("metadata: id/type/displayName/feeBps/currencies")
    void metadata() {
        StripeConnector c = newConnector();
        assertEquals("stripe", c.getId());
        assertEquals("http_psp", c.getType());
        assertEquals("Stripe (Card / Wallet / BNPL)", c.getDisplayName());
        assertEquals(290, c.feeBasisPoints());
        assertTrue(c.supportedCurrencies().isEmpty());
    }

    @Test
    @DisplayName("isActive: enabled=true -> true；默认 false")
    void isActive() throws Exception {
        StripeConnector c = newConnector();
        assertFalse(c.isActive());
        setField(c, "enabled", true);
        assertTrue(c.isActive());
    }

    // ---------- dry-run mode (apiKey blank) ----------

    @Test
    @DisplayName("dry-run createPayment: SUCCEEDED + pi_dryrun_ 前缀")
    void dryRun_createPayment() {
        StripeConnector c = newConnector();
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.SUCCEEDED, r.getStatus());
        assertTrue(r.getConnectorPaymentId().startsWith("pi_dryrun_"));
    }

    @Test
    @DisplayName("dry-run queryPayment: 已创建 -> SUCCEEDED；未知 -> FAILED")
    void dryRun_queryPayment() {
        StripeConnector c = newConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.SUCCEEDED, c.queryPayment(created.getConnectorPaymentId()));
        assertEquals(PaymentStatus.FAILED, c.queryPayment("unknown"));
    }

    @Test
    @DisplayName("dry-run refund: ok + re_dryrun_ 前缀")
    void dryRun_refund() {
        StripeConnector c = newConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        ConnectorRefundResult r = c.refund(created.getConnectorPaymentId(), 1000L);
        assertTrue(r.isSuccess());
        assertTrue(r.getRefundId().startsWith("re_dryrun_"));
    }

    @Test
    @DisplayName("healthCheck: disabled -> down")
    void healthCheck_disabled() throws Exception {
        StripeConnector c = newConnector();
        // enabled 默认 false
        ConnectorHealth h = c.healthCheck();
        assertFalse(h.isHealthy());
    }

    @Test
    @DisplayName("healthCheck: enabled + dry-run -> up")
    void healthCheck_enabledDryRun() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "enabled", true);
        // apiKey 默认空 -> dry-run
        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertEquals(0L, h.getLatencyMs());
    }

    // ---------- real mode (apiKey set + mock RestTemplate) ----------

    @Test
    @DisplayName("real createPayment: 返回 succeeded -> SUCCEEDED")
    void real_createPayment_succeeded() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "apiKey", "sk_test_123");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", "pi_abc", "status", "succeeded"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.SUCCEEDED, r.getStatus());
        assertEquals("pi_abc", r.getConnectorPaymentId());
    }

    @Test
    @DisplayName("real createPayment: 返回 processing -> PROCESSING")
    void real_createPayment_processing() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "apiKey", "sk_test_123");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", "pi_x", "status", "requires_capture"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.PROCESSING, r.getStatus());
    }

    @Test
    @DisplayName("real createPayment: 返回 canceled -> CANCELLED")
    void real_createPayment_cancelled() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "apiKey", "sk_test_123");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", "pi_x", "status", "canceled"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.CANCELLED, r.getStatus());
    }

    @Test
    @DisplayName("real createPayment: 未知 status -> FAILED")
    void real_createPayment_unknownStatus() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "apiKey", "sk_test_123");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", "pi_x", "status", "weird_status"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.FAILED, r.getStatus());
    }

    @Test
    @DisplayName("real createPayment: 空 body -> fail")
    void real_createPayment_emptyBody() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "apiKey", "sk_test_123");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("real createPayment: RestTemplate 抛异常 -> fail")
    void real_createPayment_exception() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "apiKey", "sk_test_123");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertFalse(r.isSuccess());
        assertNotNull(r.getErrorMessage());
    }

    @Test
    @DisplayName("real queryPayment: 返回 succeeded -> SUCCEEDED")
    void real_queryPayment_succeeded() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "apiKey", "sk_test_123");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("status", "succeeded"), HttpStatus.OK));

        PaymentStatus s = c.queryPayment("pi_abc");
        assertEquals(PaymentStatus.SUCCEEDED, s);
    }

    @Test
    @DisplayName("real queryPayment: 返回 processing -> PROCESSING")
    void real_queryPayment_processing() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "apiKey", "sk_test_123");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("status", "processing"), HttpStatus.OK));

        PaymentStatus s = c.queryPayment("pi_abc");
        assertEquals(PaymentStatus.PROCESSING, s);
    }

    @Test
    @DisplayName("real queryPayment: 空 body -> 回退 localState FAILED")
    void real_queryPayment_emptyBody() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "apiKey", "sk_test_123");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        PaymentStatus s = c.queryPayment("pi_unknown");
        assertEquals(PaymentStatus.FAILED, s);
    }

    @Test
    @DisplayName("real queryPayment: exchange 抛异常 -> 回退 localState FAILED")
    void real_queryPayment_exception() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "apiKey", "sk_test_123");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        PaymentStatus s = c.queryPayment("pi_unknown");
        assertEquals(PaymentStatus.FAILED, s);
    }

    @Test
    @DisplayName("real refund: 返回非空 body -> ok")
    void real_refund_success() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "apiKey", "sk_test_123");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", "re_123"), HttpStatus.OK));

        ConnectorRefundResult r = c.refund("pi_abc", 1000L);
        assertTrue(r.isSuccess());
        assertEquals("re_123", r.getRefundId());
    }

    @Test
    @DisplayName("real refund: 空 body -> fail")
    void real_refund_emptyBody() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "apiKey", "sk_test_123");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        ConnectorRefundResult r = c.refund("pi_abc", 1000L);
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("real refund: RestTemplate 抛异常 -> fail")
    void real_refund_exception() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "apiKey", "sk_test_123");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        ConnectorRefundResult r = c.refund("pi_abc", 1000L);
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("healthCheck: enabled + apiKey + exchange 成功 -> up")
    void healthCheck_real_up() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "enabled", true);
        setField(c, "apiKey", "sk_test_123");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", "acct_1"), HttpStatus.OK));

        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertTrue(h.getLatencyMs() >= 0);
    }

    @Test
    @DisplayName("healthCheck: enabled + apiKey + exchange 异常 -> down")
    void healthCheck_real_down() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "enabled", true);
        setField(c, "apiKey", "sk_test_123");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        ConnectorHealth h = c.healthCheck();
        assertFalse(h.isHealthy());
    }

    @Test
    @DisplayName("real createPayment: description=null 不抛异常")
    void real_createPayment_nullDescription() throws Exception {
        StripeConnector c = newConnector();
        setField(c, "apiKey", "sk_test_123");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", "pi_x", "status", "succeeded"),
                        HttpStatus.OK));

        ConnectorPaymentRequest req = new ConnectorPaymentRequest("pay_1", 1000L, "usd", null);
        ConnectorPaymentResult r = c.createPayment(req);
        assertTrue(r.isSuccess());
    }
}