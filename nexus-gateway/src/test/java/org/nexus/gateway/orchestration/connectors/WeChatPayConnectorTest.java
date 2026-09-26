package org.nexus.gateway.orchestration.connectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connector.ConnectorHealth;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.ConnectorRefundResult;
import org.nexus.gateway.orchestration.connector.PaymentStatus;
import org.nexus.gateway.orchestration.settlement.PspFinalityPolicy;
import org.springframework.http.*;
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
 * {@link WeChatPayConnector} 单元测试 — Wave 13 Task 8：覆盖 dry-run 模式下的下单、查询、退款、
 * 健康检查，以及 RSA-SHA256 签名验证（buildAuthorization 使用商户私钥）。
 *
 * <p>测试策略：使用无参构造器创建 Connector（默认 sandbox=true，dry-run 模式），
 * 通过反射注入字段模拟不同配置。real mode 测试使用动态生成的 RSA 密钥对。</p>
 */
class WeChatPayConnectorTest {

    private String testPrivateKeyBase64;

    @BeforeEach
    void generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();
        testPrivateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

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

    /** 配置为 real 模式：sandbox=false + 商户私钥 + apiV3Key */
    private void setRealMode(WeChatPayConnector c) throws Exception {
        setField(c, "sandbox", false);
        setField(c, "apiV3Key", "test_api_v3_key");
        setField(c, "merchantPrivateKey", testPrivateKeyBase64);
        setField(c, "apiKey", "api_key_123");
        setField(c, "appId", "wx_test");
        setField(c, "mchId", "mch_test");
        setField(c, "certSerialNo", "serial_test");
    }

    private ConnectorPaymentRequest sampleRequest() {
        return new ConnectorPaymentRequest("pay_1", 5000L, "CNY", "test");
    }

    // ==================== 元数据测试 ====================

    @Test
    @DisplayName("getId 返回 \"wechat\"")
    void getId() {
        assertEquals("wechat", newConnector().getId());
    }

    @Test
    @DisplayName("getType 返回 \"http_psp\"")
    void getType() {
        assertEquals("http_psp", newConnector().getType());
    }

    @Test
    @DisplayName("getDisplayName 返回 \"WeChat Pay (扫码/JSAPI)\"")
    void getDisplayName() {
        assertEquals("WeChat Pay (扫码/JSAPI)", newConnector().getDisplayName());
    }

    @Test
    @DisplayName("feeBasisPoints 返回 60")
    void feeBasisPoints() {
        assertEquals(60, newConnector().feeBasisPoints());
    }

    @Test
    @DisplayName("supportedCurrencies 返回 Set.of(\"CNY\")")
    void supportedCurrencies() {
        assertEquals(Set.of("CNY"), newConnector().supportedCurrencies());
    }

    @Test
    @DisplayName("getFinalityPolicy 返回 PspFinalityPolicy 实例")
    void getFinalityPolicy() {
        assertNotNull(newConnector().getFinalityPolicy());
        assertInstanceOf(PspFinalityPolicy.class, newConnector().getFinalityPolicy());
    }

    @Test
    @DisplayName("enabled=false 时 isActive 返回 false")
    void isActive_disabled() {
        assertFalse(newConnector().isActive());
    }

    // ==================== Dry-run 模式：下单 ====================

