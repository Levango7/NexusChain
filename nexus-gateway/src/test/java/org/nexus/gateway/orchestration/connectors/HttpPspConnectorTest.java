package org.nexus.gateway.orchestration.connectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link HttpPspConnector} 单元测试：
 * <ul>
 *   <li>dry-run 模式（apiKey 为空）— 内存 Map 模拟，向后兼容</li>
 *   <li>real 模式（apiKey 非空）— RestTemplate mock 验证 HTTP 调用与状态映射</li>
 * </ul>
 */
class HttpPspConnectorTest {

    /** dry-run connector (no apiKey). */
    private HttpPspConnector dryRunConnector() {
        return new HttpPspConnector("psp1", "My PSP", "https://api.psp.example",
                null, 150, Set.of("USD", "EUR", "NEX"));
    }

    /** real-mode connector with mocked RestTemplate injected. */
    private HttpPspConnector realConnector(RestTemplate rt) {
        return new HttpPspConnector("psp1", "My PSP", "https://api.psp.example",
                "key-123", 150, Set.of("USD", "EUR", "NEX"), rt);
    }

    private ConnectorPaymentRequest sampleRequest() {
        return new ConnectorPaymentRequest("pay_1", 10000L, "USD", "test payment");
    }

    // ---------- metadata ----------

    @Test
    @DisplayName("metadata: id/type/displayName/active/feeBps/currencies")
    void metadata() {
        HttpPspConnector c = dryRunConnector();
        assertEquals("psp1", c.getId());
        assertEquals("http_psp", c.getType());
        assertEquals("My PSP", c.getDisplayName());
        assertTrue(c.isActive());
        assertEquals(150, c.feeBasisPoints());
        assertTrue(c.supportedCurrencies().contains("USD"));
        assertEquals(3, c.supportedCurrencies().size());
    }

    @Test
    @DisplayName("setActive(false) 后 isActive 返回 false")
    void setActive_false() {
        HttpPspConnector c = dryRunConnector();
        c.setActive(false);
        assertFalse(c.isActive());
    }

    // ---------- dry-run mode (apiKey null/blank) ----------

