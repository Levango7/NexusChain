package org.nexus.gateway.sandbox.e2e;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.ConnectorRefundResult;
import org.nexus.gateway.orchestration.connector.PaymentStatus;
import org.nexus.gateway.orchestration.connectors.AlipayConnector;
import org.nexus.gateway.orchestration.connectors.WeChatPayConnector;
import org.nexus.gateway.sandbox.mock.AlipayMockServer;
import org.nexus.gateway.sandbox.mock.WeChatMockServer;
import org.nexus.gateway.sandbox.util.TestKeyPairGenerator;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 渠道错误处理端到端（E2E）测试 — 使用 {@link WeChatMockServer} 和 {@link AlipayMockServer}
 * （WireMock）模拟支付渠道错误场景，验证 {@link WeChatPayConnector} 和 {@link AlipayConnector}
 * 的 <b>fail-closed</b> 行为：在渠道返回错误、超时或异常状态时，connector 必须返回 fail/FAILED，
 * 不得静默回退到不安全的状态。
 *
 * <p>测试场景：
 * <ul>
 *   <li>微信支付统一下单返回 HTTP 500 → connector 返回 fail（不抛异常）</li>
 *   <li>支付宝 precreate 返回 HTTP 500 → connector 返回 fail（不抛异常）</li>
 *   <li>微信支付查询 API 超时 → connector 返回缓存状态（不抛异常，fail-closed 到安全状态）</li>
 *   <li>支付宝退款 fund_change=N → connector 返回 fail（资金未变动视为失败）</li>
 * </ul>
 *
 * <p><b>fail-closed 策略说明</b>：当渠道 API 出现错误或异常时，connector 不得假设操作成功，
 * 必须返回明确的失败结果或安全的缓存状态。这与 OIDC 回退 HMAC 的 fail-closed 原则一致
 * （经验来源：2026-09-10-java-springboot-oidc-fallback-hmac-fail-closed-fix）。</p>
 *
 * <p>测试策略：
 * <ul>
 *   <li>使用 {@link TestKeyPairGenerator} 动态生成 RSA-2048 密钥对</li>
 *   <li>通过反射注入 connector 的 @Value 字段，配置为 real mode（sandbox=false）</li>
 *   <li>通过反射获取 MockServer 的 WireMockServer 实例，注册高优先级错误 stub 覆盖默认 stub</li>
 *   <li>微信查询超时场景：设置 RestTemplate readTimeout=500ms + WireMock fixedDelay=2000ms</li>
 *   <li>支付宝使用自定义 RestTemplate 解包嵌套响应（与 {@link AlipayE2ETest} 一致）</li>
 * </ul>
 *
 * <p>经验来源：
 * <ul>
 *   <li>2026-09-10-java-springboot-oidc-fallback-hmac-fail-closed-fix（fail-closed 原则）</li>
 *   <li>2026-09-26-payment-connector-test-sandbox-rsa-adaptation（sandbox 配置、RSA 密钥、Mock 适配）</li>
 *   <li>2026-09-21-exception-type-change-javadoc-test-displayname-sync（@DisplayName 与场景同步）</li>
 * </ul>
 */
class ChannelErrorHandlingE2ETest {

    private WeChatMockServer wechatMockServer;
    private AlipayMockServer alipayMockServer;
    private WeChatPayConnector wechatConnector;
    private AlipayConnector alipayConnector;
    private TestKeyPairGenerator keyPairGenerator;

    @BeforeEach
    void setup() throws Exception {
        // 1. 启动 WireMock 模拟微信支付 V3 API 和支付宝统一网关 API
        wechatMockServer = new WeChatMockServer();
        alipayMockServer = new AlipayMockServer();
        wechatMockServer.start();
        alipayMockServer.start();

        // 2. 动态生成 RSA-2048 密钥对，模拟商户私钥
        keyPairGenerator = new TestKeyPairGenerator();

        // 3. 配置 WeChatPayConnector 为 real mode（sandbox=false）
        wechatConnector = new WeChatPayConnector();
        setField(wechatConnector, "sandbox", false);
        setField(wechatConnector, "apiBase", wechatMockServer.getBaseUrl());
        setField(wechatConnector, "merchantPrivateKey", keyPairGenerator.getPrivateKeyBase64());
        setField(wechatConnector, "apiV3Key", "test_api_v3_key_32_bytes_long_!!!");
        setField(wechatConnector, "certSerialNo", "mock_cert_serial_001");
        setField(wechatConnector, "appId", "wx_test_app");
        setField(wechatConnector, "mchId", "mch_test_001");
        setField(wechatConnector, "apiKey", "test_api_key");
        setField(wechatConnector, "enabled", true);

        // 4. 注入带超时设置的 RestTemplate（用于查询超时测试场景）
        SimpleClientHttpRequestFactory wechatFactory = new SimpleClientHttpRequestFactory();
        wechatFactory.setConnectTimeout(2000);
        wechatFactory.setReadTimeout(500); // 500ms 读取超时，用于触发查询超时
        setField(wechatConnector, "restTemplate", new RestTemplate(wechatFactory));

        // 5. 配置 AlipayConnector 为 real mode（sandbox=false）
        alipayConnector = new AlipayConnector();
        setField(alipayConnector, "sandbox", false);
        setField(alipayConnector, "enabled", true);
        setField(alipayConnector, "appId", "test_app_id");
        setField(alipayConnector, "merchantPrivateKey", keyPairGenerator.getPrivateKeyBase64());
        setField(alipayConnector, "alipayPublicKey", keyPairGenerator.getPublicKeyBase64());
        setField(alipayConnector, "apiBaseUrl", alipayMockServer.getBaseUrl() + "/gateway.do");

        // 6. 注入自定义 RestTemplate（解包支付宝嵌套响应），与 AlipayE2ETest 一致
        setField(alipayConnector, "restTemplate", createAlipayRestTemplate());
    }

