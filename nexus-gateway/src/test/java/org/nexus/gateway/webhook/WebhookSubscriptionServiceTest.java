package org.nexus.gateway.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link WebhookSubscriptionService} 单元测试。
 *
 * <p>验证订阅管理核心逻辑：
 * <ul>
 *   <li>创建订阅：生成 signingSecret、状态 ACTIVE、默认重试策略</li>
 *   <li>更新订阅：targetUrl/eventTypes/description 更新</li>
 *   <li>暂停订阅：ACTIVE → PAUSED</li>
 *   <li>恢复订阅：PAUSED → ACTIVE</li>
 *   <li>删除订阅：软删除标记 DELETED</li>
 *   <li>查询订阅：listSubscriptions、getActiveSubscriptions</li>
 *   <li>异常场景：不存在、已删除、已暂停/活跃</li>
 * </ul>
 */
class WebhookSubscriptionServiceTest {

    private WebhookSubscriptionRepository repository;
    private WebhookUrlValidator urlValidator;
    private WebhookSubscriptionService service;

    private static final Long MERCHANT_ID = 1001L;
    private static final String TARGET_URL = "https://merchant.example.com/webhook";

    @BeforeEach
    void setUp() {
        repository = mock(WebhookSubscriptionRepository.class);
        urlValidator = mock(WebhookUrlValidator.class);
        service = new WebhookSubscriptionService(repository, urlValidator);

        // repository.save 默认返回原对象（模拟 JPA save 行为）
        when(repository.save(any(WebhookSubscription.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // --- 创建订阅 ---

    @Test
    @DisplayName("创建订阅：生成 signingSecret，状态 ACTIVE，默认重试策略")
    void createSubscription_success() {
        Set<WebhookEventType> eventTypes = EnumSet.of(
                WebhookEventType.PAYMENT_CONFIRMED, WebhookEventType.PAYMENT_FAILED);

        WebhookSubscription result = service.createSubscription(
                MERCHANT_ID, TARGET_URL, eventTypes, "支付通知订阅");

        assertNotNull(result);
        assertEquals(MERCHANT_ID, result.getMerchantId());
        assertEquals(TARGET_URL, result.getTargetUrl());
        assertEquals("PAYMENT_CONFIRMED,PAYMENT_FAILED", result.getEventTypes());
        assertEquals(WebhookSubscriptionStatus.ACTIVE, result.getStatus());
        assertNotNull(result.getSigningSecret());
        assertFalse(result.getSigningSecret().isBlank());
        assertEquals("支付通知订阅", result.getDescription());
        assertEquals("{\"maxRetries\":3,\"backoff\":\"exponential\"}", result.getRetryPolicy());
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());

        // 验证 URL 校验被调用
        verify(urlValidator).validate(TARGET_URL);
        verify(repository).save(any(WebhookSubscription.class));
    }

    @Test
    @DisplayName("创建订阅：merchantId 为空时抛异常")
    void createSubscription_nullMerchantId() {
        Set<WebhookEventType> eventTypes = EnumSet.of(WebhookEventType.PAYMENT_CONFIRMED);
        assertThrows(IllegalArgumentException.class,
                () -> service.createSubscription(null, TARGET_URL, eventTypes, "desc"));
    }

    @Test
    @DisplayName("创建订阅：targetUrl 为空时抛异常")
    void createSubscription_blankTargetUrl() {
        Set<WebhookEventType> eventTypes = EnumSet.of(WebhookEventType.PAYMENT_CONFIRMED);
        assertThrows(IllegalArgumentException.class,
                () -> service.createSubscription(MERCHANT_ID, "", eventTypes, "desc"));
    }

    @Test
    @DisplayName("创建订阅：eventTypes 为空时抛异常")
    void createSubscription_emptyEventTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createSubscription(MERCHANT_ID, TARGET_URL, EnumSet.noneOf(WebhookEventType.class), "desc"));
    }

    @Test
    @DisplayName("创建订阅：URL 校验失败时抛异常")
    void createSubscription_invalidUrl() {
        doThrow(new IllegalArgumentException("Webhook URL 指向内网地址"))
                .when(urlValidator).validate("http://localhost:8080/hook");

        Set<WebhookEventType> eventTypes = EnumSet.of(WebhookEventType.PAYMENT_CONFIRMED);
        assertThrows(IllegalArgumentException.class,
                () -> service.createSubscription(MERCHANT_ID, "http://localhost:8080/hook", eventTypes, "desc"));
    }

    // --- 更新订阅 ---

    @Test
    @DisplayName("更新订阅：更新 targetUrl 和 eventTypes")
    void updateSubscription_success() {
        WebhookSubscription existing = createSampleSubscription(1L, WebhookSubscriptionStatus.ACTIVE);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        Set<WebhookEventType> newEventTypes = EnumSet.of(WebhookEventType.REFUND_ISSUED);
        WebhookSubscription result = service.updateSubscription(
                1L, "https://new.example.com/hook", newEventTypes, "新描述");

        assertEquals("https://new.example.com/hook", result.getTargetUrl());
        assertEquals("REFUND_ISSUED", result.getEventTypes());
        assertEquals("新描述", result.getDescription());
        // signingSecret 不应改变
        assertEquals(existing.getSigningSecret(), result.getSigningSecret());
        verify(urlValidator).validate("https://new.example.com/hook");
    }

    @Test
    @DisplayName("更新订阅：订阅不存在时抛异常")
    void updateSubscription_notFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.updateSubscription(999L, TARGET_URL, EnumSet.of(WebhookEventType.PAYMENT_CONFIRMED), "desc"));
    }

    @Test
    @DisplayName("更新订阅：已删除的订阅无法更新")
    void updateSubscription_deleted() {
        WebhookSubscription deleted = createSampleSubscription(1L, WebhookSubscriptionStatus.DELETED);
        when(repository.findById(1L)).thenReturn(Optional.of(deleted));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateSubscription(1L, TARGET_URL, EnumSet.of(WebhookEventType.PAYMENT_CONFIRMED), "desc"));
    }

    // --- 暂停订阅 ---

    @Test
    @DisplayName("暂停订阅：ACTIVE → PAUSED")
    void pauseSubscription_success() {
        WebhookSubscription active = createSampleSubscription(1L, WebhookSubscriptionStatus.ACTIVE);
        when(repository.findById(1L)).thenReturn(Optional.of(active));

        WebhookSubscription result = service.pauseSubscription(1L);
        assertEquals(WebhookSubscriptionStatus.PAUSED, result.getStatus());
    }

    @Test
    @DisplayName("暂停订阅：已暂停时抛异常")
    void pauseSubscription_alreadyPaused() {
        WebhookSubscription paused = createSampleSubscription(1L, WebhookSubscriptionStatus.PAUSED);
        when(repository.findById(1L)).thenReturn(Optional.of(paused));
        assertThrows(IllegalArgumentException.class, () -> service.pauseSubscription(1L));
    }

    @Test
    @DisplayName("暂停订阅：已删除时抛异常")
    void pauseSubscription_deleted() {
        WebhookSubscription deleted = createSampleSubscription(1L, WebhookSubscriptionStatus.DELETED);
        when(repository.findById(1L)).thenReturn(Optional.of(deleted));
        assertThrows(IllegalArgumentException.class, () -> service.pauseSubscription(1L));
    }

    // --- 恢复订阅 ---

    @Test
    @DisplayName("恢复订阅：PAUSED → ACTIVE")
    void resumeSubscription_success() {
        WebhookSubscription paused = createSampleSubscription(1L, WebhookSubscriptionStatus.PAUSED);
        when(repository.findById(1L)).thenReturn(Optional.of(paused));

        WebhookSubscription result = service.resumeSubscription(1L);
        assertEquals(WebhookSubscriptionStatus.ACTIVE, result.getStatus());
    }

    @Test
    @DisplayName("恢复订阅：已活跃时抛异常")
    void resumeSubscription_alreadyActive() {
        WebhookSubscription active = createSampleSubscription(1L, WebhookSubscriptionStatus.ACTIVE);
        when(repository.findById(1L)).thenReturn(Optional.of(active));
        assertThrows(IllegalArgumentException.class, () -> service.resumeSubscription(1L));
    }

    @Test
    @DisplayName("恢复订阅：已删除时抛异常")
    void resumeSubscription_deleted() {
        WebhookSubscription deleted = createSampleSubscription(1L, WebhookSubscriptionStatus.DELETED);
        when(repository.findById(1L)).thenReturn(Optional.of(deleted));
        assertThrows(IllegalArgumentException.class, () -> service.resumeSubscription(1L));
    }

    // --- 删除订阅 ---

    @Test
    @DisplayName("删除订阅：软删除标记 DELETED")
    void deleteSubscription_success() {
        WebhookSubscription active = createSampleSubscription(1L, WebhookSubscriptionStatus.ACTIVE);
        when(repository.findById(1L)).thenReturn(Optional.of(active));

        service.deleteSubscription(1L);
        assertEquals(WebhookSubscriptionStatus.DELETED, active.getStatus());
        verify(repository).save(active);
    }

    @Test
    @DisplayName("删除订阅：已删除时抛异常")
    void deleteSubscription_alreadyDeleted() {
        WebhookSubscription deleted = createSampleSubscription(1L, WebhookSubscriptionStatus.DELETED);
        when(repository.findById(1L)).thenReturn(Optional.of(deleted));
        assertThrows(IllegalArgumentException.class, () -> service.deleteSubscription(1L));
    }

    // --- 查询订阅 ---

    @Test
    @DisplayName("listSubscriptions：返回商户的非删除订阅")
    void listSubscriptions_success() {
        WebhookSubscription active = createSampleSubscription(1L, WebhookSubscriptionStatus.ACTIVE);
        WebhookSubscription paused = createSampleSubscription(2L, WebhookSubscriptionStatus.PAUSED);
        when(repository.findByMerchantIdAndStatusNot(MERCHANT_ID, WebhookSubscriptionStatus.DELETED))
                .thenReturn(List.of(active, paused));

        List<WebhookSubscription> result = service.listSubscriptions(MERCHANT_ID);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("getActiveSubscriptions：返回匹配事件类型的活跃订阅")
    void getActiveSubscriptions_success() {
        WebhookSubscription sub1 = createSampleSubscription(1L, WebhookSubscriptionStatus.ACTIVE);
        sub1.setEventTypes("PAYMENT_CONFIRMED,PAYMENT_FAILED");
        WebhookSubscription sub2 = createSampleSubscription(2L, WebhookSubscriptionStatus.ACTIVE);
        sub2.setEventTypes("REFUND_ISSUED");
        when(repository.findByMerchantIdAndStatus(MERCHANT_ID, WebhookSubscriptionStatus.ACTIVE))
                .thenReturn(List.of(sub1, sub2));

        List<WebhookSubscription> result = service.getActiveSubscriptions(MERCHANT_ID, WebhookEventType.PAYMENT_CONFIRMED);
        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    @DisplayName("getActiveSubscriptions：无匹配订阅时返回空列表")
    void getActiveSubscriptions_noMatch() {
        WebhookSubscription sub1 = createSampleSubscription(1L, WebhookSubscriptionStatus.ACTIVE);
        sub1.setEventTypes("REFUND_ISSUED");
        when(repository.findByMerchantIdAndStatus(MERCHANT_ID, WebhookSubscriptionStatus.ACTIVE))
                .thenReturn(List.of(sub1));

        List<WebhookSubscription> result = service.getActiveSubscriptions(MERCHANT_ID, WebhookEventType.PAYMENT_CONFIRMED);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("findById：订阅不存在时抛异常")
    void findById_notFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.findById(999L));
    }

    @Test
    @DisplayName("findByIdAndMerchantId：不属于该商户时抛异常")
    void findByIdAndMerchantId_notOwned() {
        when(repository.findByIdAndMerchantId(1L, 999L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.findByIdAndMerchantId(1L, 999L));
    }

    // --- isSubscribedTo ---

    @Test
    @DisplayName("isSubscribedTo：正确匹配事件类型")
    void isSubscribedTo_match() {
        WebhookSubscription sub = createSampleSubscription(1L, WebhookSubscriptionStatus.ACTIVE);
        sub.setEventTypes("PAYMENT_CONFIRMED,PAYMENT_FAILED");

        assertTrue(sub.isSubscribedTo(WebhookEventType.PAYMENT_CONFIRMED));
        assertTrue(sub.isSubscribedTo(WebhookEventType.PAYMENT_FAILED));
        assertFalse(sub.isSubscribedTo(WebhookEventType.REFUND_ISSUED));
    }

    // --- Helper ---

    private WebhookSubscription createSampleSubscription(Long id, WebhookSubscriptionStatus status) {
        WebhookSubscription sub = new WebhookSubscription();
        sub.setId(id);
        sub.setMerchantId(MERCHANT_ID);
        sub.setTargetUrl(TARGET_URL);
        sub.setEventTypes("PAYMENT_CONFIRMED");
        sub.setStatus(status);
        sub.setSigningSecret("dGVzdF9zZWNyZXRfMTIzNDU2Nzg5MA==");
        sub.setDescription("测试订阅");
        sub.setRetryPolicy("{\"maxRetries\":3,\"backoff\":\"exponential\"}");
        sub.setCreatedAt(Instant.now());
        sub.setUpdatedAt(Instant.now());
        return sub;
    }
}