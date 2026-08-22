package org.nexus.gateway.orchestration.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Webhook 投递管理 API（P4-T5）。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET /api/v1/webhooks/deliveries/{id}} - 查询投递状态</li>
 *   <li>{@code GET /api/v1/webhooks/deliveries} - 分页查询投递记录（支持按商户/状态过滤）</li>
 *   <li>{@code GET /api/v1/webhooks/payments/{paymentId}/deliveries} - 按支付 ID 查询投递记录</li>
 *   <li>{@code POST /api/v1/webhooks/dlq/replay} - 手动重投（从 DLQ）</li>
 *   <li>{@code GET /api/v1/webhooks/dlq/messages} - 列出 DLQ 消息（仅内存模式可用）</li>
 * </ul>
 *
 * <p>注意：{@code GET /api/v1/webhooks/dlq/messages} 仅在 {@code nexus.webhook.dlq.store=memory}
 * 时可用（Kafka 模式下 DLQ 消息存储在 Kafka topic，需通过 Kafka consumer 读取）。
 *
 * @since Phase 4 - P4-T5 Webhook 重试与死信队列增强
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookDeliveryController {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryController.class);

    private final WebhookDeliveryService deliveryService;
    private final WebhookDeliveryRepository repository;
    private final InMemoryDeadLetterQueueService inMemoryDlq;

    @Autowired
    public WebhookDeliveryController(
            WebhookDeliveryService deliveryService,
            WebhookDeliveryRepository repository,
            org.springframework.beans.factory.ObjectProvider<InMemoryDeadLetterQueueService> inMemoryDlqProvider) {
        this.deliveryService = deliveryService;
        this.repository = repository;
        this.inMemoryDlq = inMemoryDlqProvider.getIfAvailable();
    }

    /**
     * 查询投递状态。
     *
     * @param id 投递 ID
     * @return 投递记录详情；404 若不存在
     */
    @GetMapping("/deliveries/{id}")
    public ResponseEntity<Map<String, Object>> getDelivery(@PathVariable String id) {
        WebhookDeliveryRecord record = deliveryService.getDelivery(id);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDeliveryResponse(record));
    }

    /**
     * 分页查询投递记录。
     *
     * @param merchantId 商户 ID（可选过滤）
     * @param status     投递状态（可选过滤）
     * @param page       页码（0-based）
     * @param size        每页大小
     */
    @GetMapping("/deliveries")
    public ResponseEntity<Map<String, Object>> listDeliveries(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<WebhookDeliveryRecord> records;

        if (merchantId != null && status != null) {
            WebhookDeliveryStatus statusEnum = WebhookDeliveryStatus.valueOf(status.toUpperCase());
            records = repository.findByMerchantIdAndStatus(merchantId, statusEnum, pageRequest);
        } else if (merchantId != null) {
            records = repository.findByMerchantId(merchantId, pageRequest);
        } else if (status != null) {
            WebhookDeliveryStatus statusEnum = WebhookDeliveryStatus.valueOf(status.toUpperCase());
            records = repository.findByStatus(statusEnum, pageRequest);
        } else {
            records = repository.findAll(pageRequest);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", records.getContent().stream().map(this::toDeliveryResponse).collect(Collectors.toList()));
        resp.put("total", records.getTotalElements());
        resp.put("page", page);
        resp.put("size", size);
        return ResponseEntity.ok(resp);
    }

    /**
     * 按支付 ID 查询所有投递记录。
     *
     * @param paymentId 支付 ID
     */
    @GetMapping("/payments/{paymentId}/deliveries")
    public ResponseEntity<List<Map<String, Object>>> getDeliveriesByPayment(@PathVariable String paymentId) {
        List<WebhookDeliveryRecord> records = deliveryService.getByPaymentId(paymentId);
        return ResponseEntity.ok(records.stream().map(this::toDeliveryResponse).collect(Collectors.toList()));
    }

    /**
     * 手动重投：从 DLQ 取消息重新投递。
     *
     * <p>请求体：
     * <pre>{@code
     * {
     *   "deliveryId": "xxx",       // 可选：指定重投的 delivery ID
     *   "message": { ... }          // 可选：直接传入 DeadLetterMessage
     * }
     * }</pre>
     *
     * <p>若 {@code deliveryId} 与 {@code message} 都未提供，且 DLQ 为内存模式，
     * 则重投所有 DLQ 消息。
     *
     * @return 重投结果
     */
    @PostMapping("/dlq/replay")
    public ResponseEntity<Map<String, Object>> replay(@RequestBody(required = false) Map<String, Object> body) {
        log.info("Webhook DLQ replay requested: body={}", body);

        try {
            if (body != null && body.containsKey("message")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> messageMap = (Map<String, Object>) body.get("message");
                DeadLetterMessage message = mapToDeadLetterMessage(messageMap);
                WebhookDeliveryRecord result = deliveryService.replay(message);
                return ResponseEntity.ok(replayResultResponse(result));
            }

            if (body != null && body.containsKey("deliveryId")) {
                String deliveryId = String.valueOf(body.get("deliveryId"));
                WebhookDeliveryRecord record = deliveryService.getDelivery(deliveryId);
                if (record == null) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(errorResponse("Delivery not found: " + deliveryId));
                }
                DeadLetterMessage message = DeadLetterMessage.fromRecord(record, record.getLastError());
                WebhookDeliveryRecord result = deliveryService.replay(message);
                return ResponseEntity.ok(replayResultResponse(result));
            }

            // 重投所有内存 DLQ 消息
            if (inMemoryDlq != null) {
                List<DeadLetterMessage> messages = inMemoryDlq.drainMessages();
                if (messages.isEmpty()) {
                    return ResponseEntity.ok(Map.of("replayed", 0, "message", "DLQ is empty"));
                }
                int replayed = 0;
                int failed = 0;
                for (DeadLetterMessage msg : messages) {
                    try {
                        deliveryService.replay(msg);
                        replayed++;
                    } catch (RuntimeException e) {
                        log.error("Replay failed: deliveryId={}, error={}", msg.getDeliveryId(), e.getMessage());
                        failed++;
                    }
                }
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("replayed", replayed);
                resp.put("failed", failed);
                resp.put("total", messages.size());
                return ResponseEntity.ok(resp);
            }

            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(errorResponse("Either 'deliveryId' or 'message' must be provided, or use memory DLQ mode for replay-all"));
        } catch (Exception e) {
            log.error("DLQ replay failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse("Replay failed: " + e.getMessage()));
        }
    }

    /**
     * 列出 DLQ 消息（仅内存模式可用）。
     *
     * <p>Kafka 模式下 DLQ 消息存储在 Kafka topic，需通过 Kafka consumer 读取，
     * 此端点返回 400 提示切换到内存模式或使用 Kafka 工具。
     */
    @GetMapping("/dlq/messages")
    public ResponseEntity<?> listDlqMessages() {
        if (inMemoryDlq == null) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("error", "DLQ message listing requires memory store mode (nexus.webhook.dlq.store=memory)");
            resp.put("hint", "For Kafka mode, use kafka-console-consumer or kafka-cat to read topic: " + DeadLetterQueueService.DLQ_TOPIC);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(resp);
        }
        List<DeadLetterMessage> messages = inMemoryDlq.listMessages();
        return ResponseEntity.ok(Map.of(
                "data", messages.stream().map(this::toDlqMessageResponse).collect(Collectors.toList()),
                "total", messages.size()
        ));
    }

    // --- Helpers ---

    private Map<String, Object> toDeliveryResponse(WebhookDeliveryRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delivery_id", r.getDeliveryId());
        m.put("payment_id", r.getPaymentId());
        m.put("merchant_id", r.getMerchantId());
        m.put("notify_url", r.getNotifyUrl());
        m.put("status", r.getStatus().name());
        m.put("attempt_count", r.getAttemptCount());
        m.put("last_error", r.getLastError());
        m.put("signature", r.getSignature());
        m.put("created_at", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        m.put("last_attempt_at", r.getLastAttemptAt() != null ? r.getLastAttemptAt().toString() : null);
        m.put("delivered_at", r.getDeliveredAt() != null ? r.getDeliveredAt().toString() : null);
        m.put("dead_lettered_at", r.getDeadLetteredAt() != null ? r.getDeadLetteredAt().toString() : null);
        return m;
    }

    private Map<String, Object> toDlqMessageResponse(DeadLetterMessage m) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("delivery_id", m.getDeliveryId());
        resp.put("payment_id", m.getPaymentId());
        resp.put("merchant_id", m.getMerchantId());
        resp.put("notify_url", m.getNotifyUrl());
        resp.put("payload", m.getPayload());
        resp.put("signature", m.getSignature());
        resp.put("failure_reason", m.getFailureReason());
        resp.put("retry_count", m.getRetryCount());
        resp.put("first_attempt_at", m.getFirstAttemptAt() != null ? m.getFirstAttemptAt().toString() : null);
        resp.put("last_attempt_at", m.getLastAttemptAt() != null ? m.getLastAttemptAt().toString() : null);
        resp.put("dead_lettered_at", m.getDeadLetteredAt() != null ? m.getDeadLetteredAt().toString() : null);
        return resp;
    }

    private Map<String, Object> replayResultResponse(WebhookDeliveryRecord result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("replayed", 1);
        resp.put("delivery", toDeliveryResponse(result));
        return resp;
    }

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("error", message);
        return resp;
    }

    private DeadLetterMessage mapToDeadLetterMessage(Map<String, Object> m) {
        DeadLetterMessage msg = new DeadLetterMessage();
        msg.setDeliveryId((String) m.get("delivery_id"));
        msg.setPaymentId((String) m.get("payment_id"));
        msg.setMerchantId(m.get("merchant_id") instanceof Number ? ((Number) m.get("merchant_id")).longValue() : null);
        msg.setNotifyUrl((String) m.get("notify_url"));
        msg.setPayload((String) m.get("payload"));
        msg.setSignature((String) m.get("signature"));
        msg.setFailureReason((String) m.get("failure_reason"));
        msg.setRetryCount(m.get("retry_count") instanceof Number ? ((Number) m.get("retry_count")).intValue() : 0);
        return msg;
    }
}