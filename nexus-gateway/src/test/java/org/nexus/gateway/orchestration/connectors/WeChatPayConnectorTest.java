package org.nexus.gateway.orchestration.connectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connector.ConnectorHealth;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.ConnectorRefundResult;
import org.nexus.gateway.orchestration.connector.PaymentStatus;
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
 * {@link WeChatPayConnector} 单元测试：覆盖 dry-run（无 apiKey）与 real（有 apiKey + mock
 * RestTemplate）两条路径，包含支付创建、查询、退款、健康检查与微信支付状态映射。
 */
class WeChatPayConnectorTest {

    private WeChatPayConnector newConnector() {
        return new WeChatPayConnector();
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private RestTemplate injectRestTemplate(WeChatPayConnector c) throws Exception {
        RestTemplate rt = mock(RestTemplate.class);
        setField(c, "restTemplate", rt);
        return rt;
    }

    private ConnectorPaymentRequest sampleRequest() {
        return new ConnectorPaymentRequest("pay_1", 5000L, "CNY", "test");
    }

    // ---------- metadata ----------

    @Test
    @DisplayName("getId 返回 \"wechat\"")
    void getId() {
        WeChatPayConnector c = newConnector();
        assertEquals("wechat", c.getId());
    }

    @Test
    @DisplayName("getType 返回 \"http_psp\"")
    void getType() {
        WeChatPayConnector c = newConnector();
        assertEquals("http_psp", c.getType());
    }

    @Test
    @DisplayName("getDisplayName 返回 \"WeChat Pay (扫码/JSAPI)\"")
    void getDisplayName() {
        WeChatPayConnector c = newConnector();
        assertEquals("WeChat Pay (扫码/JSAPI)", c.getDisplayName());
    }

    @Test
    @DisplayName("feeBasisPoints 返回 60")
    void feeBasisPoints() {
        WeChatPayConnector c = newConnector();
        assertEquals(60, c.feeBasisPoints());
    }

    @Test
    @DisplayName("supportedCurrencies 返回 Set.of(\"CNY\")")
    void supportedCurrencies() {
        WeChatPayConnector c = newConnector();
        assertEquals(Set.of("CNY"), c.supportedCurrencies());
    }

    @Test
    @DisplayName("getFinalityPolicy 返回 PspFinalityPolicy 实例")
    void getFinalityPolicy() {
        WeChatPayConnector c = newConnector();
        assertNotNull(c.getFinalityPolicy());
        assertInstanceOf(PspFinalityPolicy.class, c.getFinalityPolicy());
    }

    @Test
    @DisplayName("enabled=false 时 isActive 返回 false")
    void isActive_disabled() {
        WeChatPayConnector c = newConnector();
        assertFalse(c.isActive());
    }

    // ---------- dry-run mode (apiKey blank) ----------

    @Test
    @DisplayName("dry-run createPayment 返回成功")
    void dryRun_createPayment_success() {
        WeChatPayConnector c = newConnector();
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.SUCCEEDED, r.getStatus());
    }

