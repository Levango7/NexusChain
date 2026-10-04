package org.nexus.gateway.sandbox.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.ConnectorRefundResult;
import org.nexus.gateway.orchestration.connector.PaymentStatus;
import org.nexus.gateway.orchestration.connectors.WeChatPayConnector;
import org.nexus.gateway.sandbox.mock.WeChatMockServer;
import org.nexus.gateway.sandbox.util.TestKeyPairGenerator;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 微信支付端到端（E2E）测试 — 使用 {@link WeChatMockServer}（WireMock）模拟微信支付 V3 API，
 * 验证 {@link WeChatPayConnector} 在 real mode（sandbox=false）下的全流程：
 * 统一下单 → 查询订单 → 申请退款。
 *
 * <p>测试策略：
 * <ul>
 *   <li>使用 {@link TestKeyPairGenerator} 动态生成 RSA-2048 密钥对作为商户私钥</li>
 *   <li>通过反射注入 connector 的 @Value 字段，配置为 real mode（sandbox=false）</li>
 *   <li>使用真实 {@link RestTemplate}（非 mock），连接到 WireMock 模拟的微信支付 API</li>
 *   <li>验证 connector 对微信支付 V3 API 响应的正确解析和状态映射</li>
 * </ul>
 *
 * <p>经验来源：2026-09-26-payment-connector-test-sandbox-rsa-adaptation
 * （sandbox 配置项适配、RSA 密钥动态生成、Mock 方法签名适配）</p>
 */
class WeChatPayE2ETest {

    private WeChatMockServer mockServer;
    private WeChatPayConnector connector;
    private TestKeyPairGenerator keyPairGenerator;

    @BeforeEach
    void setup() throws Exception {
        // 1. 启动 WireMock 模拟微信支付 V3 API
        mockServer = new WeChatMockServer();
        mockServer.start();

        // 2. 动态生成 RSA-2048 密钥对，模拟商户私钥
        keyPairGenerator = new TestKeyPairGenerator();

        // 3. 创建 connector（无参构造器下 @Value 不生效，下方显式注入字段配置 real mode）
        connector = new WeChatPayConnector();

        // 4. 通过反射注入字段，配置为 real mode
        setField(connector, "sandbox", false);
        setField(connector, "apiBase", mockServer.getBaseUrl());
        setField(connector, "merchantPrivateKey", keyPairGenerator.getPrivateKeyBase64());
        setField(connector, "apiV3Key", "test_api_v3_key_32_bytes_long_!!!");
        setField(connector, "certSerialNo", "mock_cert_serial_001");
        setField(connector, "appId", "wx_test_app");
        setField(connector, "mchId", "mch_test_001");
        setField(connector, "apiKey", "test_api_key");
        setField(connector, "enabled", true);

        // 5. 注入真实 RestTemplate（非 mock），连接到 WireMock
        setField(connector, "restTemplate", new RestTemplate());
    }

    @AfterEach
    void teardown() {
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Test
    @DisplayName("微信支付 E2E：统一下单返回 code_url")
    void testCreatePayment() {
        // 调用 connector.createPayment()，发起真实 HTTP 请求到 WireMock
        ConnectorPaymentRequest request = new ConnectorPaymentRequest(
                "e2e_order_001", 5000L, "CNY", "E2E测试-统一下单");

        ConnectorPaymentResult result = connector.createPayment(request);

        // 验证返回 success + code_url
        assertTrue(result.isSuccess(), "统一下单应返回成功");
        assertNotNull(result.getRedirectUrl(), "应返回 code_url（扫码链接）");
        assertTrue(result.getRedirectUrl().startsWith("weixin://wxpay/bizpayurl"),
                "code_url 应以 weixin://wxpay/bizpayurl 开头，实际: " + result.getRedirectUrl());
        assertEquals(PaymentStatus.PROCESSING, result.getStatus(),
                "real mode 下单后状态应为 PROCESSING（等待用户支付）");
    }

    @Test
    @DisplayName("微信支付 E2E：查询订单返回 SUCCESS")
    void testQueryPayment() {
        // 先 createPayment，再 queryPayment
        ConnectorPaymentRequest request = new ConnectorPaymentRequest(
                "e2e_order_002", 3000L, "CNY", "E2E测试-查询订单");

        ConnectorPaymentResult createResult = connector.createPayment(request);
        assertTrue(createResult.isSuccess(), "统一下单应成功");

        // 查询订单 — WireMock 返回 trade_state=SUCCESS
        PaymentStatus queryStatus = connector.queryPayment("e2e_order_002");

        // 验证返回 SUCCEEDED（微信 SUCCESS → PaymentStatus.SUCCEEDED）
        assertEquals(PaymentStatus.SUCCEEDED, queryStatus,
                "查询订单应返回 SUCCEEDED（微信 trade_state=SUCCESS 映射为 SUCCEEDED）");
    }

    @Test
    @DisplayName("微信支付 E2E：退款返回成功")
    void testRefund() {
        // 先 createPayment，再 refund
        ConnectorPaymentRequest request = new ConnectorPaymentRequest(
                "e2e_order_003", 10000L, "CNY", "E2E测试-退款");

        ConnectorPaymentResult createResult = connector.createPayment(request);
        assertTrue(createResult.isSuccess(), "统一下单应成功");

        // 申请退款 — WireMock 返回 refund_id + status=SUCCESS
        ConnectorRefundResult refundResult = connector.refund("e2e_order_003", 10000L);

        // 验证返回 success
        assertTrue(refundResult.isSuccess(), "退款应返回成功");
        assertNotNull(refundResult.getRefundId(), "应返回 refund_id");
        assertEquals("wx_refund_mock_001", refundResult.getRefundId(),
                "refund_id 应为 WireMock 模拟返回的值");
    }

    // ==================== 辅助方法 ====================

    /**
     * 通过反射设置 connector 的 private 字段。
     * WeChatPayConnector 使用 @Value 注解注入配置，在非 Spring 环境下需手动注入。
     */
    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}