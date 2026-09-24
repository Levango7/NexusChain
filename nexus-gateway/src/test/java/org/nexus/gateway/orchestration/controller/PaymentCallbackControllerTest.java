package org.nexus.gateway.orchestration.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.model.OrchPaymentStatus;
import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.nexus.gateway.orchestration.service.PaymentCallbackService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link PaymentCallbackController} 单元测试 — 验证回调接收、验签、幂等处理与状态更新。
 */
class PaymentCallbackControllerTest {

    private PaymentCallbackController newController(PaymentCallbackService svc) {
        return new PaymentCallbackController(svc);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ==================== 微信回调测试 ====================

    @Test
    @DisplayName("微信回调：dry-run 模式（无 APIv3 密钥）验签跳过，处理成功")
    void wechatCallback_dryRun_success() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = newController(svc);
        // apiV3Key 为空（默认值），进入 dry-run 模式

        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("pay_001");
        payment.setStatus(OrchPaymentStatus.PROCESSING);
        when(svc.findById("pay_001")).thenReturn(Optional.of(payment));
        when(svc.save(any())).thenReturn(payment);

        String body = "{\"id\":\"evt_001\",\"out_trade_no\":\"pay_001\",\"transaction_id\":\"wx_tx_001\",\"trade_state\":\"SUCCESS\"}";
        ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                body, "1700000000", "nonce123", "dummy_signature");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("SUCCESS", resp.getBody().get("code"));
        verify(svc).save(any());
        assertEquals(OrchPaymentStatus.SUCCEEDED, payment.getStatus());
    }

    @Test
    @DisplayName("微信回调：缺少签名头返回失败")
    void wechatCallback_missingSignatureHeaders() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = newController(svc);

        ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                "{}", null, null, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("FAIL", resp.getBody().get("code"));
    }

    @Test
    @DisplayName("微信回调：订单不存在时返回成功（防止微信重试）")
    void wechatCallback_orderNotFound() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = newController(svc);

        when(svc.findById("unknown_order")).thenReturn(Optional.empty());

        String body = "{\"id\":\"evt_002\",\"out_trade_no\":\"unknown_order\",\"trade_state\":\"SUCCESS\"}";
        ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                body, "1700000000", "nonce123", "dummy_signature");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("SUCCESS", resp.getBody().get("code"));
    }

    @Test
    @DisplayName("微信回调：幂等处理 — 重复通知不再处理")
    void wechatCallback_idempotent() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = newController(svc);

        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("pay_002");
        payment.setStatus(OrchPaymentStatus.PROCESSING);
        when(svc.findById("pay_002")).thenReturn(Optional.of(payment));
        when(svc.save(any())).thenReturn(payment);

        String body = "{\"id\":\"evt_003\",\"out_trade_no\":\"pay_002\",\"trade_state\":\"SUCCESS\"}";

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

    @Test
    @DisplayName("微信回调：trade_state=REFUND 映射为 REFUNDED")
    void wechatCallback_refundStatus() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = newController(svc);

        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("pay_003");
        payment.setStatus(OrchPaymentStatus.SUCCEEDED);
        when(svc.findById("pay_003")).thenReturn(Optional.of(payment));
        when(svc.save(any())).thenReturn(payment);

        String body = "{\"id\":\"evt_004\",\"out_trade_no\":\"pay_003\",\"trade_state\":\"REFUND\"}";
        ResponseEntity<Map<String, Object>> resp = controller.wechatCallback(
                body, "1700000000", "nonce123", "dummy_signature");

        assertEquals("SUCCESS", resp.getBody().get("code"));
        assertEquals(OrchPaymentStatus.REFUNDED, payment.getStatus());
    }

    // ==================== 支付宝回调测试 ====================

    @Test
    @DisplayName("支付宝回调：dry-run 模式（无公钥）验签跳过，处理成功")
    void alipayCallback_dryRun_success() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = newController(svc);
        // alipayPublicKey 为空（默认值），进入 dry-run 模式

        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("pay_004");
        payment.setStatus(OrchPaymentStatus.PROCESSING);
        when(svc.findById("pay_004")).thenReturn(Optional.of(payment));
        when(svc.save(any())).thenReturn(payment);

        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("app_id", "test_app");
        params.put("out_trade_no", "pay_004");
        params.put("trade_no", "alipay_tx_001");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("notify_id", "notify_001");

        ResponseEntity<String> resp = controller.alipayCallback(params);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("success", resp.getBody());
        verify(svc).save(any());
        assertEquals(OrchPaymentStatus.SUCCEEDED, payment.getStatus());
    }

    @Test
    @DisplayName("支付宝回调：订单不存在时返回 success")
    void alipayCallback_orderNotFound() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = newController(svc);

        when(svc.findById("unknown")).thenReturn(Optional.empty());

        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("out_trade_no", "unknown");
        params.put("trade_status", "TRADE_SUCCESS");

        ResponseEntity<String> resp = controller.alipayCallback(params);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("success", resp.getBody());
    }

    @Test
    @DisplayName("支付宝回调：幂等处理 — 重复通知不再处理")
    void alipayCallback_idempotent() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = newController(svc);

        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("pay_005");
        payment.setStatus(OrchPaymentStatus.PROCESSING);
        when(svc.findById("pay_005")).thenReturn(Optional.of(payment));
        when(svc.save(any())).thenReturn(payment);

        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("out_trade_no", "pay_005");
        params.put("trade_no", "alipay_tx_002");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("notify_id", "notify_002");

        // 第一次调用
        ResponseEntity<String> resp1 = controller.alipayCallback(params);
        assertEquals("success", resp1.getBody());
        verify(svc, times(1)).save(any());

        // 第二次调用：幂等返回 success，不再 save
        ResponseEntity<String> resp2 = controller.alipayCallback(params);
        assertEquals("success", resp2.getBody());
        verify(svc, times(1)).save(any());
    }

    @Test
    @DisplayName("支付宝回调：TRADE_CLOSED 映射为 CANCELLED")
    void alipayCallback_tradeClosed() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = newController(svc);

        OrchestratedPayment payment = new OrchestratedPayment();
        payment.setId("pay_006");
        payment.setStatus(OrchPaymentStatus.PROCESSING);
        when(svc.findById("pay_006")).thenReturn(Optional.of(payment));
        when(svc.save(any())).thenReturn(payment);

        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("out_trade_no", "pay_006");
        params.put("trade_status", "TRADE_CLOSED");
        params.put("notify_id", "notify_003");

        ResponseEntity<String> resp = controller.alipayCallback(params);
        assertEquals("success", resp.getBody());
        assertEquals(OrchPaymentStatus.CANCELLED, payment.getStatus());
    }

    @Test
    @DisplayName("支付宝回调：缺少 out_trade_no 返回 fail")
    void alipayCallback_missingOutTradeNo() throws Exception {
        PaymentCallbackService svc = mock(PaymentCallbackService.class);
        PaymentCallbackController controller = newController(svc);

        Map<String, String> params = new java.util.LinkedHashMap<>();
        params.put("trade_status", "TRADE_SUCCESS");

        ResponseEntity<String> resp = controller.alipayCallback(params);
        assertEquals("fail", resp.getBody());
    }
}