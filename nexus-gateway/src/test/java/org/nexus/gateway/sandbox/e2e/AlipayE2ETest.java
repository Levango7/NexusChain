package org.nexus.gateway.sandbox.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.ConnectorRefundResult;
import org.nexus.gateway.orchestration.connector.PaymentStatus;
import org.nexus.gateway.orchestration.connectors.AlipayConnector;
import org.nexus.gateway.sandbox.mock.AlipayMockServer;
import org.nexus.gateway.sandbox.util.TestKeyPairGenerator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 支付宝端到端（E2E）测试 — 使用 {@link AlipayMockServer}（WireMock）模拟支付宝统一网关 API，
 * 验证 {@link AlipayConnector} 在 real mode（sandbox=false）下的全流程：
 * 下单（precreate）→ 查询订单 → 退款。
 *
 * <p>测试策略：
 * <ul>
 *   <li>使用 {@link TestKeyPairGenerator} 动态生成 RSA-2048 密钥对作为商户私钥</li>
 *   <li>通过反射注入 connector 的 @Value 字段，配置为 real mode（sandbox=false）</li>
 *   <li>使用自定义 {@link RestTemplate}（解包支付宝嵌套响应），连接到 WireMock 模拟的支付宝网关</li>
 *   <li>验证 connector 对支付宝 API 响应的正确解析和状态映射</li>
 * </ul>
 *
 * <p><b>支付宝响应格式说明</b>：支付宝统一网关返回嵌套 JSON，如
 * {@code {"alipay_trade_precreate_response": {"qr_code": "...", "trade_no": "..."}}}。
 * 真实支付宝 SDK 会自动解包外层 {@code alipay_trade_*_response} 对象。
 * 本测试使用自定义 RestTemplate 模拟此解包行为，使 AlipayConnector 能正确解析响应字段。</p>
 *
 * <p>经验来源：2026-09-26-payment-connector-test-sandbox-rsa-adaptation
 * （sandbox 配置项适配、RSA 密钥动态生成、Mock 方法签名适配）</p>
 */
class AlipayE2ETest {

    private AlipayMockServer mockServer;
    private AlipayConnector connector;
    private TestKeyPairGenerator keyPairGenerator;

    @BeforeEach
    void setup() throws Exception {
        // 1. 启动 WireMock 模拟支付宝统一网关 API
        mockServer = new AlipayMockServer();
        mockServer.start();

        // 2. 动态生成 RSA-2048 密钥对，模拟商户私钥
        keyPairGenerator = new TestKeyPairGenerator();

        // 3. 创建 connector（无参构造器下 @Value 不生效，下方显式注入字段配置 real mode）
        connector = new AlipayConnector();

        // 4. 通过反射注入字段，配置为 real mode
        setField(connector, "sandbox", false);
        setField(connector, "enabled", true);
        setField(connector, "appId", "test_app_id");
        setField(connector, "merchantPrivateKey", keyPairGenerator.getPrivateKeyBase64());
        setField(connector, "alipayPublicKey", keyPairGenerator.getPublicKeyBase64());
        setField(connector, "apiBaseUrl", mockServer.getBaseUrl() + "/gateway.do");

        // 5. 注入自定义 RestTemplate（解包支付宝嵌套响应），连接到 WireMock
        setField(connector, "restTemplate", createAlipayRestTemplate());
    }