    @AfterEach
    void teardown() {
        if (wechatMockServer != null) {
            wechatMockServer.stop();
        }
        if (alipayMockServer != null) {
            alipayMockServer.stop();
        }
    }

    // ==================== 测试场景 ====================

    @Test
    @DisplayName("微信支付 E2E：统一下单返回错误码 → fail")
    void testWeChatCreatePaymentError() throws Exception {
        // 覆盖默认 stub：统一下单返回 HTTP 500，模拟微信支付服务端错误
        // WireMock atPriority(1) 确保此 stub 优先于默认 stub（默认 priority=5）
        WireMockServer wm = getWireMockServer(wechatMockServer);
        wm.stubFor(post(urlPathMatching("/v3/pay/transactions/native"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"INTERNAL_ERROR\",\"message\":\"模拟微信支付服务端错误\"}")));

        ConnectorPaymentRequest request = new ConnectorPaymentRequest(
                "err_wechat_order_001", 5000L, "CNY", "错误场景-微信统一下单");

        ConnectorPaymentResult result = wechatConnector.createPayment(request);

        // fail-closed 验证：渠道返回错误时，connector 必须返回 fail，不得静默成功
        assertFalse(result.isSuccess(),
                "微信统一下单返回 HTTP 500 时应 fail（fail-closed）");
        assertEquals(PaymentStatus.FAILED, result.getStatus(),
                "状态应映射为 FAILED");
        assertNotNull(result.getErrorMessage(),
                "应包含错误信息以便排查");
        assertTrue(result.getErrorMessage().contains("WeChat Pay"),
                "错误信息应标识来源渠道");
    }

    @Test
    @DisplayName("支付宝 E2E：precreate 返回错误码 → fail")
    void testAlipayPrecreateError() throws Exception {
        // 覆盖默认 stub：precreate 返回 HTTP 500，模拟支付宝网关错误
        WireMockServer wm = getWireMockServer(alipayMockServer);
        wm.stubFor(post(urlPathMatching("/gateway.do"))
                .withRequestBody(containing("method=alipay.trade.precreate"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"alipay_trade_precreate_response\":{" +
                                "\"code\":\"20000\"," +
                                "\"msg\":\"Service Not Available\"" +
                                "}}")));

        ConnectorPaymentRequest request = new ConnectorPaymentRequest(
                "err_alipay_order_001", 5000L, "CNY", "错误场景-支付宝precreate");

        ConnectorPaymentResult result = alipayConnector.createPayment(request);

        // fail-closed 验证：渠道返回错误时，connector 必须返回 fail
        assertFalse(result.isSuccess(),
                "支付宝 precreate 返回 HTTP 500 时应 fail（fail-closed）");
        assertEquals(PaymentStatus.FAILED, result.getStatus(),
                "状态应映射为 FAILED");
        assertNotNull(result.getErrorMessage(),
                "应包含错误信息以便排查");
        assertTrue(result.getErrorMessage().contains("Alipay"),
                "错误信息应标识来源渠道");
    }

