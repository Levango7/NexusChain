package org.nexus.gateway.webhook;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link WebhookSubscriptionController} 单元测试。
 *
 * <p>验证订阅管理 API 端点：
 * <ul>
 *   <li>POST /api/v1/webhook-subscriptions — 创建订阅</li>
 *   <li>GET /api/v1/webhook-subscriptions — 列出订阅</li>
 *   <li>GET /api/v1/webhook-subscriptions/{id} — 查看详情</li>
 *   <li>PUT /api/v1/webhook-subscriptions/{id} — 更新订阅</li>
 *   <li>POST /api/v1/webhook-subscriptions/{id}/pause — 暂停</li>
 *   <li>POST /api/v1/webhook-subscriptions/{id}/resume — 恢复</li>
 *   <li>DELETE /api/v1/webhook-subscriptions/{id} — 删除</li>
 * </ul>
 */
class WebhookSubscriptionControllerTest {

    private WebhookSubscriptionService subscriptionService;
    private MerchantOwnershipGuard ownershipGuard;
    private WebhookSubscriptionController controller;
    private HttpServletRequest httpRequest;

    private static final Long MERCHANT_ID = 1001L;

    @BeforeEach
    void setUp() {
        subscriptionService = mock(WebhookSubscriptionService.class);
        ownershipGuard = mock(MerchantOwnershipGuard.class);
        httpRequest = mock(HttpServletRequest.class);
        controller = new WebhookSubscriptionController(subscriptionService, ownershipGuard);

        when(ownershipGuard.requireMerchantId(httpRequest)).thenReturn(MERCHANT_ID);
    }

    // --- 创建订阅 ---

    @Test
    @DisplayName("POST 创建订阅：返回 201 和订阅详情")
    void create_success() {
        WebhookSubscription sub = createSampleSubscription(1L);
        when(subscriptionService.createSubscription(eq(MERCHANT_ID), anyString(), any(), anyString()))
                .thenReturn(sub);

        WebhookSubscriptionController.CreateSubscriptionRequest request =
                new WebhookSubscriptionController.CreateSubscriptionRequest();
        request.setTargetUrl("https://merchant.example.com/hook");
        request.setEventTypes("PAYMENT_CONFIRMED,PAYMENT_FAILED");
        request.setDescription("支付通知");

        ResponseEntity<Map<String, Object>> result = controller.create(request, httpRequest);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals(1L, result.getBody().get("id"));
        assertEquals("https://merchant.example.com/hook", result.getBody().get("target_url"));
        assertEquals("ACTIVE", result.getBody().get("status"));
    }

    @Test
    @DisplayName("POST 创建订阅：merchantId 从认证上下文获取")
    void create_merchantIdFromContext() {
        WebhookSubscription sub = createSampleSubscription(1L);
        when(subscriptionService.createSubscription(eq(MERCHANT_ID), anyString(), any(), any()))
                .thenReturn(sub);

        WebhookSubscriptionController.CreateSubscriptionRequest request =
                new WebhookSubscriptionController.CreateSubscriptionRequest();
        request.setTargetUrl("https://merchant.example.com/hook");
        request.setEventTypes("PAYMENT_CONFIRMED");

        controller.create(request, httpRequest);

        // 验证 merchantId 从认证上下文获取，而非请求体
        verify(ownershipGuard).requireMerchantId(httpRequest);
        verify(subscriptionService).createSubscription(eq(MERCHANT_ID), anyString(), any(), any());
    }

    // --- 列出订阅 ---

    @Test
    @DisplayName("GET 列出订阅：返回当前租户的订阅列表")
    void list_success() {
        WebhookSubscription sub1 = createSampleSubscription(1L);
        WebhookSubscription sub2 = createSampleSubscription(2L);
        when(subscriptionService.listSubscriptions(MERCHANT_ID))
                .thenReturn(List.of(sub1, sub2));

        ResponseEntity<List<Map<String, Object>>> result = controller.list(httpRequest);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals(2, result.getBody().size());
    }

    // --- 查看详情 ---

