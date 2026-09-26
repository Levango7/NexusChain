package org.nexus.gateway.sandbox.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connectors.WeChatPlatformCertificateManager;
import org.nexus.gateway.orchestration.controller.PaymentCallbackController;
import org.nexus.gateway.orchestration.model.OrchPaymentStatus;
import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.nexus.gateway.orchestration.service.PaymentCallbackService;
import org.nexus.gateway.sandbox.mock.WeChatCallbackSimulator;
import org.nexus.gateway.sandbox.util.TestPlatformCertificateFactory;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 微信支付回调端到端（E2E）测试 — 使用 {@link WeChatCallbackSimulator} 模拟微信回调通知，
 * 测试回调处理全流程（验签 → 解密 → 状态更新）。
 *
 * <p>测试策略：
 * <ul>
 *   <li>使用 {@link TestPlatformCertificateFactory} 动态生成 RSA-2048 密钥对，模拟微信平台证书</li>
 *   <li>使用 {@link WeChatCallbackSimulator} 构造真实的回调 body（含 AES-256-GCM 加密的 resource）
 *       和签名头（RSA-SHA256 签名）</li>
 *   <li>直接实例化 {@link PaymentCallbackController}，通过反射注入 @Value 字段</li>
 *   <li>Mock {@link PaymentCallbackService} 和 {@link WeChatPlatformCertificateManager}</li>
 *   <li>直接调用 controller.wechatCallback() 方法，断言 ResponseEntity</li>
 * </ul>
 *
 * <p>经验来源：
 * <ul>
 *   <li>2026-09-17-spring-controller-direct-instantiate-test-no-mockmvc —
 *       测试类用 @DisplayName 描述，不用 @SpringBootTest/@WebMvcTest，直接调用 Controller 方法断言 ResponseEntity</li>
 *   <li>2026-09-20-spring-boot-rest-controller-test-strategy-selection —
 *       Controller 测试策略选择：直接实例化 + 反射注入</li>
 * </ul>
 */
class WeChatCallbackE2ETest {

    private WeChatCallbackSimulator callbackSimulator;
    private PaymentCallbackController controller;
    private PaymentCallbackService callbackService;
    private WeChatPlatformCertificateManager certificateManager;
    private TestPlatformCertificateFactory certFactory;

    /** APIv3 密钥必须为 32 字节（AES-256），与 WeChatPaySignatureUtilTest 中的有效密钥格式一致。 */
    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef";

    @BeforeEach
    void setup() throws Exception {
        // 1. 创建测试平台证书工厂（动态生成 RSA-2048 密钥对）
        certFactory = new TestPlatformCertificateFactory();

        // 2. 创建回调模拟器（使用证书工厂私钥签名 + APIv3 密钥加密）
        callbackSimulator = new WeChatCallbackSimulator(certFactory, API_V3_KEY);

        // 3. Mock PaymentCallbackService
        callbackService = mock(PaymentCallbackService.class);

        // 4. Mock WeChatPlatformCertificateManager — 返回测试证书工厂的公钥（用于验签）
        certificateManager = mock(WeChatPlatformCertificateManager.class);
        when(certificateManager.getPlatformPublicKey(anyString()))
                .thenReturn(certFactory.getPublicKeyBase64());
        when(certificateManager.getAnyValidPlatformPublicKey())
                .thenReturn(certFactory.getPublicKeyBase64());

        // 5. 创建 Controller，注入 mock 依赖
        controller = new PaymentCallbackController(callbackService, certificateManager);

        // 6. 通过反射设置 @Value 注入的 wechatApiV3Key 字段（非 Spring 环境下需手动注入）
        setField(controller, "wechatApiV3Key", API_V3_KEY);
    }

