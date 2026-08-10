package org.nexus.gateway.orchestration.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InMemoryDeadLetterQueueService} 单元测试（P4-T5）。
 *
 * <p>验证死信队列核心逻辑：
 * <ul>
 *   <li>sendToDeadLetter：消息入队</li>
 *   <li>listMessages：列出不消费</li>
 *   <li>drainMessages：取出并清空</li>
 *   <li>size：队列大小</li>
 *   <li>DeadLetterMessage.fromRecord：从投递记录构造</li>
 * </ul>
 */
class DeadLetterQueueServiceTest {

    private InMemoryDeadLetterQueueService dlqService;

    @BeforeEach
    void setUp() {
        dlqService = new InMemoryDeadLetterQueueService();
    }

    private DeadLetterMessage sampleMessage(String deliveryId, String paymentId) {
        return new DeadLetterMessage(
                deliveryId, paymentId, 1001L,
                "https://merchant.example/webhook",
                "{\"event\":\"payment.succeeded\",\"payment_id\":\"" + paymentId + "\"}",
                "abc123signature",
                "Connection refused",
                8,
                Instant.now().minusSeconds(300),
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("sendToDeadLetter: 消息入队，size 递增")
    void sendToDeadLetter_messageEnqueued() {
        assertEquals(0, dlqService.size());
        dlqService.sendToDeadLetter(sampleMessage("d1", "p1"));
        assertEquals(1, dlqService.size());
        dlqService.sendToDeadLetter(sampleMessage("d2", "p2"));
        assertEquals(2, dlqService.size());
    }

    @Test
    @DisplayName("sendToDeadLetter: null 消息抛 IllegalArgumentException")
    void sendToDeadLetter_nullThrows() {
        assertThrows(IllegalArgumentException.class, () -> dlqService.sendToDeadLetter(null));
    }

    @Test
    @DisplayName("listMessages: 返回所有消息但不消费")
    void listMessages_doesNotConsume() {
        dlqService.sendToDeadLetter(sampleMessage("d1", "p1"));
        dlqService.sendToDeadLetter(sampleMessage("d2", "p2"));

        List<DeadLetterMessage> messages = dlqService.listMessages();
        assertEquals(2, messages.size());
        assertEquals(2, dlqService.size(), "listMessages should not consume");
    }

    @Test
    @DisplayName("drainMessages: 取出并清空队列")
    void drainMessages_emptiesQueue() {
        dlqService.sendToDeadLetter(sampleMessage("d1", "p1"));
        dlqService.sendToDeadLetter(sampleMessage("d2", "p2"));
        dlqService.sendToDeadLetter(sampleMessage("d3", "p3"));

        List<DeadLetterMessage> drained = dlqService.drainMessages();
        assertEquals(3, drained.size());
        assertEquals(0, dlqService.size(), "Queue should be empty after drain");

        // 再次 drain 返回空
        assertTrue(dlqService.drainMessages().isEmpty());
    }

    @Test
    @DisplayName("listMessages: 空队列返回空列表")
    void listMessages_emptyQueue() {
        assertTrue(dlqService.listMessages().isEmpty());
    }

    @Test
    @DisplayName("DeadLetterMessage.fromRecord: 从投递记录构造死信消息")
    void fromRecord_constructsMessage() {
        WebhookDeliveryRecord record = new WebhookDeliveryRecord();
        record.setDeliveryId("delivery_001");
        record.setPaymentId("pay_001");
        record.setMerchantId(1001L);
        record.setNotifyUrl("https://merchant.example/webhook");
        record.setPayload("{\"event\":\"payment.succeeded\"}");
        record.setSignature("sig123");
        record.setAttemptCount(8);
        record.setCreatedAt(Instant.now().minusSeconds(600));
        record.setLastAttemptAt(Instant.now().minusSeconds(10));
        record.setLastError("Connection timeout");

        DeadLetterMessage msg = DeadLetterMessage.fromRecord(record, "Connection timeout");

        assertEquals("delivery_001", msg.getDeliveryId());
        assertEquals("pay_001", msg.getPaymentId());
        assertEquals(1001L, msg.getMerchantId());
        assertEquals("https://merchant.example/webhook", msg.getNotifyUrl());
        assertEquals("{\"event\":\"payment.succeeded\"}", msg.getPayload());
        assertEquals("sig123", msg.getSignature());
        assertEquals("Connection timeout", msg.getFailureReason());
        assertEquals(8, msg.getRetryCount());
        assertNotNull(msg.getFirstAttemptAt());
        assertNotNull(msg.getLastAttemptAt());
        assertNotNull(msg.getDeadLetteredAt());
    }

    @Test
    @DisplayName("DeadLetterMessage: 无参构造器 + setter（Jackson 反序列化）")
    void deadLetterMessage_noArgConstructor() {
        DeadLetterMessage msg = new DeadLetterMessage();
        msg.setDeliveryId("d1");
        msg.setPaymentId("p1");
        msg.setMerchantId(1L);
        msg.setFailureReason("error");
        msg.setRetryCount(3);

        assertEquals("d1", msg.getDeliveryId());
        assertEquals("p1", msg.getPaymentId());
        assertEquals(1L, msg.getMerchantId());
        assertEquals("error", msg.getFailureReason());
        assertEquals(3, msg.getRetryCount());
    }
}