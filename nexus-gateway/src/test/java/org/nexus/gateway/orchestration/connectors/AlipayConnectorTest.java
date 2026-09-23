package org.nexus.gateway.orchestration.connectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connector.ConnectorHealth;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.ConnectorRefundResult;
import org.nexus.gateway.orchestration.connector.PaymentStatus;
import org.nexus.gateway.orchestration.settlement.FinalityPolicy;
import org.nexus.gateway.orchestration.settlement.PspFinalityPolicy;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AlipayConnector} 单元测试：覆盖 dry-run（无 merchantPrivateKey）与 real（有 key + mock
 * RestTemplate）两条路径，包含支付创建、查询、退款、健康检查与支付宝状态映射。
 */
class AlipayConnectorTest {

    private AlipayConnector newConnector() {
        return new AlipayConnector();
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private RestTemplate injectRestTemplate(AlipayConnector c) throws Exception {
        RestTemplate rt = mock(RestTemplate.class);
        setField(c, "restTemplate", rt);
        return rt;
    }

    private ConnectorPaymentRequest sampleRequest() {
        return new ConnectorPaymentRequest("pay_1", 5000L, "CNY", "test");
    }

    // ---------- 1. Dry-run 模式 createPayment 返回成功 ----------

    @Test
    @DisplayName("dry-run createPayment: 返回 SUCCEEDED")
    void dryRun_createPayment_success() {
        AlipayConnector c = newConnector();
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.SUCCEEDED, r.getStatus());
    }

    // ---------- 2. Dry-run 模式 createPayment 生成正确格式的 ID ----------

