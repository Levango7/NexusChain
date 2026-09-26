package org.nexus.gateway.sandbox.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connectors.WeChatPlatformCertificateManager;
import org.nexus.gateway.orchestration.controller.PaymentCallbackController;
import org.nexus.gateway.orchestration.model.OrchPaymentStatus;
import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.nexus.gateway.orchestration.service.PaymentCallbackService;
import org.nexus.gateway.sandbox.mock.AlipayCallbackSimulator;
import org.nexus.gateway.sandbox.util.TestKeyPairGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 支付宝回调端到端测试 — 使用真实 RSA2 密钥对和真实签名/验签流程，
 * 验证从回调参数构造、签名生成、验签到状态更新的全链路。
 *
 * <p>与 {@link org.nexus.gateway.orchestration.controller.PaymentCallbackControllerTest} 的区别：
 * 单元测试 mock 了 {@code AlipaySignatureUtil} 静态方法，仅验证控制器逻辑分支；
 * 本 E2E 测试使用 {@link TestKeyPairGenerator} 生成真实密钥对，
 * 通过 {@link AlipayCallbackSimulator} 生成带真实 RSA2 签名的回调参数，
 * 验签使用真实公钥，覆盖签名→验签→状态更新的完整链路。</p>
 *
 * <p>经验来源：</p>
 * <ul>
 *   <li>2026-09-17-spring-controller-direct-instantiate-test-no-mockmvc —
 *       直接实例化 Controller、不使用 MockMvc、断言 ResponseEntity</li>
 * </ul>
 */
class AlipayCallbackE2ETest {

    private AlipayCallbackSimulator callbackSimulator;
    private PaymentCallbackController controller;
    private PaymentCallbackService callbackService;
    private WeChatPlatformCertificateManager certificateManager;

    @BeforeEach
    void setup() throws Exception {
        // 1. 生成真实 RSA 密钥对（模拟支付宝商户私钥 + 平台公钥）
        TestKeyPairGenerator keyPair = new TestKeyPairGenerator();
        callbackSimulator = new AlipayCallbackSimulator(keyPair.getPrivateKeyBase64(), "test_app_id");

        // 2. Mock callbackService（仅 mock 数据层依赖，不 mock 签名验签）
        callbackService = mock(PaymentCallbackService.class);

        // 3. Mock certificateManager（支付宝回调不使用证书管理器，传 mock 即可）
        certificateManager = mock(WeChatPlatformCertificateManager.class);

        // 4. 创建 controller，通过反射注入 alipayPublicKey（模拟 @Value 注入）
        controller = new PaymentCallbackController(callbackService, certificateManager);
        setField(controller, "alipayPublicKey", keyPair.getPublicKeyBase64());
    }

    @Test
    @DisplayName("支付宝回调 E2E：验签+状态更新全流程")
    void testCallbackFullFlow() {
        // 1. 使用 callbackSimulator 构造带真实 RSA2 签名的回调参数
        Map<String, String> params = callbackSimulator.buildCallbackParams(
                "ORDER_E2E_001", "ALIPAY_TX_E2E_001", "TRADE_SUCCESS");

        // 2. mock callbackService 返回一个 PROCESSING 状态的订单
        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("ORDER_E2E_001");
        payment.setStatus(OrchPaymentStatus.PROCESSING);
        when(callbackService.findById("ORDER_E2E_001")).thenReturn(Optional.of(payment));
        when(callbackService.save(any())).thenReturn(payment);

        // 3. 调用 controller.alipayCallback()
        ResponseEntity<String> resp = controller.alipayCallback(params);

        // 4. 验证返回 "success"
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("success", resp.getBody());

        // 5. 验证 callbackService.save() 被调用，且状态更新为 SUCCEEDED
        verify(callbackService).save(any());
        assertEquals(OrchPaymentStatus.SUCCEEDED, payment.getStatus());
        assertNotNull(payment.getConfirmedAt());
    }

    @Test
    @DisplayName("支付宝回调 E2E：验签失败返回 fail")
    void testCallbackSignatureInvalid() {
        // 1. 使用 callbackSimulator 构造带真实签名的回调参数
        Map<String, String> params = callbackSimulator.buildCallbackParams(
                "ORDER_E2E_002", "ALIPAY_TX_E2E_002", "TRADE_SUCCESS");

        // 2. 篡改业务参数（使签名不再匹配），模拟验签失败场景
        params.put("out_trade_no", "TAMPERED_ORDER_ID");

        // 3. 调用 controller.alipayCallback()
        ResponseEntity<String> resp = controller.alipayCallback(params);

        // 4. 验证返回 "fail"
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("fail", resp.getBody());

        // 5. 验证 callbackService.save() 未被调用
        verify(callbackService, never()).save(any());
    }

    // ==================== 辅助方法 ====================

    /**
     * 通过反射设置 Controller 的私有字段（模拟 Spring @Value 注入）。
     */
    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}