    @Test
    @DisplayName("dry-run createPayment: 成功 -> PROCESSING + connectorPaymentId 以 id 为前缀")
    void dryRun_createPayment_success() {
        HttpPspConnector c = dryRunConnector();
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.PROCESSING, r.getStatus());
        assertNotNull(r.getConnectorPaymentId());
        assertTrue(r.getConnectorPaymentId().startsWith("psp1_"));
    }

    @Test
    @DisplayName("dry-run queryPayment: 已创建 -> PROCESSING；未知 -> FAILED")
    void dryRun_queryPayment_knownAndUnknown() {
        HttpPspConnector c = dryRunConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.PROCESSING, c.queryPayment(created.getConnectorPaymentId()));
        assertEquals(PaymentStatus.FAILED, c.queryPayment("non-existent"));
    }

    @Test
    @DisplayName("dry-run refund: 已知 payment -> ok + 状态变 REFUNDED")
    void dryRun_refund_knownPayment() {
        HttpPspConnector c = dryRunConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        ConnectorRefundResult r = c.refund(created.getConnectorPaymentId(), 5000L);
        assertTrue(r.isSuccess());
        assertNotNull(r.getRefundId());
        assertEquals(PaymentStatus.REFUNDED, c.queryPayment(created.getConnectorPaymentId()));
    }

    @Test
    @DisplayName("dry-run refund: 未知 payment -> fail")
    void dryRun_refund_unknownPayment() {
        HttpPspConnector c = dryRunConnector();
        ConnectorRefundResult r = c.refund("unknown-payout", 100L);
        assertFalse(r.isSuccess());
        assertNotNull(r.getErrorMessage());
    }

    @Test
    @DisplayName("dry-run healthCheck: 始终 up（无外部调用）")
    void dryRun_healthCheck_up() {
        HttpPspConnector c = dryRunConnector();
        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertEquals("psp1", h.getConnectorId());
        assertTrue(h.getLatencyMs() >= 0);
    }

    // ---------- real mode (apiKey set + mock RestTemplate) ----------

    @Test
    @DisplayName("real createPayment: 返回 succeeded -> SUCCEEDED")
    void real_createPayment_succeeded() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", "pay_xyz", "status", "succeeded"), HttpStatus.OK));
        HttpPspConnector c = realConnector(rt);

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.SUCCEEDED, r.getStatus());
        assertEquals("pay_xyz", r.getConnectorPaymentId());
    }

    @Test
    @DisplayName("real createPayment: 返回 processing -> PROCESSING")
    void real_createPayment_processing() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", "pay_x", "status", "processing"), HttpStatus.OK));
        HttpPspConnector c = realConnector(rt);

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.PROCESSING, r.getStatus());
    }

    @Test
    @DisplayName("real createPayment: 返回 cancelled -> CANCELLED")
    void real_createPayment_cancelled() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", "pay_x", "status", "cancelled"), HttpStatus.OK));
        HttpPspConnector c = realConnector(rt);

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.CANCELLED, r.getStatus());
    }

    @Test
    @DisplayName("real createPayment: 空 body -> fail")
    void real_createPayment_emptyBody() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>((Map) null, HttpStatus.OK));
        HttpPspConnector c = realConnector(rt);

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("real createPayment: RestTemplate 抛异常 -> fail")
    void real_createPayment_exception() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new org.springframework.web.client.RestClientException("network error") {});
        HttpPspConnector c = realConnector(rt);

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertFalse(r.isSuccess());
        assertNotNull(r.getErrorMessage());
    }

    @Test
    @DisplayName("real queryPayment: 返回 succeeded -> SUCCEEDED")
    void real_queryPayment_succeeded() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("status", "succeeded"), HttpStatus.OK));
        HttpPspConnector c = realConnector(rt);

        PaymentStatus s = c.queryPayment("pay_xyz");
        assertEquals(PaymentStatus.SUCCEEDED, s);
    }

    @Test
    @DisplayName("real queryPayment: 异常 -> 回退 localState FAILED")
    void real_queryPayment_exception() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new org.springframework.web.client.RestClientException("network error") {});
        HttpPspConnector c = realConnector(rt);

        PaymentStatus s = c.queryPayment("pay_unknown");
        assertEquals(PaymentStatus.FAILED, s);
    }

    @Test
    @DisplayName("real refund: 返回非空 body -> ok")
    void real_refund_success() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", "re_123"), HttpStatus.OK));
        HttpPspConnector c = realConnector(rt);

        ConnectorRefundResult r = c.refund("pay_xyz", 1000L);
        assertTrue(r.isSuccess());
        assertEquals("re_123", r.getRefundId());
    }

    @Test
    @DisplayName("real refund: 异常 -> fail")
    void real_refund_exception() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new org.springframework.web.client.RestClientException("network error") {});
        HttpPspConnector c = realConnector(rt);

        ConnectorRefundResult r = c.refund("pay_xyz", 1000L);
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("real healthCheck: GET /health 2xx -> up")
    void real_healthCheck_up() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(contains("/health"), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("status", "ok"), HttpStatus.OK));
        HttpPspConnector c = realConnector(rt);

        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertTrue(h.getLatencyMs() >= 0);
    }

    @Test
    @DisplayName("real healthCheck: 异常 -> down")
    void real_healthCheck_down() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(contains("/health"), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new org.springframework.web.client.RestClientException("network error") {});
        HttpPspConnector c = realConnector(rt);

        ConnectorHealth h = c.healthCheck();
        assertFalse(h.isHealthy());
    }

    @Test
    @DisplayName("baseUrl 末尾的 / 会被规范化")
    void baseUrl_trailingSlashNormalized() {
        HttpPspConnector c = new HttpPspConnector("psp1", "My PSP", "https://api.psp.example/",
                null, 100, Set.of("USD"));
        // 内部 baseUrl 已去除末尾 /；通过 dry-run createPayment 间接验证不会抛异常
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
    }
}
