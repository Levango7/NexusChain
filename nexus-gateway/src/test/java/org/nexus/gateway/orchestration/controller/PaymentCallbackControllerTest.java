package org.nexus.gateway.orchestration.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nexus.gateway.orchestration.connectors.AlipaySignatureUtil;
import org.nexus.gateway.orchestration.connectors.WeChatPaySignatureUtil;
import org.nexus.gateway.orchestration.connectors.WeChatPlatformCertificateManager;
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
 * {@link PaymentCallbackController} 单元测试 — 验证回调接收、验签、解密、幂等处理与状态更新。
 *
 * <p>Wave 13 修改后：微信回调验签使用平台证书 RSA-SHA256，回调解密使用 AES-256-GCM。
 * 所有 "成功处理" 场景需要 mock 平台证书管理器和静态签名/解密方法。</p>
 */
class PaymentCallbackControllerTest {

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 构造已配置微信 APIv3 密钥和平台证书管理器的 Controller。 */
    private PaymentCallbackController wechatControllerWithKey(PaymentCallbackService svc,
                                                               WeChatPlatformCertificateManager certMgr) throws Exception {
        PaymentCallbackController controller = new PaymentCallbackController(svc, certMgr);
        setField(controller, "wechatApiV3Key", "test_wechat_v3_key");
        return controller;
    }

    /** 构造已配置支付宝公钥的 Controller。 */
    private PaymentCallbackController alipayControllerWithKey(PaymentCallbackService svc,
                                                               WeChatPlatformCertificateManager certMgr) throws Exception {
        PaymentCallbackController controller = new PaymentCallbackController(svc, certMgr);
        setField(controller, "alipayPublicKey", "test_alipay_public_key");
        return controller;
    }

    /** 构造微信回调 body（包含 resource 字段）。 */
    private String wechatCallbackBody(String notificationId, String outTradeNo, String tradeState) {
        return "{\"id\":\"" + notificationId + "\","
                + "\"event_type\":\"TRANSACTION.SUCCESS\","
                + "\"resource\":{\"ciphertext\":\"encrypted_data\","
                + "\"nonce\":\"nonce123\","
                + "\"associated_data\":\"transaction\"}}";
    }

    /** 构造解密后的 JSON（模拟 decryptResource 的返回值）。 */
    private String decryptedResourceJson(String outTradeNo, String transactionId, String tradeState) {
        return "{\"out_trade_no\":\"" + outTradeNo + "\","
                + "\"transaction_id\":\"" + transactionId + "\","
                + "\"trade_state\":\"" + tradeState + "\"}";
    }

    // ==================== 微信回调测试 ====================

    @Test
    @DisplayName("微信回调：APIv3 密钥未配置 -> 直接拒绝，不处理")
    void wechatCallback_missingKey_rejected() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        WeChatPlatformCertificateManager certMgr = mock(WeChatPlatformCertificateManager.class);
        PaymentCallbackController controller = new PaymentCallbackController(svc, certMgr);
        // wechatApiV3Key 为空（默认值）→ 应直接拒绝