    @Test
    @DisplayName("dry-run createPayment: connectorPaymentId 以 alipay_dryrun_ 前缀")
    void dryRun_createPayment_idFormat() {
        AlipayConnector c = newConnector();
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.getConnectorPaymentId().startsWith("alipay_dryrun_"));
    }

    // ---------- 3. Dry-run 模式 queryPayment 返回缓存状态 ----------

    @Test
    @DisplayName("dry-run queryPayment: 已创建 -> SUCCEEDED；未知 -> FAILED")
    void dryRun_queryPayment_cachedState() {
        AlipayConnector c = newConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.SUCCEEDED, c.queryPayment(created.getConnectorPaymentId()));
        assertEquals(PaymentStatus.FAILED, c.queryPayment("unknown"));
    }

    // ---------- 4. Dry-run 模式 refund 返回成功 ----------

    @Test
    @DisplayName("dry-run refund: 返回 ok")
    void dryRun_refund_success() {
        AlipayConnector c = newConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        ConnectorRefundResult r = c.refund(created.getConnectorPaymentId(), 1000L);
        assertTrue(r.isSuccess());
        assertTrue(r.getRefundId().startsWith("alipay_refund_"));
    }

    // ---------- 5. Dry-run 模式 healthCheck 返回 UP ----------

    @Test
    @DisplayName("dry-run healthCheck: enabled + 无 key -> UP")
    void dryRun_healthCheck_up() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "enabled", true);
        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertEquals(0L, h.getLatencyMs());
    }

    // ---------- 6. enabled=false 时 isActive 返回 false ----------

    @Test
    @DisplayName("isActive: enabled=false -> false")
    void isActive_disabled() {
        AlipayConnector c = newConnector();
        assertFalse(c.isActive());
    }

    // ---------- 7. enabled=false 时 healthCheck 返回 DOWN ----------

    @Test
    @DisplayName("healthCheck: enabled=false -> DOWN")
    void healthCheck_disabled() {
        AlipayConnector c = newConnector();
        ConnectorHealth h = c.healthCheck();
        assertFalse(h.isHealthy());
    }

    // ---------- 8. getId 返回 "alipay" ----------

    @Test
    @DisplayName("getId: 返回 'alipay'")
    void getId() {
        AlipayConnector c = newConnector();
        assertEquals("alipay", c.getId());
    }

    // ---------- 9. getType 返回 "http_psp" ----------

    @Test
    @DisplayName("getType: 返回 'http_psp'")
    void getType() {
        AlipayConnector c = newConnector();
        assertEquals("http_psp", c.getType());
    }

    // ---------- 10. getDisplayName 返回正确名称 ----------

    @Test
    @DisplayName("getDisplayName: 返回 'Alipay (当面付/网页支付)'")
    void getDisplayName() {
        AlipayConnector c = newConnector();
        assertEquals("Alipay (当面付/网页支付)", c.getDisplayName());
    }

    // ---------- 11. feeBasisPoints 返回 38 ----------

    @Test
    @DisplayName("feeBasisPoints: 返回 38")
    void feeBasisPoints() {
        AlipayConnector c = newConnector();
        assertEquals(38, c.feeBasisPoints());
    }

    // ---------- 12. supportedCurrencies 返回 Set.of("CNY") ----------

    @Test
    @DisplayName("supportedCurrencies: 返回 Set.of('CNY')")
    void supportedCurrencies() {
        AlipayConnector c = newConnector();
        assertEquals(Set.of("CNY"), c.supportedCurrencies());
    }

    // ---------- 13. getFinalityPolicy 返回 PspFinalityPolicy 实例 ----------

    @Test
    @DisplayName("getFinalityPolicy: 返回 PspFinalityPolicy 实例")
    void getFinalityPolicy() {
        AlipayConnector c = newConnector();
        FinalityPolicy policy = c.getFinalityPolicy();
        assertNotNull(policy);
        assertInstanceOf(PspFinalityPolicy.class, policy);
    }

    // ---------- 14. 状态映射：TRADE_SUCCESS → SUCCEEDED ----------

    @Test
    @DisplayName("状态映射: TRADE_SUCCESS -> SUCCEEDED")
    void mapStatus_tradeSuccess() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "merchantPrivateKey", "test_key");
        setField(c, "appId", "test_app");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_no", "t1", "trade_status", "TRADE_SUCCESS", "qr_code", "https://qr.example.com"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.SUCCEEDED, r.getStatus());
        assertEquals("t1", r.getConnectorPaymentId());
        assertEquals("https://qr.example.com", r.getRedirectUrl());
    }

    // ---------- 15. 状态映射：WAIT_BUYER_PAY → PROCESSING ----------

    @Test
    @DisplayName("状态映射: WAIT_BUYER_PAY -> PROCESSING")
    void mapStatus_waitBuyerPay() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "merchantPrivateKey", "test_key");
        setField(c, "appId", "test_app");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_no", "t2", "trade_status", "WAIT_BUYER_PAY"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.PROCESSING, r.getStatus());
    }

    // ---------- 16. 状态映射：TRADE_CLOSED → CANCELLED ----------

    @Test
    @DisplayName("状态映射: TRADE_CLOSED -> CANCELLED")
    void mapStatus_tradeClosed() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "merchantPrivateKey", "test_key");
        setField(c, "appId", "test_app");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_no", "t3", "trade_status", "TRADE_CLOSED"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.CANCELLED, r.getStatus());
    }

    // ---------- 17. 状态映射：TRADE_REFUND → REFUNDED ----------

    @Test
    @DisplayName("状态映射: TRADE_REFUND -> REFUNDED")
    void mapStatus_tradeRefund() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "merchantPrivateKey", "test_key");
        setField(c, "appId", "test_app");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_no", "t4", "trade_status", "TRADE_REFUND"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.REFUNDED, r.getStatus());
    }

    // ---------- 18. 状态映射：未知状态 → FAILED (fail-closed) ----------

    @Test
    @DisplayName("状态映射: 未知状态 -> FAILED (fail-closed)")
    void mapStatus_unknown() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "merchantPrivateKey", "test_key");
        setField(c, "appId", "test_app");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_no", "t5", "trade_status", "UNKNOWN_STATUS"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.FAILED, r.getStatus());
    }

    // ---------- 补充：TRADE_FINISHED → SUCCEEDED ----------

    @Test
    @DisplayName("状态映射: TRADE_FINISHED -> SUCCEEDED")
    void mapStatus_tradeFinished() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "merchantPrivateKey", "test_key");
        setField(c, "appId", "test_app");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_no", "t6", "trade_status", "TRADE_FINISHED"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.SUCCEEDED, r.getStatus());
    }

    // ---------- 补充：real queryPayment 返回状态 ----------

    @Test
    @DisplayName("real queryPayment: TRADE_SUCCESS -> SUCCEEDED 并更新 localState")
    void real_queryPayment_success() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "merchantPrivateKey", "test_key");
        setField(c, "appId", "test_app");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_status", "TRADE_SUCCESS"),
                        HttpStatus.OK));

        PaymentStatus s = c.queryPayment("t1");
        assertEquals(PaymentStatus.SUCCEEDED, s);
    }

    // ---------- 补充：real queryPayment 异常时回退 localState ----------

    @Test
    @DisplayName("real queryPayment: 异常 -> 回退 localState FAILED")
    void real_queryPayment_exception() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "merchantPrivateKey", "test_key");
        setField(c, "appId", "test_app");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        PaymentStatus s = c.queryPayment("unknown_id");
        assertEquals(PaymentStatus.FAILED, s);
    }

    // ---------- 补充：real refund fund_change=Y → ok ----------

    @Test
    @DisplayName("real refund: fund_change=Y -> ok")
    void real_refund_success() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "merchantPrivateKey", "test_key");
        setField(c, "appId", "test_app");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("fund_change", "Y"),
                        HttpStatus.OK));

        ConnectorRefundResult r = c.refund("t1", 1000L);
        assertTrue(r.isSuccess());
    }

    // ---------- 补充：real refund fund_change=N → fail ----------

    @Test
    @DisplayName("real refund: fund_change=N -> fail")
    void real_refund_fundChangeN() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "merchantPrivateKey", "test_key");
        setField(c, "appId", "test_app");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("fund_change", "N"),
                        HttpStatus.OK));

        ConnectorRefundResult r = c.refund("t1", 1000L);
        assertFalse(r.isSuccess());
    }

    // ---------- 补充：real refund 异常 → fail ----------

    @Test
    @DisplayName("real refund: 异常 -> fail")
    void real_refund_exception() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "merchantPrivateKey", "test_key");
        setField(c, "appId", "test_app");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        ConnectorRefundResult r = c.refund("t1", 1000L);
        assertFalse(r.isSuccess());
    }

    // ---------- 补充：real healthCheck 成功 → UP ----------

    @Test
    @DisplayName("healthCheck: enabled + key + 请求成功 -> UP")
    void healthCheck_real_up() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "enabled", true);
        setField(c, "merchantPrivateKey", "test_key");
        setField(c, "appId", "test_app");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK));

        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertTrue(h.getLatencyMs() >= 0);
    }

    // ---------- 补充：real healthCheck 异常 → DOWN ----------

    @Test
    @DisplayName("healthCheck: enabled + key + 异常 -> DOWN")
    void healthCheck_real_down() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "enabled", true);
        setField(c, "merchantPrivateKey", "test_key");
        setField(c, "appId", "test_app");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        ConnectorHealth h = c.healthCheck();
        assertFalse(h.isHealthy());
    }

    // ---------- 补充：real createPayment 空 body → fail ----------

    @Test
    @DisplayName("real createPayment: 空 body -> fail")
    void real_createPayment_emptyBody() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "merchantPrivateKey", "test_key");
        setField(c, "appId", "test_app");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>((Map) null, HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertFalse(r.isSuccess());
    }

    // ---------- 补充：real createPayment 异常 → fail ----------

    @Test
    @DisplayName("real createPayment: 异常 -> fail")
    void real_createPayment_exception() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "merchantPrivateKey", "test_key");
        setField(c, "appId", "test_app");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertFalse(r.isSuccess());
        assertNotNull(r.getErrorMessage());
    }
}