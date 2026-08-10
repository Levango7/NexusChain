package org.nexus.gateway.orchestration.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link WebhookDeliveryController} 单元测试（P4-T5）。
 *
 * <p>验证投递管理 API：
 * <ul>
 *   <li>GET /api/v1/webhooks/deliveries/{id} - 查询投递状态</li>
 *   <li>GET /api/v1/webhooks/deliveries - 分页查询</li>
 *   <li>GET /api/v1/webhooks/payments/{paymentId}/deliveries - 按支付查询</li>
 *   <li>POST /api/v1/webhooks/dlq/replay - 手动重投</li>
 *   <li>GET /api/v1/webhooks/dlq/messages - 列出 DLQ 消息</li>
 * </ul>
 */
class WebhookDeliveryControllerTest {

    private WebhookDeliveryService deliveryService;
    private WebhookDeliveryRepository repository;
    private InMemoryDeadLetterQueueService inMemoryDlq;
    private ObjectProvider<InMemoryDeadLetterQueueService> dlqProvider;
    private WebhookDeliveryController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        deliveryService = mock(WebhookDeliveryService.class);
        repository = mock(WebhookDeliveryRepository.class);
        inMemoryDlq = new InMemoryDeadLetterQueueService();
        dlqProvider = mock(ObjectProvider.class);
        when(dlqProvider.getIfAvailable()).thenReturn(inMemoryDlq);