    @Test
    @DisplayName("dry-run createPayment 生成正确格式的 ID（wechat_dryrun_ 前缀）")
    void dryRun_createPayment_idFormat() {
        WeChatPayConnector c = newConnector();
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.getConnectorPaymentId().startsWith("wechat_dryrun_"));
    }

    @Test
    @DisplayName("dry-run queryPayment 返回缓存状态")
    void dryRun_queryPayment_cached() {
        WeChatPayConnector c = newConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.SUCCEEDED, c.queryPayment(created.getConnectorPaymentId()));
    }

    @Test
    @DisplayName("dry-run queryPayment 未知 ID 返回 FAILED")
    void dryRun_queryPayment_unknown() {
        WeChatPayConnector c = newConnector();
        assertEquals(PaymentStatus.FAILED, c.queryPayment("unknown"));
    }

    @Test
    @DisplayName("dry-run refund 返回成功")
    void dryRun_refund_success() {
        WeChatPayConnector c = newConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        ConnectorRefundResult r = c.refund(created.getConnectorPaymentId(), 1000L);
        assertTrue(r.isSuccess());
        assertTrue(r.getRefundId().startsWith("wechat_refund_"));
    }

    @Test
    @DisplayName("dry-run healthCheck 返回 UP")
    void dryRun_healthCheck_up() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "enabled", true);
        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertEquals(0L, h.getLatencyMs());
    }

    @Test
    @DisplayName("enabled=false 时 healthCheck 返回 DOWN")
    void healthCheck_disabled() {
        WeChatPayConnector c = newConnector();
        ConnectorHealth h = c.healthCheck();
        assertFalse(h.isHealthy());
    }

    // ---------- 状态映射测试 ----------

    @Test
    @DisplayName("状态映射：SUCCESS → SUCCEEDED")
    void mapStatus_success() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "apiKey", "api_key_123");
        setField(c, "appId", "wx_test");
        setField(c, "mchId", "mch_test");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_state", "SUCCESS"),
                        HttpStatus.OK));

        PaymentStatus s = c.queryPayment("pay_1");
        assertEquals(PaymentStatus.SUCCEEDED, s);
    }

    @Test
    @DisplayName("状态映射：NOTPAY → PROCESSING")
    void mapStatus_notpay() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "apiKey", "api_key_123");
        setField(c, "appId", "wx_test");
        setField(c, "mchId", "mch_test");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_state", "NOTPAY"),
                        HttpStatus.OK));

        PaymentStatus s = c.queryPayment("pay_1");
        assertEquals(PaymentStatus.PROCESSING, s);
    }

    @Test
    @DisplayName("状态映射：CLOSED → CANCELLED")
    void mapStatus_closed() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "apiKey", "api_key_123");
        setField(c, "appId", "wx_test");
        setField(c, "mchId", "mch_test");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_state", "CLOSED"),
                        HttpStatus.OK));

        PaymentStatus s = c.queryPayment("pay_1");
        assertEquals(PaymentStatus.CANCELLED, s);
    }

    @Test
    @DisplayName("状态映射：PAYERROR → FAILED")
    void mapStatus_payerror() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "apiKey", "api_key_123");
        setField(c, "appId", "wx_test");
        setField(c, "mchId", "mch_test");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_state", "PAYERROR"),
                        HttpStatus.OK));

        PaymentStatus s = c.queryPayment("pay_1");
        assertEquals(PaymentStatus.FAILED, s);
    }

    @Test
    @DisplayName("状态映射：未知状态 → FAILED (fail-closed)")
    void mapStatus_unknown() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "apiKey", "api_key_123");
        setField(c, "appId", "wx_test");
        setField(c, "mchId", "mch_test");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_state", "WEIRD_STATUS"),
                        HttpStatus.OK));

        PaymentStatus s = c.queryPayment("pay_1");
        assertEquals(PaymentStatus.FAILED, s);
    }

    // ---------- real mode (apiKey set + mock RestTemplate) ----------

    @Test
    @DisplayName("real createPayment: 返回 code_url 设置为 redirectUrl")
    void real_createPayment_codeUrl() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "apiKey", "api_key_123");
        setField(c, "appId", "wx_test");
        setField(c, "mchId", "mch_test");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("code_url", "weixin://wxpay/bizpayurl?pr=test"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.PROCESSING, r.getStatus());
        assertEquals("weixin://wxpay/bizpayurl?pr=test", r.getRedirectUrl());
    }

    @Test
    @DisplayName("real createPayment: 空 body -> fail")
    void real_createPayment_emptyBody() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "apiKey", "api_key_123");
        setField(c, "appId", "wx_test");
        setField(c, "mchId", "mch_test");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>((Map) null, HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("real createPayment: RestTemplate 抛异常 -> fail")
    void real_createPayment_exception() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "apiKey", "api_key_123");
        setField(c, "appId", "wx_test");
        setField(c, "mchId", "mch_test");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("real queryPayment: 空 body -> 回退 localState FAILED")
    void real_queryPayment_emptyBody() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "apiKey", "api_key_123");
        setField(c, "appId", "wx_test");
        setField(c, "mchId", "mch_test");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>((Map) null, HttpStatus.OK));

        PaymentStatus s = c.queryPayment("pay_unknown");
        assertEquals(PaymentStatus.FAILED, s);
    }

    @Test
    @DisplayName("real queryPayment: RestTemplate 抛异常 -> 回退 localState FAILED")
    void real_queryPayment_exception() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "apiKey", "api_key_123");
        setField(c, "appId", "wx_test");
        setField(c, "mchId", "mch_test");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        PaymentStatus s = c.queryPayment("pay_unknown");
        assertEquals(PaymentStatus.FAILED, s);
    }

    @Test
    @DisplayName("real refund: 返回非空 body -> ok")
    void real_refund_success() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "apiKey", "api_key_123");
        setField(c, "appId", "wx_test");
        setField(c, "mchId", "mch_test");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("refund_id", "r_123"), HttpStatus.OK));

        ConnectorRefundResult r = c.refund("pay_1", 1000L);
        assertTrue(r.isSuccess());
        assertEquals("r_123", r.getRefundId());
    }

    @Test
    @DisplayName("real refund: 空 body -> fail")
    void real_refund_emptyBody() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "apiKey", "api_key_123");
        setField(c, "appId", "wx_test");
        setField(c, "mchId", "mch_test");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>((Map) null, HttpStatus.OK));

        ConnectorRefundResult r = c.refund("pay_1", 1000L);
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("real refund: RestTemplate 抛异常 -> fail")
    void real_refund_exception() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "apiKey", "api_key_123");
        setField(c, "appId", "wx_test");
        setField(c, "mchId", "mch_test");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        ConnectorRefundResult r = c.refund("pay_1", 1000L);
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("healthCheck: enabled + apiKey + postForEntity 成功 -> up")
    void healthCheck_real_up() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "enabled", true);
        setField(c, "apiKey", "api_key_123");
        setField(c, "appId", "wx_test");
        setField(c, "mchId", "mch_test");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK));

        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertTrue(h.getLatencyMs() >= 0);
    }

    @Test
    @DisplayName("healthCheck: enabled + apiKey + postForEntity 异常 -> down")
    void healthCheck_real_down() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "enabled", true);
        setField(c, "apiKey", "api_key_123");
        setField(c, "appId", "wx_test");
        setField(c, "mchId", "mch_test");
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        ConnectorHealth h = c.healthCheck();
        assertFalse(h.isHealthy());
    }
}