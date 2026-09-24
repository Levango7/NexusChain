package org.nexus.gateway.orchestration.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nexus.gateway.orchestration.connectors.AlipaySignatureUtil;
import org.nexus.gateway.orchestration.connectors.WeChatPaySignatureUtil;
import org.nexus.gateway.orchestration.model.OrchPaymentStatus;
import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.nexus.gateway.orchestration.service.PaymentCallbackService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link PaymentCallbackController} 单元测试 — 验证回调接收、验签、幂等处理与状态更新。
 *
 * <p>P0-1 安全修复后：验签密钥/公钥未配置时回调被直接拒绝，因此所有
 * "成功处理" 场景都需要显式配置密钥并 mock 静态验签方法。</p>
 */
class PaymentCallbackControllerTest {

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 构造已配置微信 APIv3 密钥的 Controller。 */
    private PaymentCallbackController wechatControllerWithKey(PaymentCallbackService svc) throws Exception {
        PaymentCallbackController controller = new PaymentCallbackController(svc);
        setField(controller, "wechatApiV3Key", "test_wechat_v3_key");
        return controller;
    }

    /** 构造已配置支付宝公钥的 Controller。 */
    private PaymentCallbackController alipayControllerWithKey(PaymentCallbackService svc) throws Exception {
        PaymentCallbackController controller = new PaymentCallbackController(svc);
        setField(controller, "alipayPublicKey", "test_alipay_public_key");
        return controller;
    }

    // ==================== 微信回调测试 ====================

    @Test
    @DisplayName("微信回调：APIv3 密钥未配置 -> 直接拒绝，不处理")
    void wechatCallback_missingKey_rejected() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = new PaymentCallbackController(svc);
        // wechatApiV3Key 为空（默认值）→ 应直接拒绝

