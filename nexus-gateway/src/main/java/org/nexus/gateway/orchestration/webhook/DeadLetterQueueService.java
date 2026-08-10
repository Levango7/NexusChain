package org.nexus.gateway.orchestration.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 死信队列服务（P4-T5）。
 *
 * <p>使用 Kafka DLQ topic {@code webhook-dlq} 持久化投递失败的消息。
 *
 * <p>消息格式：{@link DeadLetterMessage} 序列化为 JSON，包含：
 * <ul>
 *   <li>原始 Webhook payload</li>
 *   <li>失败原因</li>
 *   <li>重试次数</li>
 *   <li>最后重试时间</li>
 * </ul>
 *
 * <p>激活条件：{@code nexus.webhook.dlq.store=kafka}（默认），
 * 测试环境可通过 {@code nexus.webhook.dlq.store=memory} 切换为内存实现
 * （{@link InMemoryDeadLetterQueueService}），避免依赖 Kafka broker。
 *
 * <p>Kafka topic 配置见 {@code deploy/kafka/kafka-topics.yaml}（P3-T4 已部署）。
 *
 * @since Phase 4 - P4-T5 Webhook 重试与死信队列增强
 */
@Service
@ConditionalOnProperty(prefix = "nexus.webhook.dlq", name = "store", havingValue = "kafka", matchIfMissing = true)
public class DeadLetterQueueService implements DeadLetterSender {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueueService.class);

    /** Kafka DLQ topic 名称（与 deploy/kafka/kafka-topics.yaml 对齐）。 */
    public static final String DLQ_TOPIC = "webhook-dlq";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final boolean waitForAck;

    @Autowired
    public DeadLetterQueueService(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${nexus.webhook.dlq.wait-for-ack:true}") boolean waitForAck) {
        this.kafkaTemplate = kafkaTemplate;
        this.waitForAck = waitForAck;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * 将死信消息发送到 Kafka DLQ topic。
     *
     * @param message 死信消息
     */
    @Override
    public void sendToDeadLetter(DeadLetterMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("dead letter message must not be null");
        }
        String json = serialize(message);
        String partitionKey = message.getPaymentId() != null ? message.getPaymentId() : message.getDeliveryId();

        try {
            if (waitForAck) {
                kafkaTemplate.send(DLQ_TOPIC, partitionKey, json).get();
                log.info("Dead letter sent to Kafka: topic={}, deliveryId={}, paymentId={}, retryCount={}",
                        DLQ_TOPIC, message.getDeliveryId(), message.getPaymentId(), message.getRetryCount());
            } else {
                kafkaTemplate.send(DLQ_TOPIC, partitionKey, json);
                log.info("Dead letter fire-and-forget to Kafka: deliveryId={}, paymentId={}",
                        message.getDeliveryId(), message.getPaymentId());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending dead letter to Kafka", ie);
        } catch (Exception e) {
            log.error("Failed to send dead letter to Kafka: deliveryId={}, paymentId={}, error={}",
                    message.getDeliveryId(), message.getPaymentId(), e.getMessage(), e);
            throw new IllegalStateException("Failed to send dead letter to Kafka: " + e.getMessage(), e);
        }
    }

    /**
     * 序列化死信消息为 JSON。
     */
    private String serialize(DeadLetterMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize dead letter message", e);
        }
    }

    /**
     * 反序列化 JSON 为死信消息（供消费端/重投使用）。
     */
    public DeadLetterMessage deserialize(String json) {
        try {
            return objectMapper.readValue(json, DeadLetterMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize dead letter message", e);
        }
    }
}