    @Test
    @DisplayName("微信回调 E2E：验签+解密+状态更新全流程")
    void testCallbackFullFlow() {
        // 1. 使用 callbackSimulator.buildCallbackBody() 构造回调 body
        String body = callbackSimulator.buildCallbackBody(
                "EV-2018022511223320873", "PAY-123456", "SUCCESS");

        // 2. 使用 callbackSimulator.buildSignatureHeaders() 构造签名头
        Map<String, String> headers = callbackSimulator.buildSignatureHeaders(body);

        // 3. Mock callbackService.findById 返回一个 PROCESSING 状态的支付订单
        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("PAY-123456");
        payment.setStatus(OrchPaymentStatus.PROCESSING);
        when(callbackService.findById("PAY-123456")).thenReturn(Optional.of(payment));
        when(callbackService.save(any(OrchestratedPayment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 4. 调用 controller.wechatCallback()
        ResponseEntity<Map<String, Object>> response = controller.wechatCallback(
                body,
                headers.get("Wechatpay-Timestamp"),
                headers.get("Wechatpay-Nonce"),
                headers.get("Wechatpay-Signature"),
                headers.get("Wechatpay-Serial"));

        // 5. 验证返回 SUCCESS
        assertNotNull(response, "响应不应为 null");
        assertEquals(200, response.getStatusCode().value(), "HTTP 状态码应为 200");
        assertNotNull(response.getBody(), "响应体不应为 null");
        assertEquals("SUCCESS", response.getBody().get("code"),
                "回调处理成功应返回 code=SUCCESS");

        // 6. 验证 callbackService.save() 被调用（状态从 PROCESSING 更新为 SUCCEEDED）
        verify(callbackService).save(any(OrchestratedPayment.class));
    }

    @Test
    @DisplayName("微信回调 E2E：验签失败返回 FAIL")
    void testCallbackSignatureInvalid() {
        // 1. 构造回调 body
        String body = callbackSimulator.buildCallbackBody(
                "EV-2018022511223320873", "PAY-123456", "SUCCESS");

        // 2. 构造签名头
        Map<String, String> headers = callbackSimulator.buildSignatureHeaders(body);

        // 3. 使用错误的签名（验签会失败）
        String invalidSignature = "invalid_signature_base64_string";

        // 4. 调用 controller.wechatCallback()
        ResponseEntity<Map<String, Object>> response = controller.wechatCallback(
                body,
                headers.get("Wechatpay-Timestamp"),
                headers.get("Wechatpay-Nonce"),
                invalidSignature,
                headers.get("Wechatpay-Serial"));

        // 5. 验证返回 FAIL
        assertNotNull(response, "响应不应为 null");
        assertNotNull(response.getBody(), "响应体不应为 null");
        assertEquals("FAIL", response.getBody().get("code"),
                "验签失败应返回 code=FAIL");

        // 6. 验证 callbackService.save() 未被调用
        verify(callbackService, never()).save(any());
    }

    @Test
    @DisplayName("微信回调 E2E：解密失败返回 FAIL")
    void testCallbackDecryptFailed() throws Exception {
        // 1. 使用正确的 APIv3 密钥构造 body（resource 被 AES-256-GCM 加密）
        String body = callbackSimulator.buildCallbackBody(
                "EV-2018022511223320873", "PAY-123456", "SUCCESS");

        // 2. 构造签名头（验签会通过，因为签名用的是 certFactory 的私钥，与公钥配对）
        Map<String, String> headers = callbackSimulator.buildSignatureHeaders(body);

        // 3. 在 controller 中设置错误的 APIv3 密钥（验签通过但解密会失败）
        setField(controller, "wechatApiV3Key", "abcdef0123456789abcdef0123456789");

        // 4. 调用 controller.wechatCallback()
        ResponseEntity<Map<String, Object>> response = controller.wechatCallback(
                body,
                headers.get("Wechatpay-Timestamp"),
                headers.get("Wechatpay-Nonce"),
                headers.get("Wechatpay-Signature"),
                headers.get("Wechatpay-Serial"));

        // 5. 验证返回 FAIL
        assertNotNull(response, "响应不应为 null");
        assertNotNull(response.getBody(), "响应体不应为 null");
        assertEquals("FAIL", response.getBody().get("code"),
                "解密失败应返回 code=FAIL");

        // 6. 验证 callbackService.save() 未被调用
        verify(callbackService, never()).save(any());
    }

    // ==================== 辅助方法 ====================

    /**
     * 通过反射设置 controller 的 private 字段。
     * PaymentCallbackController 使用 @Value 注解注入配置，在非 Spring 环境下需手动注入。
     */
    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}