        controller = new WebhookDeliveryController(deliveryService, repository, dlqProvider);
    }

    private WebhookDeliveryRecord sampleRecord(String deliveryId, WebhookDeliveryStatus status) {
        WebhookDeliveryRecord r = new WebhookDeliveryRecord();
        r.setDeliveryId(deliveryId);
        r.setPaymentId("pay_001");
        r.setMerchantId(1001L);
        r.setNotifyUrl("https://merchant.example/webhook");
        r.setPayload("{\"event\":\"payment.succeeded\"}");
        r.setStatus(status);
        r.setAttemptCount(1);
        r.setCreatedAt(Instant.now());
        return r;
    }

    @Test
    @DisplayName("GET /deliveries/{id}: 存在时返回 200 + 投递详情")
    void getDelivery_found() {
        WebhookDeliveryRecord record = sampleRecord("d1", WebhookDeliveryStatus.DELIVERED);
        when(deliveryService.getDelivery("d1")).thenReturn(record);

        ResponseEntity<Map<String, Object>> resp = controller.getDelivery("d1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("d1", resp.getBody().get("delivery_id"));
        assertEquals("DELIVERED", resp.getBody().get("status"));
        assertEquals("pay_001", resp.getBody().get("payment_id"));
    }

    @Test
    @DisplayName("GET /deliveries/{id}: 不存在时返回 404")
    void getDelivery_notFound() {
        when(deliveryService.getDelivery("nonexistent")).thenReturn(null);

        ResponseEntity<Map<String, Object>> resp = controller.getDelivery("nonexistent");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    @DisplayName("GET /deliveries: 无过滤条件返回分页列表")
    void listDeliveries_noFilter() {
        WebhookDeliveryRecord r1 = sampleRecord("d1", WebhookDeliveryStatus.DELIVERED);
        WebhookDeliveryRecord r2 = sampleRecord("d2", WebhookDeliveryStatus.RETRYING);
        Page<WebhookDeliveryRecord> page = new PageImpl<>(List.of(r1, r2));
        when(repository.findAll(any(PageRequest.class))).thenReturn(page);

        ResponseEntity<Map<String, Object>> resp = controller.listDeliveries(null, null, 0, 20);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(2L, resp.getBody().get("total"));
        assertEquals(0, resp.getBody().get("page"));
        assertEquals(20, resp.getBody().get("size"));
    }

    @Test
    @DisplayName("GET /deliveries: 按商户 ID 过滤")
    void listDeliveries_byMerchant() {
        Page<WebhookDeliveryRecord> page = new PageImpl<>(Collections.emptyList());
        when(repository.findByMerchantId(eq(1001L), any(PageRequest.class))).thenReturn(page);

        ResponseEntity<Map<String, Object>> resp = controller.listDeliveries(1001L, null, 0, 20);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(0L, resp.getBody().get("total"));
    }

    @Test
    @DisplayName("GET /deliveries: 按状态过滤")
    void listDeliveries_byStatus() {
        Page<WebhookDeliveryRecord> page = new PageImpl<>(Collections.emptyList());
        when(repository.findByStatus(eq(WebhookDeliveryStatus.DEAD_LETTER), any(PageRequest.class)))
                .thenReturn(page);

        ResponseEntity<Map<String, Object>> resp = controller.listDeliveries(null, "DEAD_LETTER", 0, 20);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    @DisplayName("GET /payments/{paymentId}/deliveries: 返回该支付的所有投递记录")
    void getDeliveriesByPayment() {
        WebhookDeliveryRecord r = sampleRecord("d1", WebhookDeliveryStatus.DELIVERED);
        when(deliveryService.getByPaymentId("pay_001")).thenReturn(List.of(r));

        ResponseEntity<List<Map<String, Object>>> resp = controller.getDeliveriesByPayment("pay_001");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(1, resp.getBody().size());
        assertEquals("d1", resp.getBody().get(0).get("delivery_id"));
    }

    @Test
    @DisplayName("POST /dlq/replay: 通过 deliveryId 重投")
    void replay_byDeliveryId() {
        WebhookDeliveryRecord record = sampleRecord("d1", WebhookDeliveryStatus.DEAD_LETTER);
        record.setLastError("Connection refused");
        when(deliveryService.getDelivery("d1")).thenReturn(record);
        when(deliveryService.replay(any(DeadLetterMessage.class))).thenReturn(record);

        Map<String, Object> body = Map.of("deliveryId", "d1");
        ResponseEntity<Map<String, Object>> resp = controller.replay(body);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(1, resp.getBody().get("replayed"));
    }

    @Test
    @DisplayName("POST /dlq/replay: deliveryId 不存在返回 404")
    void replay_deliveryIdNotFound() {
        when(deliveryService.getDelivery("nonexistent")).thenReturn(null);

        Map<String, Object> body = Map.of("deliveryId", "nonexistent");
        ResponseEntity<Map<String, Object>> resp = controller.replay(body);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    @DisplayName("POST /dlq/replay: 通过 message 重投")
    void replay_byMessage() {
        WebhookDeliveryRecord record = sampleRecord("d1", WebhookDeliveryStatus.DELIVERED);
        when(deliveryService.replay(any(DeadLetterMessage.class))).thenReturn(record);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("delivery_id", "d1");
        message.put("payment_id", "pay_001");
        message.put("merchant_id", 1001L);
        message.put("notify_url", "https://merchant.example/webhook");
        message.put("payload", "{}");
        message.put("failure_reason", "error");
        message.put("retry_count", 8);
        Map<String, Object> body = Map.of("message", message);

        ResponseEntity<Map<String, Object>> resp = controller.replay(body);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().get("replayed"));
    }

    @Test
    @DisplayName("POST /dlq/replay: 无 body 且内存 DLQ 为空，返回 replayed=0")
    void replay_emptyDlq() {
        ResponseEntity<Map<String, Object>> resp = controller.replay(null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(0, resp.getBody().get("replayed"));
    }

    @Test
    @DisplayName("POST /dlq/replay: 无 body，重投所有内存 DLQ 消息")
    void replay_allFromMemoryDlq() {
        // 准备 DLQ 消息
        DeadLetterMessage msg = new DeadLetterMessage(
                "d1", "pay_001", 1001L,
                "https://merchant.example/webhook",
                "{}", "sig", "error", 8,
                Instant.now(), Instant.now(), Instant.now()
        );
        inMemoryDlq.sendToDeadLetter(msg);

        WebhookDeliveryRecord record = sampleRecord("d1", WebhookDeliveryStatus.DELIVERED);
        when(deliveryService.replay(any(DeadLetterMessage.class))).thenReturn(record);

        ResponseEntity<Map<String, Object>> resp = controller.replay(null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().get("replayed"));
        assertEquals(1, resp.getBody().get("total"));
    }

    @Test
    @DisplayName("GET /dlq/messages: 内存模式返回消息列表")
    void listDlqMessages_memoryMode() {
        DeadLetterMessage msg = new DeadLetterMessage(
                "d1", "pay_001", 1001L,
                "https://merchant.example/webhook",
                "{}", "sig", "error", 8,
                Instant.now(), Instant.now(), Instant.now()
        );
        inMemoryDlq.sendToDeadLetter(msg);

        ResponseEntity<?> resp = controller.listDlqMessages();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals(1, body.get("total"));
    }

    @Test
    @DisplayName("GET /dlq/messages: 内存模式空队列返回 total=0")
    void listDlqMessages_empty() {
        ResponseEntity<?> resp = controller.listDlqMessages();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals(0, body.get("total"));
    }

    @Test
    @DisplayName("GET /dlq/messages: Kafka 模式返回 400 提示")
    void listDlqMessages_kafkaMode() {
        // 重新设置：inMemoryDlq 不可用
        when(dlqProvider.getIfAvailable()).thenReturn(null);
        controller = new WebhookDeliveryController(deliveryService, repository, dlqProvider);

        ResponseEntity<?> resp = controller.listDlqMessages();

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertNotNull(body.get("error"));
        assertNotNull(body.get("hint"));
    }
}