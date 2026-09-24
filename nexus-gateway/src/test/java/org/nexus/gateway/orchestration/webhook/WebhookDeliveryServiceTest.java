package org.nexus.gateway.orchestration.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.nexus.gateway.webhook.WebhookUrlValidator;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link WebhookDeliveryService} 单元测试（P4-T5 / Wave 8-A5 增强）。
 *
 * <p>验证核心投递流程：
 * <ul>
 *   <li>成功投递：状态 DELIVERED</li>
 *   <li>重试耗尽：状态 DEAD_LETTER，发送到 DLQ</li>
 *   <li>幂等去重：同一 paymentId + statusEvent 不重复投递</li>
 *   <li>Wave 8-A5 幂等校验：基于 deliveryId + eventId 去重</li>
 *   <li>Wave 8-A5 At-least-once：投递失败后自动触发重试</li>
 *   <li>Wave 8-A5 retryDelivery：手动重试入口</li>
 *   <li>空 notifyUrl：跳过投递</li>
 *   <li>HMAC 签名：请求头携带 X-NexusChain-Signature</li>
 *   <li>重投：从死信消息重新投递</li>
 * </ul>
 *
 * <p>使用 Mock RestTemplate + 内存 DLQ + Mock Repository，不依赖 Spring 上下文。
 * 重试延迟通过 Mock WebhookRetryService 控制（awaitRetry 立即返回）。
 */
class WebhookDeliveryServiceTest {

    private WebhookDeliveryRepository repository;
    private WebhookRetryService retryService;
    private WebhookSignatureService signatureService;
    private InMemoryDeadLetterQueueService dlqService;
    private RestTemplate restTemplate;
    private WebhookDeliveryService deliveryService;

    private static final String SECRET = "test_webhook_secret";