        String body = wechatCallbackBody("evt_001", "pay_001", "SUCCESS");
        ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                body, "1700000000", "nonce123", "dummy_signature", "cert_serial_001");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("FAIL", resp.getBody().get("code"));
        verify(svc, never()).findById(anyString());
        verify(svc, never()).save(any());
    }

    @Test
    @DisplayName("微信回调：密钥已配置且验签通过+解密成功，处理成功")
    void wechatCallback_verifiedSuccess() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        WeChatPlatformCertificateManager certMgr = mock(WeChatPlatformCertificateManager.class);
        PaymentCallbackController controller = wechatControllerWithKey(svc, certMgr);

        when(certMgr.getPlatformPublicKey("cert_serial_001")).thenReturn("test_platform_public_key");

        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("pay_001");
        payment.setStatus(OrchPaymentStatus.PROCESSING);
        when(svc.findById("pay_001")).thenReturn(Optional.of(payment));
        when(svc.save(any())).thenReturn(payment);

        String body = wechatCallbackBody("evt_001", "pay_001", "SUCCESS");

        try (MockedStatic<WeChatPaySignatureUtil> mocked = mockStatic(WeChatPaySignatureUtil.class)) {
            mocked.when(() -> WeChatPaySignatureUtil.verifyCallbackSignatureWithPlatformCert(
                    anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
            mocked.when(() -> WeChatPaySignatureUtil.decryptResource(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(
                    decryptedResourceJson("pay_001", "wx_tx_001", "SUCCESS"));

            ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                    body, "1700000000", "nonce123", "dummy_signature", "cert_serial_001");

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
        WeChatPlatformCertificateManager certMgr = mock(WeChatPlatformCertificateManager.class);
        PaymentCallbackController controller = wechatControllerWithKey(svc, certMgr);

        when(certMgr.getPlatformPublicKey(anyString())).thenReturn("test_platform_public_key");

        String body = wechatCallbackBody("evt_001", "pay_001", "SUCCESS");

        try (MockedStatic<WeChatPaySignatureUtil> mocked = mockStatic(WeChatPaySignatureUtil.class)) {
            mocked.when(() -> WeChatPaySignatureUtil.verifyCallbackSignatureWithPlatformCert(
                    anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(false);

            ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                    body, "1700000000", "nonce123", "bad_signature", "cert_serial_001");

            assertEquals("FAIL", resp.getBody().get("code"));
            verify(svc, never()).save(any());
        }
    }

    @Test
    @DisplayName("微信回调：无可用平台证书 -> 拒绝处理（fail-closed）")
    void wechatCallback_noPlatformCert_rejected() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        WeChatPlatformCertificateManager certMgr = mock(WeChatPlatformCertificateManager.class);
        PaymentCallbackController controller = wechatControllerWithKey(svc, certMgr);

        when(certMgr.getPlatformPublicKey(anyString())).thenReturn(null);
        when(certMgr.getAnyValidPlatformPublicKey()).thenReturn(null);

        String body = wechatCallbackBody("evt_001", "pay_001", "SUCCESS");

        ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                body, "1700000000", "nonce123", "dummy_signature", "cert_serial_001");

        assertEquals("FAIL", resp.getBody().get("code"));
        verify(svc, never()).save(any());
    }

    @Test
    @DisplayName("微信回调：缺少签名头返回失败")
    void wechatCallback_missingSignatureHeaders() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        WeChatPlatformCertificateManager certMgr = mock(WeChatPlatformCertificateManager.class);
        PaymentCallbackController controller = wechatControllerWithKey(svc, certMgr);

        ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                "{}", null, null, null, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("FAIL", resp.getBody().get("code"));
    }

    @Test
    @DisplayName("微信回调：订单不存在时返回成功（防止微信重试）")
    void wechatCallback_orderNotFound() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        WeChatPlatformCertificateManager certMgr = mock(WeChatPlatformCertificateManager.class);
        PaymentCallbackController controller = wechatControllerWithKey(svc, certMgr);

        when(certMgr.getPlatformPublicKey(anyString())).thenReturn("test_platform_public_key");
        when(svc.findById("unknown_order")).thenReturn(Optional.empty());

        String body = wechatCallbackBody("evt_002", "unknown_order", "SUCCESS");

        try (MockedStatic<WeChatPaySignatureUtil> mocked = mockStatic(WeChatPaySignatureUtil.class)) {
            mocked.when(() -> WeChatPaySignatureUtil.verifyCallbackSignatureWithPlatformCert(
                    anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
            mocked.when(() -> WeChatPaySignatureUtil.decryptResource(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(
                    decryptedResourceJson("unknown_order", "wx_tx_002", "SUCCESS"));

            ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                    body, "1700000000", "nonce123", "dummy_signature", "cert_serial_001");

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals("SUCCESS", resp.getBody().get("code"));
        }
    }

    @Test
    @DisplayName("微信回调：幂等处理 — 重复通知不再处理")
    void wechatCallback_idempotent() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        WeChatPlatformCertificateManager certMgr = mock(WeChatPlatformCertificateManager.class);
        PaymentCallbackController controller = wechatControllerWithKey(svc, certMgr);

        when(certMgr.getPlatformPublicKey(anyString())).thenReturn("test_platform_public_key");

        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("pay_002");
        payment.setStatus(OrchPaymentStatus.PROCESSING);
        when(svc.findById("pay_002")).thenReturn(Optional.of(payment));
        when(svc.save(any())).thenReturn(payment);

        String body = wechatCallbackBody("evt_003", "pay_002", "SUCCESS");

        try (MockedStatic<WeChatPaySignatureUtil> mocked = mockStatic(WeChatPaySignatureUtil.class)) {
            mocked.when(() -> WeChatPaySignatureUtil.verifyCallbackSignatureWithPlatformCert(
                    anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
            mocked.when(() -> WeChatPaySignatureUtil.decryptResource(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(
                    decryptedResourceJson("pay_002", "wx_tx_003", "SUCCESS"));

            // 第一次调用：处理成功
            ResponseEntity<Map<String, Object>> resp1 = controller.wechatCallback(
                    body, "1700000000", "nonce123", "dummy_signature", "cert_serial_001");
            assertEquals("SUCCESS", resp1.getBody().get("code"));
            verify(svc, times(1)).save(any());

            // 第二次调用：幂等返回成功，不再 save
            ResponseEntity<Map<String, Object>> resp2 = controller.wechatCallback(
                    body, "1700000000", "nonce123", "dummy_signature", "cert_serial_001");
            assertEquals("SUCCESS", resp2.getBody().get("code"));
            verify(svc, times(1)).save(any()); // 仍然是 1 次
        }
    }

    @Test
    @DisplayName("微信回调：trade_state=REFUND 映射为 REFUNDED")
    void wechatCallback_refundStatus() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        WeChatPlatformCertificateManager certMgr = mock(WeChatPlatformCertificateManager.class);
        PaymentCallbackController controller = wechatControllerWithKey(svc, certMgr);

        when(certMgr.getPlatformPublicKey(anyString())).thenReturn("test_platform_public_key");

        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("pay_003");
        payment.setStatus(OrchPaymentStatus.SUCCEEDED);
        when(svc.findById("pay_003")).thenReturn(Optional.of(payment));
        when(svc.save(any())).thenReturn(payment);

        String body = wechatCallbackBody("evt_004", "pay_003", "REFUND");

        try (MockedStatic<WeChatPaySignatureUtil> mocked = mockStatic(WeChatPaySignatureUtil.class)) {
            mocked.when(() -> WeChatPaySignatureUtil.verifyCallbackSignatureWithPlatformCert(
                    anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
            mocked.when(() -> WeChatPaySignatureUtil.decryptResource(
                    anyString(), anyString(), anyString(), anyString())).thenReturn(
                    decryptedResourceJson("pay_003", "wx_tx_004", "REFUND"));

            ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                    body, "1700000000", "nonce123", "dummy_signature", "cert_serial_001");

            assertEquals("SUCCESS", resp.getBody().get("code"));
            assertEquals(OrchPaymentStatus.REFUNDED, payment.getStatus());
        }
    }

    @Test
    @DisplayName("微信回调：AES-GCM 解密失败 -> 拒绝处理")
    void wechatCallback_decryptFailed_rejected() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        WeChatPlatformCertificateManager certMgr = mock(WeChatPlatformCertificateManager.class);
        PaymentCallbackController controller = wechatControllerWithKey(svc, certMgr);

        when(certMgr.getPlatformPublicKey(anyString())).thenReturn("test_platform_public_key");

        String body = wechatCallbackBody("evt_005", "pay_005", "SUCCESS");

        try (MockedStatic<WeChatPaySignatureUtil> mocked = mockStatic(WeChatPaySignatureUtil.class)) {
            mocked.when(() -> WeChatPaySignatureUtil.verifyCallbackSignatureWithPlatformCert(
                    anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
            mocked.when(() -> WeChatPaySignatureUtil.decryptResource(
                    anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("解密失败: AEADBadTagException"));

            ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                    body, "1700000000", "nonce123", "dummy_signature", "cert_serial_001");

            assertEquals("FAIL", resp.getBody().get("code"));
            verify(svc, never()).save(any());
        }
    }

    // ==================== 支付宝回调测试 ====================

    @Test
    @DisplayName("支付宝回调：公钥未配置 -> 直接拒绝，不处理")
    void alipayCallback_missingKey_rejected() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        WeChatPlatformCertificateManager certMgr = mock(WeChatPlatformCertificateManager.class);
        PaymentCallbackController controller = new PaymentCallbackController(svc, certMgr);
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
        WeChatPlatformCertificateManager certMgr = mock(WeChatPlatformCertificateManager.class);
        PaymentCallbackController controller = alipayControllerWithKey(svc, certMgr);

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
        WeChatPlatformCertificateManager certMgr = mock(WeChatPlatformCertificateManager.class);
        PaymentCallbackController controller = alipayControllerWithKey(svc, certMgr);

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
        WeChatPlatformCertificateManager certMgr = mock(WeChatPlatformCertificateManager.class);
        PaymentCallbackController controller = alipayControllerWithKey(svc, certMgr);

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
        WeChatPlatformCertificateManager certMgr = mock(WeChatPlatformCertificateManager.class);
        PaymentCallbackController controller = alipayControllerWithKey(svc, certMgr);

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
        WeChatPlatformCertificateManager certMgr = mock(WeChatPlatformCertificateManager.class);
        PaymentCallbackController controller = alipayControllerWithKey(svc, certMgr);

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
        WeChatPlatformCertificateManager certMgr = mock(WeChatPlatformCertificateManager.class);
        PaymentCallbackController controller = alipayControllerWithKey(svc, certMgr);

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