    @AfterEach
    void teardown() {
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    // ==================== 测试场景 ====================

    @Test
    @DisplayName("支付宝 E2E：precreate 返回 qr_code（下单成功）")
    void testPrecreate() {
        // 调用 connector.createPayment()，发起真实 HTTP 请求到 WireMock
        ConnectorPaymentRequest request = new ConnectorPaymentRequest(
                "e2e_alipay_order_001", 5000L, "CNY", "E2E测试-支付宝下单");

        ConnectorPaymentResult result = connector.createPayment(request);

        // 验证下单成功
        assertTrue(result.isSuccess(), "precreate 应返回成功");
        assertNotNull(result.getRedirectUrl(), "应返回 qr_code（扫码链接）");
        assertTrue(result.getRedirectUrl().startsWith("https://qr.alipay.com/"),
                "qr_code 应以 https://qr.alipay.com/ 开头，实际: " + result.getRedirectUrl());
        assertEquals("alipay_tx_mock_001", result.getConnectorPaymentId(),
                "trade_no 应为 WireMock 模拟返回的值");
    }

    @Test
    @DisplayName("支付宝 E2E：查询订单返回 TRADE_SUCCESS")
    void testQueryPayment() {
        // 先 createPayment，再 queryPayment
        ConnectorPaymentRequest request = new ConnectorPaymentRequest(
                "e2e_alipay_order_002", 3000L, "CNY", "E2E测试-支付宝查询");

        ConnectorPaymentResult createResult = connector.createPayment(request);
        assertTrue(createResult.isSuccess(), "precreate 应成功");

        // 查询订单 — WireMock 返回 trade_status=TRADE_SUCCESS
        PaymentStatus queryStatus = connector.queryPayment("e2e_alipay_order_002");

        // 验证返回 SUCCEEDED（支付宝 TRADE_SUCCESS → PaymentStatus.SUCCEEDED）
        assertEquals(PaymentStatus.SUCCEEDED, queryStatus,
                "查询订单应返回 SUCCEEDED（支付宝 trade_status=TRADE_SUCCESS 映射为 SUCCEEDED）");
    }

    @Test
    @DisplayName("支付宝 E2E：退款返回成功")
    void testRefund() {
        // 先 createPayment，再 refund
        ConnectorPaymentRequest request = new ConnectorPaymentRequest(
                "e2e_alipay_order_003", 10000L, "CNY", "E2E测试-支付宝退款");

        ConnectorPaymentResult createResult = connector.createPayment(request);
        assertTrue(createResult.isSuccess(), "precreate 应成功");

        // 申请退款 — WireMock 返回 fund_change=Y
        ConnectorRefundResult refundResult = connector.refund("e2e_alipay_order_003", 10000L);

        // 验证返回 success
        assertTrue(refundResult.isSuccess(), "退款应返回成功");
        assertNotNull(refundResult.getRefundId(), "应返回 refund_id");
        assertTrue(refundResult.getRefundId().startsWith("alipay_refund_"),
                "refund_id 应以 alipay_refund_ 开头，实际: " + refundResult.getRefundId());
    }

    // ==================== 辅助方法 ====================

    /**
     * 通过反射设置 connector 的 private 字段。
     * AlipayConnector 使用 @Value 注解注入配置，在非 Spring 环境下需手动注入。
     */
    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * 创建自定义 RestTemplate — 解包支付宝统一网关的嵌套响应。
     *
     * <p>支付宝统一网关返回格式为 {@code {"alipay_trade_precreate_response": {...}}}，
     * 外层 key 为 {@code alipay_trade_<method>_response}，内层为实际业务字段。
     * 真实支付宝 SDK 会自动解包外层，本方法通过覆盖 {@link RestTemplate#postForEntity}
     * 模拟此解包行为，使 {@link AlipayConnector} 能直接读取内层字段。</p>
     *
     * @return 解包嵌套响应的 RestTemplate 实例
     */
    private RestTemplate createAlipayRestTemplate() {
        return new RestTemplate() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public <T> ResponseEntity<T> postForEntity(String url, Object request,
                                                        Class<T> responseType, Object... uriVariables) {
                // 先以 Map.class 反序列化原始响应
                ResponseEntity<Map> rawResponse = super.postForEntity(url, request, Map.class);
                if (rawResponse.getBody() == null) {
                    return new ResponseEntity<>(null, rawResponse.getHeaders(), rawResponse.getStatusCode());
                }

                // 解包 alipay_trade_*_response 嵌套层
                Map<String, Object> body = rawResponse.getBody();
                for (Map.Entry<String, Object> entry : body.entrySet()) {
                    if (entry.getKey().startsWith("alipay_trade_") && entry.getValue() instanceof Map) {
                        Map<String, Object> unwrapped = (Map<String, Object>) entry.getValue();
                        return new ResponseEntity<>((T) unwrapped, rawResponse.getHeaders(),
                                rawResponse.getStatusCode());
                    }
                }

                // 无嵌套层时直接返回原始响应
                return (ResponseEntity<T>) rawResponse;
            }
        };
    }
}