        String body = "{\"id\":\"evt_001\",\"out_trade_no\":\"pay_001\",\"trade_state\":\"SUCCESS\"}";
        ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                body, "1700000000", "nonce123", "dummy_signature");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("FAIL", resp.getBody().get("code"));
        verify(svc, never()).findById(anyString());
        verify(svc, never()).save(any());
    }

    @Test
    @DisplayName("微信回调：密钥已配置且验签通过，处理成功")
    void wechatCallback_verifiedSuccess() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = wechatControllerWithKey(svc);

        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("pay_001");
        payment.setStatus(OrchPaymentStatus.PROCESSING);
        when(svc.findById("pay_001")).thenReturn(Optional.of(payment));
        when(svc.save(any())).thenReturn(payment);

        String body = "{\"id\":\"evt_001\",\"out_trade_no\":\"pay_001\",\"transaction_id\":\"wx_tx_001\",\"trade_state\":\"SUCCESS\"}";

        try (MockedStatic<WeChatPaySignatureUtil> mocked = mockStatic(WeChatPaySignatureUtil.class)) {
            mocked.when(() -> WeChatPaySignatureUtil.verifyCallbackSignature(
                    anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                    body, "1700000000", "nonce123", "dummy_signature");

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals("SUCCESS", resp.getBody().get("code"));
            verify(svc).save(any());
            assertEquals(OrchPaymentStatus.SUCCEEDED, payment.getStatus());
        }
    }

    @Test
    @DisplayName("微信回调：密钥已配置但验签失败 -> 拒绝")
    void wechatCallback_signatureInvalid() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = wechatControllerWithKey(svc);

        String body = "{\"id\":\"evt_001\",\"out_trade_no\":\"pay_001\",\"trade_state\":\"SUCCESS\"}";

        try (MockedStatic<WeChatPaySignatureUtil> mocked = mockStatic(WeChatPaySignatureUtil.class)) {
            mocked.when(() -> WeChatPaySignatureUtil.verifyCallbackSignature(
                    anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(false);

            ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                    body, "1700000000", "nonce123", "bad_signature");

            assertEquals("FAIL", resp.getBody().get("code"));
            verify(svc, never()).save(any());
        }
    }

    @Test
    @DisplayName("微信回调：缺少签名头返回失败")
    void wechatCallback_missingSignatureHeaders() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        // 配置密钥，确保走到签名头检查分支（而非密钥缺失分支）
        PaymentCallbackController controller = wechatControllerWithKey(svc);

        ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                "{}", null, null, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("FAIL", resp.getBody().get("code"));
    }

    @Test
    @DisplayName("微信回调：订单不存在时返回成功（防止微信重试）")
    void wechatCallback_orderNotFound() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = wechatControllerWithKey(svc);

        when(svc.findById("unknown_order")).thenReturn(Optional.empty());

        String body = "{\"id\":\"evt_002\",\"out_trade_no\":\"unknown_order\",\"trade_state\":\"SUCCESS\"}";

        try (MockedStatic<WeChatPaySignatureUtil> mocked = mockStatic(WeChatPaySignatureUtil.class)) {
            mocked.when(() -> WeChatPaySignatureUtil.verifyCallbackSignature(
                    anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                    body, "1700000000", "nonce123", "dummy_signature");

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals("SUCCESS", resp.getBody().get("code"));
        }
    }

    @Test
    @DisplayName("微信回调：幂等处理 — 重复通知不再处理")
    void wechatCallback_idempotent() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = wechatControllerWithKey(svc);

        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("pay_002");
        payment.setStatus(OrchPaymentStatus.PROCESSING);
        when(svc.findById("pay_002")).thenReturn(Optional.of(payment));
        when(svc.save(any())).thenReturn(payment);

        String body = "{\"id\":\"evt_003\",\"out_trade_no\":\"pay_002\",\"trade_state\":\"SUCCESS\"}";

        try (MockedStatic<WeChatPaySignatureUtil> mocked = mockStatic(WeChatPaySignatureUtil.class)) {
            mocked.when(() -> WeChatPaySignatureUtil.verifyCallbackSignature(
                    anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            // 第一次调用：处理成功
            ResponseEntity<Map<String, Object>> resp1 = controller.wechatCallback(
                    body, "1700000000", "nonce123", "dummy_signature");
            assertEquals("SUCCESS", resp1.getBody().get("code"));
            verify(svc, times(1)).save(any());

            // 第二次调用：幂等返回成功，不再 save
            ResponseEntity<Map<String, Object>> resp2 = controller.wechatCallback(
                    body, "1700000000", "nonce123", "dummy_signature");
            assertEquals("SUCCESS", resp2.getBody().get("code"));
            verify(svc, times(1)).save(any()); // 仍然是 1 次
        }
    }

    @Test
    @DisplayName("微信回调：trade_state=REFUND 映射为 REFUNDED")
    void wechatCallback_refundStatus() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = wechatControllerWithKey(svc);

        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("pay_003");
        payment.setStatus(OrchPaymentStatus.SUCCEEDED);
        when(svc.findById("pay_003")).thenReturn(Optional.of(payment));
        when(svc.save(any())).thenReturn(payment);

        String body = "{\"id\":\"evt_004\",\"out_trade_no\":\"pay_003\",\"trade_state\":\"REFUND\"}";

        try (MockedStatic<WeChatPaySignatureUtil> mocked = mockStatic(WeChatPaySignatureUtil.class)) {
            mocked.when(() -> WeChatPaySignatureUtil.verifyCallbackSignature(
                    anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                    body, "1700000000", "nonce123", "dummy_signature");

            assertEquals("SUCCESS", resp.getBody().get("code"));
            assertEquals(OrchPaymentStatus.REFUNDED, payment.getStatus());
        }
    }

    @Test
    @DisplayName("微信回调：非法 JSON 不再崩溃，按缺少 out_trade_no 拒绝")
    void wechatCallback_malformedJson_rejected() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = wechatControllerWithKey(svc);

        try (MockedStatic<WeChatPaySignatureUtil> mocked = mockStatic(WeChatPaySignatureUtil.class)) {
            mocked.when(() -> WeChatPaySignatureUtil.verifyCallbackSignature(
                    anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);

            ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                    "not-a-json{{{", "1700000000", "nonce123", "dummy_signature");

            assertEquals("FAIL", resp.getBody().get("code"));
            verify(svc, never()).save(any());
        }
    }

    // ==================== 支付宝回调测试 ====================

    @Test
    @DisplayName("支付宝回调：公钥未配置 -> 直接拒绝，不处理")
    void alipayCallback_missingKey_rejected() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = new PaymentCallbackController(svc);
        // alipayPublicKey 为空（默认值）→ 应直接拒绝

        Map<String, String> params = new LinkedHashMap<>();
        params.put("out_trade_no", "pay_004");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("notify_id", "notify_001");

        ResponseEntity<String> resp = controller.alipayCallback(params);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("fail", resp.getBody());
        verify(svc, never()).findById(anyString());
        verify(svc, never()).save(any());
    }

    @Test
    @DisplayName("支付宝回调：公钥已配置且验签通过，处理成功")
    void alipayCallback_verifiedSuccess() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = alipayControllerWithKey(svc);

        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("pay_004");
        payment.setStatus(OrchPaymentStatus.PROCESSING);
        when(svc.findById("pay_004")).thenReturn(Optional.of(payment));
        when(svc.save(any())).thenReturn(payment);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", "test_app");
        params.put("out_trade_no", "pay_004");
        params.put("trade_no", "alipay_tx_001");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("notify_id", "notify_001");

        try (MockedStatic<AlipaySignatureUtil> mocked = mockStatic(AlipaySignatureUtil.class)) {
            mocked.when(() -> AlipaySignatureUtil.verifyCallbackSignature(any(), anyString()))
                    .thenReturn(true);

            ResponseEntity<String> resp = controller.alipayCallback(params);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals("success", resp.getBody());
            verify(svc).save(any());
            assertEquals(OrchPaymentStatus.SUCCEEDED, payment.getStatus());
        }
    }

    @Test
    @DisplayName("支付宝回调：验签失败 -> 返回 fail")
    void alipayCallback_signatureInvalid() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = alipayControllerWithKey(svc);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("out_trade_no", "pay_004");
        params.put("trade_status", "TRADE_SUCCESS");

        try (MockedStatic<AlipaySignatureUtil> mocked = mockStatic(AlipaySignatureUtil.class)) {
            mocked.when(() -> AlipaySignatureUtil.verifyCallbackSignature(any(), anyString()))
                    .thenReturn(false);

            ResponseEntity<String> resp = controller.alipayCallback(params);

            assertEquals("fail", resp.getBody());
            verify(svc, never()).save(any());
        }
    }

    @Test
    @DisplayName("支付宝回调：订单不存在时返回 success")
    void alipayCallback_orderNotFound() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = alipayControllerWithKey(svc);

        when(svc.findById("unknown")).thenReturn(Optional.empty());

        Map<String, String> params = new LinkedHashMap<>();
        params.put("out_trade_no", "unknown");
        params.put("trade_status", "TRADE_SUCCESS");

        try (MockedStatic<AlipaySignatureUtil> mocked = mockStatic(AlipaySignatureUtil.class)) {
            mocked.when(() -> AlipaySignatureUtil.verifyCallbackSignature(any(), anyString()))
                    .thenReturn(true);

            ResponseEntity<String> resp = controller.alipayCallback(params);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals("success", resp.getBody());
        }
    }

    @Test
    @DisplayName("支付宝回调：幂等处理 — 重复通知不再处理")
    void alipayCallback_idempotent() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = alipayControllerWithKey(svc);

        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("pay_005");
        payment.setStatus(OrchPaymentStatus.PROCESSING);
        when(svc.findById("pay_005")).thenReturn(Optional.of(payment));
        when(svc.save(any())).thenReturn(payment);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("out_trade_no", "pay_005");
        params.put("trade_no", "alipay_tx_002");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("notify_id", "notify_002");

        try (MockedStatic<AlipaySignatureUtil> mocked = mockStatic(AlipaySignatureUtil.class)) {
            mocked.when(() -> AlipaySignatureUtil.verifyCallbackSignature(any(), anyString()))
                    .thenReturn(true);

            // 第一次调用
            ResponseEntity<String> resp1 = controller.alipayCallback(params);
            assertEquals("success", resp1.getBody());
            verify(svc, times(1)).save(any());

            // 第二次调用：幂等返回 success，不再 save
            ResponseEntity<String> resp2 = controller.alipayCallback(params);
            assertEquals("success", resp2.getBody());
            verify(svc, times(1)).save(any());
        }
    }

    @Test
    @DisplayName("支付宝回调：TRADE_CLOSED 映射为 CANCELLED")
    void alipayCallback_tradeClosed() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = alipayControllerWithKey(svc);

        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("pay_006");
        payment.setStatus(OrchPaymentStatus.PROCESSING);
        when(svc.findById("pay_006")).thenReturn(Optional.of(payment));
        when(svc.save(any())).thenReturn(payment);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("out_trade_no", "pay_006");
        params.put("trade_status", "TRADE_CLOSED");
        params.put("notify_id", "notify_003");

        try (MockedStatic<AlipaySignatureUtil> mocked = mockStatic(AlipaySignatureUtil.class)) {
            mocked.when(() -> AlipaySignatureUtil.verifyCallbackSignature(any(), anyString()))
                    .thenReturn(true);

            ResponseEntity<String> resp = controller.alipayCallback(params);
            assertEquals("success", resp.getBody());
            assertEquals(OrchPaymentStatus.CANCELLED, payment.getStatus());
        }
    }

    @Test
    @DisplayName("支付宝回调：缺少 out_trade_no 返回 fail")
    void alipayCallback_missingOutTradeNo() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = alipayControllerWithKey(svc);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("trade_status", "TRADE_SUCCESS");

        try (MockedStatic<AlipaySignatureUtil> mocked = mockStatic(AlipaySignatureUtil.class)) {
            mocked.when(() -> AlipaySignatureUtil.verifyCallbackSignature(any(), anyString()))
                    .thenReturn(true);

            ResponseEntity<String> resp = controller.alipayCallback(params);
            assertEquals("fail", resp.getBody());
        }
    }
}