    @Test
    @DisplayName("dry-run createPayment：返回成功（SUCCEEDED）")
    void dryRun_createPayment_success() {
        ConnectorPaymentResult r = newConnector().createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.SUCCEEDED, r.getStatus());
    }

    @Test
    @DisplayName("dry-run createPayment：connectorPaymentId 以 wechat_dryrun_ 前缀")
    void dryRun_createPayment_idFormat() {
        ConnectorPaymentResult r = newConnector().createPayment(sampleRequest());
        assertTrue(r.getConnectorPaymentId().startsWith("wechat_dryrun_"));
    }

    @Test
    @DisplayName("dry-run createPayment：返回模拟扫码链接")
    void dryRun_createPayment_redirectUrl() {
        ConnectorPaymentResult r = newConnector().createPayment(sampleRequest());
        assertNotNull(r.getRedirectUrl());
        assertTrue(r.getRedirectUrl().startsWith("weixin://wxpay/bizpayurl?pr=dryrun_"));
    }

    // ==================== Dry-run 模式：查询 ====================

    @Test
    @DisplayName("dry-run queryPayment：已创建订单返回 SUCCEEDED")
    void dryRun_queryPayment_cached() {
        WeChatPayConnector c = newConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.SUCCEEDED, c.queryPayment(created.getConnectorPaymentId()));
    }

    @Test
    @DisplayName("dry-run queryPayment：未知 ID 返回 FAILED")
    void dryRun_queryPayment_unknown() {
        assertEquals(PaymentStatus.FAILED, newConnector().queryPayment("unknown"));
    }

    // ==================== Dry-run 模式：退款 ====================

    @Test
    @DisplayName("dry-run refund：返回成功，refundId 以 wechat_refund_ 前缀")
    void dryRun_refund_success() {
        WeChatPayConnector c = newConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        ConnectorRefundResult r = c.refund(created.getConnectorPaymentId(), 1000L);
        assertTrue(r.isSuccess());
        assertTrue(r.getRefundId().startsWith("wechat_refund_"));
    }

    // ==================== Dry-run 模式：健康检查 ====================

    @Test
    @DisplayName("dry-run healthCheck：enabled=true 时返回 UP")
    void dryRun_healthCheck_up() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "enabled", true);
        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertEquals(0L, h.getLatencyMs());
    }

    @Test
    @DisplayName("healthCheck：enabled=false 时返回 DOWN")
    void healthCheck_disabled() {
        ConnectorHealth h = newConnector().healthCheck();
        assertFalse(h.isHealthy());
    }

    // ==================== isDryRun 逻辑测试 ====================

    @Test
    @DisplayName("isDryRun：merchantPrivateKey 为空时返回 true（dry-run 模式）")
    void isDryRun_emptyPrivateKey() throws Exception {
        WeChatPayConnector c = newConnector();
        // 默认 sandbox=true，所以 isDryRun 为 true
        // 设置 sandbox=false 但 merchantPrivateKey 为空
        setField(c, "sandbox", false);
        setField(c, "apiV3Key", "test_key");
        // merchantPrivateKey 默认为空字符串
        // isDryRun() 是 private 方法，通过 createPayment 的 dry-run 行为间接验证
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertTrue(r.getConnectorPaymentId().startsWith("wechat_dryrun_"));
    }

    @Test
    @DisplayName("isDryRun：apiV3Key 为空时返回 true（dry-run 模式）")
    void isDryRun_emptyApiV3Key() throws Exception {
        WeChatPayConnector c = newConnector();
        setField(c, "sandbox", false);
        setField(c, "merchantPrivateKey", testPrivateKeyBase64);
        // apiV3Key 默认为空字符串
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertTrue(r.getConnectorPaymentId().startsWith("wechat_dryrun_"));
    }

    @Test
    @DisplayName("isDryRun：sandbox=true 时返回 true（无论密钥是否配置）")
    void isDryRun_sandboxTrue() throws Exception {
        WeChatPayConnector c = newConnector();
        // 无参构造器中 @Value 不生效，sandbox 默认为 false，需显式设置
        setField(c, "sandbox", true);
        setField(c, "merchantPrivateKey", testPrivateKeyBase64);
        setField(c, "apiV3Key", "test_key");
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertTrue(r.getConnectorPaymentId().startsWith("wechat_dryrun_"));
    }

    // ==================== buildAuthorization RSA-SHA256 签名验证 ====================

    @Test
    @DisplayName("buildAuthorization：real 模式下使用 RSA-SHA256 签名（通过 createPayment 间接验证）")
    void buildAuthorization_usesRsaSignature() throws Exception {
        WeChatPayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("code_url", "weixin://wxpay/bizpayurl?pr=test"),
                        HttpStatus.OK));

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        // real 模式下，createPayment 成功说明 buildAuthorization 未抛异常
        // 即 RSA-SHA256 签名生成成功
        assertEquals(PaymentStatus.PROCESSING, r.getStatus());
    }

    @Test
    @DisplayName("buildAuthorization：Authorization 头格式包含 WECHATPAY2-SHA256-RSA2048")
    void buildAuthorization_headerFormat() throws Exception {
        WeChatPayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenAnswer(invocation -> {
                    HttpEntity<String> entity = invocation.getArgument(1);
                    HttpHeaders headers = entity.getHeaders();
                    String auth = headers.getFirst("Authorization");
                    // 验证 Authorization 头格式
                    assertNotNull(auth);
                    assertTrue(auth.startsWith("WECHATPAY2-SHA256-RSA2048 "));
                    assertTrue(auth.contains("mchid=\"mch_test\""));
                    assertTrue(auth.contains("serial_no=\"serial_test\""));
                    assertTrue(auth.contains("signature=\""));
                    return new ResponseEntity<>(
                            Map.of("code_url", "weixin://wxpay/bizpayurl?pr=test"),
                            HttpStatus.OK);
                });

        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
    }

    // ==================== Real 模式：状态映射 ====================

    @Test
    @DisplayName("状态映射：SUCCESS → SUCCEEDED")
    void mapStatus_success() throws Exception {
        WeChatPayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_state", "SUCCESS"),
                        HttpStatus.OK));

        assertEquals(PaymentStatus.SUCCEEDED, c.queryPayment("pay_1"));
    }

    @Test
    @DisplayName("状态映射：NOTPAY → PROCESSING")
    void mapStatus_notpay() throws Exception {
        WeChatPayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_state", "NOTPAY"),
                        HttpStatus.OK));

        assertEquals(PaymentStatus.PROCESSING, c.queryPayment("pay_1"));
    }

    @Test
    @DisplayName("状态映射：CLOSED → CANCELLED")
    void mapStatus_closed() throws Exception {
        WeChatPayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_state", "CLOSED"),
                        HttpStatus.OK));

        assertEquals(PaymentStatus.CANCELLED, c.queryPayment("pay_1"));
    }

    @Test
    @DisplayName("状态映射：未知状态 → FAILED (fail-closed)")
    void mapStatus_unknown() throws Exception {
        WeChatPayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("trade_state", "WEIRD_STATUS"),
                        HttpStatus.OK));

        assertEquals(PaymentStatus.FAILED, c.queryPayment("pay_1"));
    }

    // ==================== Real 模式：下单 ====================

    @Test
    @DisplayName("real createPayment：返回 code_url 设置为 redirectUrl")
    void real_createPayment_codeUrl() throws Exception {
        WeChatPayConnector c = newConnector();
        setRealMode(c);
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
    @DisplayName("real createPayment：空 body → fail")
    void real_createPayment_emptyBody() throws Exception {
        WeChatPayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>((Map) null, HttpStatus.OK));

        assertFalse(c.createPayment(sampleRequest()).isSuccess());
    }

    @Test
    @DisplayName("real createPayment：RestTemplate 抛异常 → fail")
    void real_createPayment_exception() throws Exception {
        WeChatPayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        assertFalse(c.createPayment(sampleRequest()).isSuccess());
    }

    // ==================== Real 模式：退款 ====================

    @Test
    @DisplayName("real refund：返回 refund_id → ok")
    void real_refund_success() throws Exception {
        WeChatPayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("refund_id", "r_123", "refund_status", "SUCCESS"),
                        HttpStatus.OK));

        ConnectorRefundResult r = c.refund("pay_1", 1000L);
        assertTrue(r.isSuccess());
        assertEquals("r_123", r.getRefundId());
    }

    @Test
    @DisplayName("real refund：空 body → fail")
    void real_refund_emptyBody() throws Exception {
        WeChatPayConnector c = newConnector();
        setRealMode(c);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.postForEntity(anyString(), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>((Map) null, HttpStatus.OK));

        assertFalse(c.refund("pay_1", 1000L).isSuccess());
    }

    // ==================== Real 模式：健康检查 ====================

    @Test
    @DisplayName("healthCheck：enabled + real mode + exchange 成功 → up")
    void healthCheck_real_up() throws Exception {
        WeChatPayConnector c = newConnector();
        setRealMode(c);
        setField(c, "enabled", true);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK));

        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertTrue(h.getLatencyMs() >= 0);
    }

    @Test
    @DisplayName("healthCheck：enabled + real mode + exchange 异常 → down")
    void healthCheck_real_down() throws Exception {
        WeChatPayConnector c = newConnector();
        setRealMode(c);
        setField(c, "enabled", true);
        RestTemplate rt = injectRestTemplate(c);

        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("network error"));

        ConnectorHealth h = c.healthCheck();
        assertFalse(h.isHealthy());
    }

    // ==================== Dry-run 模式：关单 ====================

    @Test
    @DisplayName("dry-run closePayment：返回 true 并设置 CANCELLED")
    void dryRun_closePayment() {
        WeChatPayConnector c = newConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        boolean closed = c.closePayment(created.getConnectorPaymentId());
        assertTrue(closed);
        assertEquals(PaymentStatus.CANCELLED, c.queryPayment(created.getConnectorPaymentId()));
    }
}
