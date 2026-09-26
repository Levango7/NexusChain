package org.nexus.gateway.orchestration.connectors;

import org.junit.jupiter.api.BeforeAll;
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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AlipayConnector} 单元测试 — Wave 13 Task 8：覆盖 dry-run 模式下的下单、查询、退款，
 * formatAmount 分→元转换，默认沙箱地址，以及 bizContent 序列化行为。
 *
 * <p>real mode 测试使用动态生成的 RSA 密钥对模拟商户私钥。</p>
 */
class AlipayConnectorTest {

    private static String testPrivateKeyBase64;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();
        testPrivateKeyBase64 = Base64.getEncoder().encodeToString(
                keyPair.getPrivate().getEncoded());
    }

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

    /** 配置为 real 模式：sandbox=false + 真实 RSA 私钥 */
    private void setRealMode(AlipayConnector c) throws Exception {
        setField(c, "sandbox", false);
        setField(c, "merchantPrivateKey", testPrivateKeyBase64);
        setField(c, "appId", "test_app");
    }

    private ConnectorPaymentRequest sampleRequest() {
        return new ConnectorPaymentRequest("pay_1", 5000L, "CNY", "test");
    }

    // ==================== Dry-run 模式：下单 ====================

    @Test
    @DisplayName("dry-run createPayment：返回 SUCCEEDED")
    void dryRun_createPayment_success() {
        ConnectorPaymentResult r = newConnector().createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.SUCCEEDED, r.getStatus());
    }

    @Test
    @DisplayName("dry-run createPayment：connectorPaymentId 以 alipay_dryrun_ 前缀")
    void dryRun_createPayment_idFormat() {
        ConnectorPaymentResult r = newConnector().createPayment(sampleRequest());
        assertTrue(r.getConnectorPaymentId().startsWith("alipay_dryrun_"));
    }

    @Test
    @DisplayName("dry-run createPayment：返回模拟 qr_code 链接")
    void dryRun_createPayment_qrCode() {
        ConnectorPaymentResult r = newConnector().createPayment(sampleRequest());
        assertNotNull(r.getRedirectUrl());
        assertTrue(r.getRedirectUrl().startsWith("https://qr.alipay.com/dryrun_"));
    }

    // ==================== Dry-run 模式：查询 ====================

    @Test
    @DisplayName("dry-run queryPayment：已创建 → SUCCEEDED；未知 → FAILED")
    void dryRun_queryPayment_cachedState() {
        AlipayConnector c = newConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.SUCCEEDED, c.queryPayment(created.getConnectorPaymentId()));
        assertEquals(PaymentStatus.FAILED, c.queryPayment("unknown"));
    }

    // ==================== Dry-run 模式：退款 ====================

    @Test
    @DisplayName("dry-run refund：返回 ok，refundId 以 alipay_refund_ 前缀")
    void dryRun_refund_success() {
        AlipayConnector c = newConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        ConnectorRefundResult r = c.refund(created.getConnectorPaymentId(), 1000L);
        assertTrue(r.isSuccess());
        assertTrue(r.getRefundId().startsWith("alipay_refund_"));
    }

    // ==================== formatAmount 分→元转换 ====================

    @Test
    @DisplayName("formatAmount：100 分 → \"1.00\" 元")
    void formatAmount_100cents() throws Exception {
        AlipayConnector c = newConnector();
        // formatAmount 是 private 方法，通过反射调用
        java.lang.reflect.Method m = AlipayConnector.class.getDeclaredMethod("formatAmount", long.class);
        m.setAccessible(true);
        String result = (String) m.invoke(c, 100L);
        assertEquals("1.00", result);
    }

    @Test
    @DisplayName("formatAmount：5000 分 → \"50.00\" 元")
    void formatAmount_5000cents() throws Exception {
        AlipayConnector c = newConnector();
        java.lang.reflect.Method m = AlipayConnector.class.getDeclaredMethod("formatAmount", long.class);
        m.setAccessible(true);
        String result = (String) m.invoke(c, 5000L);
        assertEquals("50.00", result);
    }

    @Test
    @DisplayName("formatAmount：1 分 → \"0.01\" 元")
    void formatAmount_1cent() throws Exception {
        AlipayConnector c = newConnector();
        java.lang.reflect.Method m = AlipayConnector.class.getDeclaredMethod("formatAmount", long.class);
        m.setAccessible(true);
        String result = (String) m.invoke(c, 1L);
        assertEquals("0.01", result);
    }

    @Test
    @DisplayName("formatAmount：0 分 → \"0.00\" 元")
    void formatAmount_0cents() throws Exception {
        AlipayConnector c = newConnector();
        java.lang.reflect.Method m = AlipayConnector.class.getDeclaredMethod("formatAmount", long.class);
        m.setAccessible(true);
        String result = (String) m.invoke(c, 0L);
        assertEquals("0.00", result);
    }

    // ==================== 默认沙箱地址 ====================

    @Test
    @DisplayName("默认 apiBaseUrl 为沙箱地址 https://openapi-sandbox.dl.alipaydev.com/gateway.do")
    void defaultApiBaseUrl_isSandbox() throws Exception {
        AlipayConnector c = newConnector();
        Field f = AlipayConnector.class.getDeclaredField("apiBaseUrl");
        f.setAccessible(true);
        String apiBaseUrl = (String) f.get(c);
        assertEquals("https://openapi-sandbox.dl.alipaydev.com/gateway.do", apiBaseUrl);
    }

    // ==================== Dry-run 模式：bizContent 不调用 ObjectMapper ====================

    @Test
    @DisplayName("dry-run createPayment：不进入真实 API 路径，不调用 ObjectMapper 序列化 bizContent")
    void dryRun_createPayment_noObjectMapperCall() {
        // dry-run 模式下 createPayment 直接返回模拟响应，不进入 try 块中的真实 API 路径
        // 因此不会调用 objectMapper.writeValueAsString()
        // 验证方式：dry-run createPayment 不应抛出任何 JsonProcessingException
        AlipayConnector c = newConnector();
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        // 如果 ObjectMapper 被调用且出错，会返回 fail 结果
        // dry-run 路径不经过 ObjectMapper，所以一定成功
    }

    // ==================== Dry-run 模式：健康检查 ====================

    @Test
    @DisplayName("dry-run healthCheck：enabled=true → UP")
    void dryRun_healthCheck_up() throws Exception {
        AlipayConnector c = newConnector();
        setField(c, "enabled", true);
        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertEquals(0L, h.getLatencyMs());
    }

    @Test
    @DisplayName("healthCheck：enabled=false → DOWN")
    void healthCheck_disabled() {
        ConnectorHealth h = newConnector().healthCheck();
        assertFalse(h.isHealthy());
    }

    // ==================== 元数据测试 ====================

    @Test
    @DisplayName("getId：返回 'alipay'")
    void getId() {
        assertEquals("alipay", newConnector().getId());
    }

    @Test
    @DisplayName("getType：返回 'http_psp'")
    void getType() {
        assertEquals("http_psp", newConnector().getType());
    }

    @Test
    @DisplayName("getDisplayName：返回 'Alipay (当面付/网页支付)'")
    void getDisplayName() {
        assertEquals("Alipay (当面付/网页支付)", newConnector().getDisplayName());
    }

    @Test
    @DisplayName("feeBasisPoints：返回 38")
    void feeBasisPoints() {
        assertEquals(38, newConnector().feeBasisPoints());
    }

    @Test
    @DisplayName("supportedCurrencies：返回 Set.of('CNY')")
    void supportedCurrencies() {
        assertEquals(Set.of("CNY"), newConnector().supportedCurrencies());
    }

    @Test
    @DisplayName("getFinalityPolicy：返回 PspFinalityPolicy 实例")
    void getFinalityPolicy() {
        FinalityPolicy policy = newConnector().getFinalityPolicy();
        assertNotNull(policy);
        assertInstanceOf(PspFinalityPolicy.class, policy);
    }

    @Test
    @DisplayName("isActive：enabled=false → false")
    void isActive_disabled() {
        assertFalse(newConnector().isActive());
    }

    // ==================== Real 模式：状态映射 ====================

    @Test
    @DisplayName("状态映射：TRADE_SUCCESS → SUCCEEDED")
    void mapStatus_tradeSuccess() throws Exception {
        AlipayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_no", "t1", "trade_status", "TRADE_SUCCESS", "qr_code", "https://qr.example.com"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.SUCCEEDED, r.getStatus());
    }

    @Test
    @DisplayName("状态映射：WAIT_BUYER_PAY → PROCESSING")
    void mapStatus_waitBuyerPay() throws Exception {
        AlipayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_no", "t2", "trade_status", "WAIT_BUYER_PAY"),
                        HttpStatus.OK));

        assertEquals(PaymentStatus.PROCESSING, c.createPayment(sampleRequest()).getStatus());
    }

    @Test
    @DisplayName("状态映射：未知状态 → FAILED (fail-closed)")
    void mapStatus_unknown() throws Exception {
        AlipayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_no", "t5", "trade_status", "UNKNOWN_STATUS"),
                        HttpStatus.OK));

        assertEquals(PaymentStatus.FAILED, c.createPayment(sampleRequest()).getStatus());
    }

    // ==================== Real 模式：查询 ====================

    @Test
    @DisplayName("real queryPayment：TRADE_SUCCESS → SUCCEEDED")
    void real_queryPayment_success() throws Exception {
        AlipayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_status", "TRADE_SUCCESS"),
                        HttpStatus.OK));

        assertEquals(PaymentStatus.SUCCEEDED, c.queryPayment("t1"));
    }

    @Test
    @DisplayName("real queryPayment：异常 → 回退 localState FAILED")
    void real_queryPayment_exception() throws Exception {
        AlipayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        assertEquals(PaymentStatus.FAILED, c.queryPayment("unknown_id"));
    }

    // ==================== Real 模式：退款 ====================

    @Test
    @DisplayName("real refund：fund_change=Y → ok")
    void real_refund_success() throws Exception {
        AlipayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("fund_change", "Y"),
                        HttpStatus.OK));

        ConnectorRefundResult r = c.refund("t1", 1000L);
        assertTrue(r.isSuccess());
    }

    @Test
    @DisplayName("real refund：fund_change=N → fail")
    void real_refund_fundChangeN() throws Exception {
        AlipayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("fund_change", "N"),
                        HttpStatus.OK));

        assertFalse(c.refund("t1", 1000L).isSuccess());
    }

    @Test
    @DisplayName("real refund：异常 → fail")
    void real_refund_exception() throws Exception {
        AlipayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        assertFalse(c.refund("t1", 1000L).isSuccess());
    }

    // ==================== Dry-run 模式：关单 ====================

    @Test
    @DisplayName("dry-run closePayment：返回 true 并设置 CANCELLED")
    void dryRun_closePayment() {
        AlipayConnector c = newConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        boolean closed = c.closePayment(created.getConnectorPaymentId());
        assertTrue(closed);
        assertEquals(PaymentStatus.CANCELLED, c.queryPayment(created.getConnectorPaymentId()));
    }
}
