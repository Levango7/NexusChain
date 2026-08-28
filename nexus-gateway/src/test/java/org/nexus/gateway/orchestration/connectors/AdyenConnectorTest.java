package org.nexus.gateway.orchestration.connectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * {@link AdyenConnector} 单元测试：覆盖 dry-run（无 apiKey）与 real（有 apiKey + mock
 * RestTemplate）两条路径，包含支付创建、查询、退款、健康检查与结果映射。
 */
class AdyenConnectorTest {

    private AdyenConnector newConnector() {
        return new AdyenConnector();
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private RestTemplate injectRestTemplate(AdyenConnector c) throws Exception {
        RestTemplate rt = mock(RestTemplate.class);
        setField(c, "restTemplate", rt);
        return rt;
    }

    private ConnectorPaymentRequest sampleRequest() {
        return new ConnectorPaymentRequest("pay_1", 5000L, "USD", "test");
    }

    // ---------- metadata ----------

    @Test
    @DisplayName("metadata: id/type/displayName/feeBps/currencies")
    void metadata() {
        AdyenConnector c = newConnector();
        assertEquals("adyen", c.getId());
        assertEquals("http_psp", c.getType());
        assertEquals("Adyen (Global Acquiring)", c.getDisplayName());
        assertEquals(250, c.feeBasisPoints());
        assertTrue(c.supportedCurrencies().isEmpty());
    }

    @Test
    @DisplayName("isActive: enabled=true -> true；默认 false")
    void isActive() throws Exception {
        AdyenConnector c = newConnector();
        assertFalse(c.isActive());
        setField(c, "enabled", true);
        assertTrue(c.isActive());
    }

    // ---------- dry-run mode (apiKey blank) ----------

    @Test
    @DisplayName("dry-run createPayment: SUCCEEDED + adyen_dryrun_ 前缀")
    void dryRun_createPayment() {
        AdyenConnector c = newConnector();
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.SUCCEEDED, r.getStatus());
        assertTrue(r.getConnectorPaymentId().startsWith("adyen_dryrun_"));
    }