    @Test
    @DisplayName("微信支付 E2E：查询 API 超时 → 返回缓存状态")
    void testWeChatQueryTimeout() throws Exception {
        // 先 createPayment 成功，缓存 PROCESSING 状态到 localState
        ConnectorPaymentRequest request = new ConnectorPaymentRequest(
                "timeout_wechat_order_001", 3000L, "CNY", "超时场景-微信查询");
        ConnectorPaymentResult createResult = wechatConnector.createPayment(request);
        assertTrue(createResult.isSuccess(),
                "统一下单应成功（默认 stub 返回 200）");
        assertEquals(PaymentStatus.PROCESSING, createResult.getStatus(),
                "real mode 下单后状态应为 PROCESSING");

        // 覆盖查询 stub：添加 2000ms 延迟，超过 RestTemplate 的 500ms readTimeout
        // → RestTemplate 抛 SocketTimeoutException → connector catch → 返回缓存状态
        WireMockServer wm = getWireMockServer(wechatMockServer);
        wm.stubFor(get(urlPathMatching("/v3/pay/transactions/out-trade-no/[^/]+"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(2000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"trade_state\":\"SUCCESS\",\"transaction_id\":\"wx_tx_mock_001\"}")));

        // 查询订单 — RestTemplate 超时 → connector catch 异常 → 返回 localState 缓存
        // fail-closed：不抛异常，返回缓存的安全状态（PROCESSING），而非假设成功
        PaymentStatus queryStatus = wechatConnector.queryPayment("timeout_wechat_order_001");

        assertEquals(PaymentStatus.PROCESSING, queryStatus,
                "查询超时应返回缓存状态 PROCESSING（fail-closed：不抛异常，不假设成功）");
    }

    @Test
    @DisplayName("支付宝 E2E：退款 fund_change=N → fail")
    void testAlipayRefundFundChangeN() throws Exception {
        // 先 createPayment 成功
        ConnectorPaymentRequest request = new ConnectorPaymentRequest(
                "refund_fail_alipay_order_001", 10000L, "CNY", "退款失败场景-支付宝");
        ConnectorPaymentResult createResult = alipayConnector.createPayment(request);
        assertTrue(createResult.isSuccess(),
                "precreate 应成功（默认 stub 返回 200）");

        // 覆盖退款 stub：返回 fund_change=N（资金未发生变动）
        // 支付宝退款接口 fund_change=N 表示退款未实际执行，connector 应视为失败
        WireMockServer wm = getWireMockServer(alipayMockServer);
        wm.stubFor(post(urlPathMatching("/gateway.do"))
                .withRequestBody(containing("method=alipay.trade.refund"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"alipay_trade_refund_response\":{" +
                                "\"code\":\"10000\"," +
                                "\"msg\":\"Success\"," +
                                "\"fund_change\":\"N\"," +
                                "\"trade_no\":\"alipay_tx_mock_001\"" +
                                "}}")));

        ConnectorRefundResult refundResult = alipayConnector.refund(
                "refund_fail_alipay_order_001", 10000L);

        // fail-closed 验证：fund_change=N 表示资金未变动，退款应视为失败
        assertFalse(refundResult.isSuccess(),
                "fund_change=N 时退款应 fail（资金未变动，不得视为成功）");
        assertNotNull(refundResult.getErrorMessage(),
                "应包含错误信息以便排查");
        assertTrue(refundResult.getErrorMessage().contains("fund_change=N"),
                "错误信息应包含 fund_change=N 以标识失败原因");
    }

    // ==================== 辅助方法 ====================

    /**
     * 通过反射设置 connector 的 private 字段。
     * Connector 使用 @Value 注解注入配置，在非 Spring 环境下需手动注入。
     */
    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * 通过反射获取 MockServer 内部的 WireMockServer 实例，
     * 用于在测试中注册高优先级 stub 覆盖默认 stub。
     */
    private WireMockServer getWireMockServer(Object mockServer) throws Exception {
        Field f = mockServer.getClass().getDeclaredField("wireMockServer");
        f.setAccessible(true);
        return (WireMockServer) f.get(mockServer);
    }

    /**
     * 创建自定义 RestTemplate — 解包支付宝统一网关的嵌套响应。
     *
     * <p>支付宝统一网关返回格式为 {@code {"alipay_trade_precreate_response": {...}}}，
     * 外层 key 为 {@code alipay_trade_<method>_response}，内层为实际业务字段。
     * 真实支付宝 SDK 会自动解包外层，本方法通过覆盖 {@link RestTemplate#postForEntity}
     * 模拟此解包行为，使 {@link AlipayConnector} 能直接读取内层字段。</p>
     *
     * <p>与 {@link AlipayE2ETest#createAlipayRestTemplate()} 实现一致。</p>
     *
     * @return 解包嵌套响应的 RestTemplate 实例
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private RestTemplate createAlipayRestTemplate() {
        return new RestTemplate() {
            @Override
            public <T> ResponseEntity<T> postForEntity(String url, Object request,
                                                        Class<T> responseType, Object... uriVariables) {
                ResponseEntity<Map> rawResponse = super.postForEntity(url, request, Map.class);
                if (rawResponse.getBody() == null) {
                    return new ResponseEntity<>(null, rawResponse.getHeaders(), rawResponse.getStatusCode());
                }

                Map<String, Object> body = rawResponse.getBody();
                for (Map.Entry<String, Object> entry : body.entrySet()) {
                    if (entry.getKey().startsWith("alipay_trade_") && entry.getValue() instanceof Map) {
                        Map<String, Object> unwrapped = (Map<String, Object>) entry.getValue();
                        return new ResponseEntity<>((T) unwrapped, rawResponse.getHeaders(),
                                rawResponse.getStatusCode());
                    }
                }

                return (ResponseEntity<T>) rawResponse;
            }
        };
    }
}