    @Test
    @DisplayName("GET 查看订阅详情：返回订阅信息")
    void get_success() {
        WebhookSubscription sub = createSampleSubscription(1L);
        when(subscriptionService.findByIdAndMerchantId(1L, MERCHANT_ID))
                .thenReturn(sub);

        ResponseEntity<Map<String, Object>> result = controller.get(1L, httpRequest);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals(1L, result.getBody().get("id"));
    }

    @Test
    @DisplayName("GET 查看订阅详情：不属于该商户时抛异常")
    void get_notOwned() {
        when(subscriptionService.findByIdAndMerchantId(1L, MERCHANT_ID))
                .thenThrow(new IllegalArgumentException("订阅不存在或不属于该商户"));

        assertThrows(IllegalArgumentException.class,
                () -> controller.get(1L, httpRequest));
    }

    // --- 更新订阅 ---

    @Test
    @DisplayName("PUT 更新订阅：返回更新后的订阅")
    void update_success() {
        WebhookSubscription sub = createSampleSubscription(1L);
        sub.setTargetUrl("https://new.example.com/hook");
        when(subscriptionService.findByIdAndMerchantId(1L, MERCHANT_ID))
                .thenReturn(sub);
        when(subscriptionService.updateSubscription(eq(1L), anyString(), any(), any()))
                .thenReturn(sub);

        WebhookSubscriptionController.UpdateSubscriptionRequest request =
                new WebhookSubscriptionController.UpdateSubscriptionRequest();
        request.setTargetUrl("https://new.example.com/hook");
        request.setEventTypes("REFUND_ISSUED");

        ResponseEntity<Map<String, Object>> result = controller.update(1L, request, httpRequest);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("https://new.example.com/hook", result.getBody().get("target_url"));
    }

    // --- 暂停订阅 ---

    @Test
    @DisplayName("POST 暂停订阅：返回 PAUSED 状态的订阅")
    void pause_success() {
        WebhookSubscription sub = createSampleSubscription(1L);
        sub.setStatus(WebhookSubscriptionStatus.PAUSED);
        when(subscriptionService.findByIdAndMerchantId(1L, MERCHANT_ID))
                .thenReturn(sub);
        when(subscriptionService.pauseSubscription(1L))
                .thenReturn(sub);

        ResponseEntity<Map<String, Object>> result = controller.pause(1L, httpRequest);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("PAUSED", result.getBody().get("status"));
    }

    // --- 恢复订阅 ---

    @Test
    @DisplayName("POST 恢复订阅：返回 ACTIVE 状态的订阅")
    void resume_success() {
        WebhookSubscription sub = createSampleSubscription(1L);
        when(subscriptionService.findByIdAndMerchantId(1L, MERCHANT_ID))
                .thenReturn(sub);
        when(subscriptionService.resumeSubscription(1L))
                .thenReturn(sub);

        ResponseEntity<Map<String, Object>> result = controller.resume(1L, httpRequest);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("ACTIVE", result.getBody().get("status"));
    }

    // --- 删除订阅 ---

    @Test
    @DisplayName("DELETE 删除订阅：返回 204")
    void delete_success() {
        WebhookSubscription sub = createSampleSubscription(1L);
        when(subscriptionService.findByIdAndMerchantId(1L, MERCHANT_ID))
                .thenReturn(sub);
        doNothing().when(subscriptionService).deleteSubscription(1L);

        ResponseEntity<Void> result = controller.delete(1L, httpRequest);

        assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode());
        verify(subscriptionService).deleteSubscription(1L);
    }

    // --- Helper ---

    private WebhookSubscription createSampleSubscription(Long id) {
        WebhookSubscription sub = new WebhookSubscription();
        sub.setId(id);
        sub.setMerchantId(MERCHANT_ID);
        sub.setTargetUrl("https://merchant.example.com/hook");
        sub.setEventTypes("PAYMENT_CONFIRMED,PAYMENT_FAILED");
        sub.setStatus(WebhookSubscriptionStatus.ACTIVE);
        sub.setSigningSecret("dGVzdF9zZWNyZXRfMTIzNDU2Nzg5MA==");
        sub.setDescription("测试订阅");
        sub.setRetryPolicy("{\"maxRetries\":3,\"backoff\":\"exponential\"}");
        sub.setCreatedAt(Instant.now());
        sub.setUpdatedAt(Instant.now());
        return sub;
    }
}