    @Test
    @DisplayName("dry-run queryPayment: 已创建 -> SUCCEEDED；未知 -> FAILED")
    void dryRun_queryPayment() {
        AdyenConnector c = newConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.SUCCEEDED, c.queryPayment(created.getConnectorPaymentId()));
        assertEquals(PaymentStatus.FAILED, c.queryPayment("unknown"));
    }

    @Test
    @DisplayName("dry-run refund: ok + adyen_refund_ 前缀")
    void dryRun_refund() {
        AdyenConnector c = newConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        ConnectorRefundResult r = c.refund(created.getConnectorPaymentId(), 1000L);
        assertTrue(r.isSuccess());
        assertTrue(r.getRefundId().startsWith("adyen_refund_"));
    }

    @Test
    @DisplayName("healthCheck: disabled -> down")
    void healthCheck_disabled() throws Exception {
        AdyenConnector c = newConnector();
        ConnectorHealth h = c.healthCheck();
        assertFalse(h.isHealthy());
    }

    @Test
    @DisplayName("healthCheck: enabled + dry-run -> up")
    void healthCheck_enabledDryRun() throws Exception {
        AdyenConnector c = newConnector();
        setField(c, "enabled", true);
        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertEquals(0L, h.getLatencyMs());
    }

    // ---------- real mode (apiKey set + mock RestTemplate) ----------

    @Test
    @DisplayName("real createPayment: Authorised -> SUCCEEDED")
    void real_createPayment_authorised() throws Exception {
        AdyenConnector c = newConnector();
        setField(c, "apiKey", "apiKey_123");
        setField(c, "merchantAccount", "TestAccount");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("pspReference", "ref1", "resultCode", "Authorised"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.SUCCEEDED, r.getStatus());
        assertEquals("ref1", r.getConnectorPaymentId());
    }

    @Test
    @DisplayName("real createPayment: Received -> SUCCEEDED")
    void real_createPayment_received() throws Exception {
        AdyenConnector c = newConnector();
        setField(c, "apiKey", "apiKey_123");
        setField(c, "merchantAccount", "TestAccount");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("pspReference", "ref1", "resultCode", "Received"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.SUCCEEDED, r.getStatus());
    }

    @Test
    @DisplayName("real createPayment: Pending -> PROCESSING")
    void real_createPayment_pending() throws Exception {
        AdyenConnector c = newConnector();
        setField(c, "apiKey", "apiKey_123");
        setField(c, "merchantAccount", "TestAccount");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("pspReference", "ref1", "resultCode", "Pending"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.PROCESSING, r.getStatus());
    }

    @Test
    @DisplayName("real createPayment: RedirectShopper -> PROCESSING")
    void real_createPayment_redirectShopper() throws Exception {
        AdyenConnector c = newConnector();
        setField(c, "apiKey", "apiKey_123");
        setField(c, "merchantAccount", "TestAccount");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("pspReference", "ref1", "resultCode", "RedirectShopper"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.PROCESSING, r.getStatus());
    }

    @Test
    @DisplayName("real createPayment: Cancelled -> CANCELLED")
    void real_createPayment_cancelled() throws Exception {
        AdyenConnector c = newConnector();
        setField(c, "apiKey", "apiKey_123");
        setField(c, "merchantAccount", "TestAccount");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("pspReference", "ref1", "resultCode", "Cancelled"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.CANCELLED, r.getStatus());
    }

    @Test
    @DisplayName("real createPayment: Refused -> FAILED")
    void real_createPayment_refused() throws Exception {
        AdyenConnector c = newConnector();
        setField(c, "apiKey", "apiKey_123");
        setField(c, "merchantAccount", "TestAccount");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("pspReference", "ref1", "resultCode", "Refused"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.FAILED, r.getStatus());
    }

    @Test
    @DisplayName("real createPayment: 缺字段使用默认值 unknown/Error -> FAILED")
    void real_createPayment_missingFields() throws Exception {
        AdyenConnector c = newConnector();
        setField(c, "apiKey", "apiKey_123");
        setField(c, "merchantAccount", "TestAccount");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.FAILED, r.getStatus());
    }

    @Test
    @DisplayName("real createPayment: 空 body -> fail")
    void real_createPayment_emptyBody() throws Exception {
        AdyenConnector c = newConnector();
        setField(c, "apiKey", "apiKey_123");
        setField(c, "merchantAccount", "TestAccount");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>((Map) null, HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("real createPayment: RestTemplate 抛异常 -> fail")
    void real_createPayment_exception() throws Exception {
        AdyenConnector c = newConnector();
        setField(c, "apiKey", "apiKey_123");
        setField(c, "merchantAccount", "TestAccount");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("real refund: 返回非空 body -> ok")
    void real_refund_success() throws Exception {
        AdyenConnector c = newConnector();
        setField(c, "apiKey", "apiKey_123");
        setField(c, "merchantAccount", "TestAccount");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("pspReference", "re_123"), HttpStatus.OK));

        ConnectorRefundResult r = c.refund("ref1", 1000L);
        assertTrue(r.isSuccess());
        assertEquals("re_123", r.getRefundId());
    }

    @Test
    @DisplayName("real refund: 空 body -> fail")
    void real_refund_emptyBody() throws Exception {
        AdyenConnector c = newConnector();
        setField(c, "apiKey", "apiKey_123");
        setField(c, "merchantAccount", "TestAccount");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>((Map) null, HttpStatus.OK));

        ConnectorRefundResult r = c.refund("ref1", 1000L);
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("real refund: RestTemplate 抛异常 -> fail")
    void real_refund_exception() throws Exception {
        AdyenConnector c = newConnector();
        setField(c, "apiKey", "apiKey_123");
        setField(c, "merchantAccount", "TestAccount");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        ConnectorRefundResult r = c.refund("ref1", 1000L);
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("healthCheck: enabled + apiKey + exchange 成功 -> up")
    void healthCheck_real_up() throws Exception {
        AdyenConnector c = newConnector();
        setField(c, "enabled", true);
        setField(c, "apiKey", "apiKey_123");
        setField(c, "merchantAccount", "TestAccount");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK));

        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertTrue(h.getLatencyMs() >= 0);
    }

    @Test
    @DisplayName("healthCheck: enabled + apiKey + exchange 异常 -> down")
    void healthCheck_real_down() throws Exception {
        AdyenConnector c = newConnector();
        setField(c, "enabled", true);
        setField(c, "apiKey", "apiKey_123");
        setField(c, "merchantAccount", "TestAccount");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        ConnectorHealth h = c.healthCheck();
        assertFalse(h.isHealthy());
    }
}