    @BeforeEach
    void setUp() {
        repository = mock(WebhookDeliveryRepository.class);
        retryService = mock(WebhookRetryService.class);
        signatureService = new WebhookSignatureService();
        dlqService = new InMemoryDeadLetterQueueService();
        restTemplate = mock(RestTemplate.class);

        // retryService 默认配置：max 5 次，awaitRetry 立即返回（不阻塞测试）
        when(retryService.getMaxRetries()).thenReturn(5);
        when(retryService.awaitRetry(anyInt())).thenReturn(true);

        // repository.save 默认返回原记录（模拟 JPA save）
        when(repository.save(any(WebhookDeliveryRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        deliveryService = new WebhookDeliveryService(
                repository, retryService, signatureService, dlqService,
                restTemplate, SECRET, mock(WebhookUrlValidator.class));
    }

    private Map<String, Object> samplePayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", "payment.succeeded");
        m.put("payment_id", "pay_001");
        m.put("amount", 10000L);
        m.put("currency", "NEX");
        return m;
    }

    @Test
    @DisplayName("deliver: 成功投递，状态 DELIVERED")
    void deliver_success_statusDelivered() {
        when(repository.findByPaymentIdAndStatus(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        WebhookDeliveryRecord result = deliveryService.deliver(
                "pay_001", 1001L, "https://merchant.example/webhook",
                samplePayload(), "payment.succeeded");

        assertNotNull(result);
        assertEquals(WebhookDeliveryStatus.DELIVERED, result.getStatus());
        assertEquals(0, result.getAttemptCount(), "First attempt should be 0");
        assertNotNull(result.getDeliveredAt());
        assertEquals(0, dlqService.size(), "No dead letter on success");
    }

    @Test
    @DisplayName("deliver: 携带 v2 X-NexusChain-Signature 请求头（绑定 deliveryId+timestamp）")
    void deliver_carriesSignatureHeader() {
        when(repository.findByPaymentIdAndStatus(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        Map<String, Object> payload = samplePayload();
        deliveryService.deliver("pay_001", 1001L, "https://merchant.example/webhook",
                payload, "payment.succeeded");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), any(HttpMethod.class), captor.capture(), any(Class.class));

        HttpEntity<Map<String, Object>> entity = captor.getValue();
        String signature = entity.getHeaders().getFirst(WebhookSignatureService.SIGNATURE_HEADER);
        assertNotNull(signature, "Signature header should be present");
        assertTrue(signature.startsWith("v2:"), "短期项 #4c：投递签名应为 v2（绑定 deliveryId+timestamp）");

        String deliveryId = entity.getHeaders().getFirst(WebhookSignatureService.DELIVERY_ID_HEADER);
        String timestamp = entity.getHeaders().getFirst(WebhookSignatureService.TIMESTAMP_HEADER);
        assertNotNull(deliveryId, "v2 签名必须携带 Delivery-Id 头");
        assertNotNull(timestamp, "v2 签名必须携带 Timestamp 头");
        String expectedSig = signatureService.signV2(payload, SECRET, deliveryId, timestamp);
        assertEquals(expectedSig, signature, "v2 签名应与按头重算结果一致");
    }

    @Test
    @DisplayName("deliver: 重试耗尽后转入死信队列，状态 DEAD_LETTER")
    void deliver_retryExhausted_deadLettered() {
        when(repository.findByPaymentIdAndStatus(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        WebhookDeliveryRecord result = deliveryService.deliver(
                "pay_002", 1001L, "https://merchant.example/webhook",
                samplePayload(), "payment.succeeded");

        assertNotNull(result);
        assertEquals(WebhookDeliveryStatus.DEAD_LETTER, result.getStatus());
        assertEquals(5, result.getAttemptCount(), "Should attempt 6 times (0-5)");
        assertNotNull(result.getDeadLetteredAt());
        assertEquals(1, dlqService.size(), "Should send 1 message to DLQ");

        DeadLetterMessage dlqMsg = dlqService.listMessages().get(0);
        assertEquals("pay_002", dlqMsg.getPaymentId());
        assertEquals(5, dlqMsg.getRetryCount());
        assertNotNull(dlqMsg.getFailureReason());
    }

    @Test
    @DisplayName("deliver: 幂等去重，同一 paymentId+statusEvent 不重复投递")
    void deliver_dedup_samePaymentAndStatus() {
        WebhookDeliveryRecord existing = new WebhookDeliveryRecord();
        existing.setDeliveryId("existing_001");
        existing.setPaymentId("pay_003");
        existing.setStatus(WebhookDeliveryStatus.DELIVERED);
        when(repository.findByPaymentIdAndStatus(eq("pay_003"), eq("payment.succeeded")))
                .thenReturn(Optional.of(existing));

        WebhookDeliveryRecord result = deliveryService.deliver(
                "pay_003", 1001L, "https://merchant.example/webhook",
                samplePayload(), "payment.succeeded");

        assertEquals("existing_001", result.getDeliveryId(), "Should return existing record");
        verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class));
    }

    @Test
    @DisplayName("deliver: 空 notifyUrl 返回 null，不投递")
    void deliver_emptyNotifyUrl_returnsNull() {
        WebhookDeliveryRecord result = deliveryService.deliver(
                "pay_004", 1001L, null, samplePayload(), "payment.succeeded");
        assertNull(result);
        verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class));

        result = deliveryService.deliver(
                "pay_004", 1001L, "  ", samplePayload(), "payment.succeeded");
        assertNull(result);
    }

    @Test
    @DisplayName("deliver: 重试过程中成功，状态 DELIVERED")
    void deliver_retryThenSuccess_statusDelivered() {
        when(repository.findByPaymentIdAndStatus(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenThrow(new RuntimeException("Connection refused")) // attempt 0 fails
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK)); // attempt 1 succeeds

        WebhookDeliveryRecord result = deliveryService.deliver(
                "pay_005", 1001L, "https://merchant.example/webhook",
                samplePayload(), "payment.succeeded");

        assertNotNull(result);
        assertEquals(WebhookDeliveryStatus.DELIVERED, result.getStatus());
        assertEquals(1, result.getAttemptCount(), "Should succeed on attempt 1");
        assertEquals(0, dlqService.size(), "No dead letter on retry success");
    }

    @Test
    @DisplayName("getDelivery: 查询投递记录")
    void getDelivery_returnsRecord() {
        WebhookDeliveryRecord record = new WebhookDeliveryRecord();
        record.setDeliveryId("d1");
        record.setStatus(WebhookDeliveryStatus.DELIVERED);
        when(repository.findById("d1")).thenReturn(Optional.of(record));

        WebhookDeliveryRecord result = deliveryService.getDelivery("d1");
        assertNotNull(result);
        assertEquals("d1", result.getDeliveryId());
    }

    @Test
    @DisplayName("getDelivery: 不存在返回 null")
    void getDelivery_notFoundReturnsNull() {
        when(repository.findById("nonexistent")).thenReturn(Optional.empty());
        assertNull(deliveryService.getDelivery("nonexistent"));
    }

    @Test
    @DisplayName("replay: 从死信消息重投成功")
    void replay_success() {
        DeadLetterMessage dlqMsg = new DeadLetterMessage(
                "delivery_replay_001", "pay_replay", 1001L,
                "https://merchant.example/webhook",
                "{\"event\":\"payment.succeeded\",\"payment_id\":\"pay_replay\",\"amount\":10000,\"currency\":\"NEX\"}",
                "sig123",
                "Connection refused",
                5,
                Instant.now().minusSeconds(300),
                Instant.now(),
                Instant.now()
        );

        when(repository.findById("delivery_replay_001")).thenReturn(Optional.empty());
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        WebhookDeliveryRecord result = deliveryService.replay(dlqMsg);

        assertNotNull(result);
        assertEquals(WebhookDeliveryStatus.DELIVERED, result.getStatus());
    }

    @Test
    @DisplayName("replay: null 消息抛 IllegalArgumentException")
    void replay_nullThrows() {
        assertThrows(IllegalArgumentException.class, () -> deliveryService.replay(null));
    }

    @Test
    @DisplayName("deliver: 非 2xx 响应触发重试")
    void deliver_non2xxTriggersRetry() {
        when(repository.findByPaymentIdAndStatus(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(new ResponseEntity<>("error", HttpStatus.INTERNAL_SERVER_ERROR));

        WebhookDeliveryRecord result = deliveryService.deliver(
                "pay_006", 1001L, "https://merchant.example/webhook",
                samplePayload(), "payment.succeeded");

        assertEquals(WebhookDeliveryStatus.DEAD_LETTER, result.getStatus());
        assertNotNull(result.getLastError());
        assertTrue(result.getLastError().contains("500"));
    }

    // ─── Wave 8-A5 新增测试：幂等校验 + At-least-once ──────────────────────

    @Test
    @DisplayName("isAlreadyDelivered: 已成功投递的记录返回 true")
    void isAlreadyDelivered_alreadyDelivered_returnsTrue() {
        WebhookDeliveryRecord delivered = new WebhookDeliveryRecord();
        delivered.setDeliveryId("d1");
        delivered.setPaymentId("pay_001");
        delivered.setStatus(WebhookDeliveryStatus.DELIVERED);
        when(repository.findByPaymentIdAndStatus("pay_001", "payment.succeeded"))
                .thenReturn(Optional.of(delivered));

        assertTrue(deliveryService.isAlreadyDelivered("pay_001", "payment.succeeded"));
    }

    @Test
    @DisplayName("isAlreadyDelivered: 未投递的记录返回 false")
    void isAlreadyDelivered_notDelivered_returnsFalse() {
        when(repository.findByPaymentIdAndStatus("pay_001", "payment.succeeded"))
                .thenReturn(Optional.empty());

        assertFalse(deliveryService.isAlreadyDelivered("pay_001", "payment.succeeded"));
    }

    @Test
    @DisplayName("isAlreadyDelivered: RETRYING 状态的记录返回 false（未成功投递）")
    void isAlreadyDelivered_retryingStatus_returnsFalse() {
        WebhookDeliveryRecord retrying = new WebhookDeliveryRecord();
        retrying.setDeliveryId("d1");
        retrying.setPaymentId("pay_001");
        retrying.setStatus(WebhookDeliveryStatus.RETRYING);
        when(repository.findByPaymentIdAndStatus("pay_001", "payment.succeeded"))
                .thenReturn(Optional.of(retrying));

        assertFalse(deliveryService.isAlreadyDelivered("pay_001", "payment.succeeded"));
    }

    @Test
    @DisplayName("isAlreadyDelivered: DEAD_LETTER 状态的记录返回 false（未成功投递）")
    void isAlreadyDelivered_deadLetterStatus_returnsFalse() {
        WebhookDeliveryRecord deadLetter = new WebhookDeliveryRecord();
        deadLetter.setDeliveryId("d1");
        deadLetter.setPaymentId("pay_001");
        deadLetter.setStatus(WebhookDeliveryStatus.DEAD_LETTER);
        when(repository.findByPaymentIdAndStatus("pay_001", "payment.succeeded"))
                .thenReturn(Optional.of(deadLetter));

        assertFalse(deliveryService.isAlreadyDelivered("pay_001", "payment.succeeded"));
    }

    @Test
    @DisplayName("deliver: 幂等校验跳过已成功投递的 eventId")
    void deliver_idempotentSkip_alreadyDeliveredEvent() {
        // 第一次查询（findByPaymentIdAndStatus）返回空（没有已投递记录）
        // 但第二次查询（幂等校验 isAlreadyDelivered）返回 DELIVERED 记录
        WebhookDeliveryRecord delivered = new WebhookDeliveryRecord();
        delivered.setDeliveryId("d_existing");
        delivered.setPaymentId("pay_007");
        delivered.setStatus(WebhookDeliveryStatus.DELIVERED);

        when(repository.findByPaymentIdAndStatus(eq("pay_007"), eq("payment.succeeded")))
                .thenReturn(Optional.empty())       // 第一次：去重检查（没有已投递记录）
                .thenReturn(Optional.of(delivered)); // 第二次：幂等校验（发现已成功投递）

        WebhookDeliveryRecord result = deliveryService.deliver(
                "pay_007", 1001L, "https://merchant.example/webhook",
                samplePayload(), "payment.succeeded");

        assertNull(result, "Should skip delivery when already delivered (idempotent)");
        verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class));
    }

    @Test
    @DisplayName("retryDelivery: 手动重试失败投递，成功后状态 DELIVERED")
    void retryDelivery_success() {
        WebhookDeliveryRecord failedRecord = new WebhookDeliveryRecord();
        failedRecord.setDeliveryId("d_retry_001");
        failedRecord.setPaymentId("pay_retry");
        failedRecord.setMerchantId(1001L);
        failedRecord.setNotifyUrl("https://merchant.example/webhook");
        failedRecord.setPayload("{\"event\":\"payment.succeeded\",\"payment_id\":\"pay_retry\"}");
        failedRecord.setSignature("sig123");
        failedRecord.setStatus(WebhookDeliveryStatus.RETRYING);
        failedRecord.setAttemptCount(2);

        when(repository.findById("d_retry_001")).thenReturn(Optional.of(failedRecord));
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        WebhookDeliveryRecord result = deliveryService.retryDelivery("d_retry_001");

        assertNotNull(result);
        assertEquals(WebhookDeliveryStatus.DELIVERED, result.getStatus());
    }

    @Test
    @DisplayName("retryDelivery: 记录不存在返回 null")
    void retryDelivery_notFound_returnsNull() {
        when(repository.findById("nonexistent")).thenReturn(Optional.empty());

        assertNull(deliveryService.retryDelivery("nonexistent"));
    }

    @Test
    @DisplayName("retryDelivery: 已 DELIVERED 的记录跳过重试")
    void retryDelivery_alreadyDelivered_skips() {
        WebhookDeliveryRecord delivered = new WebhookDeliveryRecord();
        delivered.setDeliveryId("d_delivered");
        delivered.setStatus(WebhookDeliveryStatus.DELIVERED);

        when(repository.findById("d_delivered")).thenReturn(Optional.of(delivered));

        WebhookDeliveryRecord result = deliveryService.retryDelivery("d_delivered");

        assertNotNull(result);
        assertEquals(WebhookDeliveryStatus.DELIVERED, result.getStatus());
        verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class));
    }

    @Test
    @DisplayName("retryDelivery: 重试耗尽后转入死信队列")
    void retryDelivery_retryExhausted_deadLettered() {
        WebhookDeliveryRecord failedRecord = new WebhookDeliveryRecord();
        failedRecord.setDeliveryId("d_retry_fail");
        failedRecord.setPaymentId("pay_retry_fail");
        failedRecord.setMerchantId(1001L);
        failedRecord.setNotifyUrl("https://merchant.example/webhook");
        failedRecord.setPayload("{\"event\":\"payment.succeeded\",\"payment_id\":\"pay_retry_fail\"}");
        failedRecord.setSignature("sig123");
        failedRecord.setStatus(WebhookDeliveryStatus.RETRYING);
        failedRecord.setAttemptCount(2);

        when(repository.findById("d_retry_fail")).thenReturn(Optional.of(failedRecord));
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        WebhookDeliveryRecord result = deliveryService.retryDelivery("d_retry_fail");

        assertNotNull(result);
        assertEquals(WebhookDeliveryStatus.DEAD_LETTER, result.getStatus());
        assertEquals(1, dlqService.size(), "Should send to DLQ");
    }
}
