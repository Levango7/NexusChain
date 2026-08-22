package org.nexus.gateway.event.sourcing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * 基于 Kafka 的事件存储实现。
 *
 * <p>事件以 JSON 序列化后写入 Kafka topic {@code payment-events}，
 * 以 {@code aggregateId} 作为分区键，保证同一聚合根的事件有序落盘。
 *
 * <p>设计要点：
 * <ul>
 *   <li>事件溯源的"持久化"语义：append 即 produce，Kafka 的不可变日志天然满足事件不可变</li>
 *   <li>乐观锁：通过 {@code version} 字段在消费端校验（Kafka 单分区内消息有序，
 *       生产端追加时若版本不连续，消费投影会拒绝应用并告警）</li>
 *   <li>{@link #loadEvents} 与 {@link #loadEventsFromVersion} 在 Kafka 实现中需要回溯消费，
 *       生产环境通常由消费者维护本地物化视图；本实现返回空列表并记录 WARN，
 *       实际重放由 {@link EventReplayService} 配合 Kafka consumer.seek 完成</li>
 *   <li>{@link #loadAllEvents} 同上，全量重放通过 Kafka consumer 全分区扫描实现</li>
 * </ul>
 *
 * <p>激活条件：{@code nexus.event-sourcing.store=kafka}（默认），
 * 测试环境可通过该开关切换为 {@link InMemoryEventStore}。
 *
 * @since Phase 3 - P3-T3 事件溯源 + CQRS
 */
@Component
@ConditionalOnProperty(prefix = "nexus.event-sourcing", name = "store", havingValue = "kafka", matchIfMissing = true)
public class KafkaEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventStore.class);

    /** Kafka topic：支付事件流（与 P3-T4 Kafka 部署对齐） */
    public static final String TOPIC_PAYMENT_EVENTS = "payment-events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /** 是否等待 Kafka ack 后返回；测试环境可关闭以避免依赖 broker */
    private final boolean waitForAck;

    public KafkaEventStore(KafkaTemplate<String, String> kafkaTemplate,
                           @Value("${nexus.event-sourcing.kafka.wait-for-ack:true}") boolean waitForAck) {
        this.kafkaTemplate = kafkaTemplate;
        this.waitForAck = waitForAck;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public void append(PaymentEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        String payload = serialize(event);
        // 以 aggregateId 作为分区键，保证同一聚合根事件落同一分区、有序
        CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(TOPIC_PAYMENT_EVENTS, event.getAggregateId(), payload);

        if (waitForAck) {
            try {
                SendResult<String, String> result = future.get();
                log.debug("Event appended to Kafka: topic={}, partition={}, offset={}, eventId={}, aggregateId={}, version={}",
                        TOPIC_PAYMENT_EVENTS,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        event.getEventId(),
                        event.getAggregateId(),
                        event.getVersion());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new EventStoreException("Interrupted while appending event to Kafka", ie);
            } catch (ExecutionException ee) {
                // future.get() 的 ExecutionException：解包 cause 抛出
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                throw new EventStoreException("Failed to append event to Kafka: " + cause.getMessage(), cause);
            } catch (RuntimeException e) {
                throw new EventStoreException("Failed to append event to Kafka: " + e.getMessage(), e);
            }
        } else {
            log.debug("Event fire-and-forget to Kafka: eventId={}, aggregateId={}, version={}",
                    event.getEventId(), event.getAggregateId(), event.getVersion());
        }
    }

    @Override
    public List<PaymentEvent> loadEvents(String aggregateId) {
        // Kafka 是只追加日志，无法直接按 key 随机读取历史事件。
        // 生产环境重放由 EventReplayService 通过 KafkaConsumer.seek + poll 实现。
        // 本方法返回空列表，调用方应使用 EventReplayService.replay(aggregateId)。
        log.warn("KafkaEventStore.loadEvents is not supported directly; use EventReplayService for replay. aggregateId={}",
                aggregateId);
        return Collections.emptyList();
    }

    @Override
    public List<PaymentEvent> loadEventsFromVersion(String aggregateId, long fromVersion) {
        log.warn("KafkaEventStore.loadEventsFromVersion is not supported directly; use EventReplayService. aggregateId={}, fromVersion={}",
                aggregateId, fromVersion);
        return Collections.emptyList();
    }

    @Override
    public long currentVersion(String aggregateId) {
        // 同上，Kafka 不支持按 key 查询最新版本号。
        // 调用方应在聚合根缓存或读模型中维护版本号。
        log.warn("KafkaEventStore.currentVersion is not supported directly; aggregateId={}", aggregateId);
        return 0L;
    }

    @Override
    public List<PaymentEvent> loadAllEvents() {
        log.warn("KafkaEventStore.loadAllEvents is not supported directly; use EventReplayService for full replay");
        return Collections.emptyList();
    }

    /**
     * 序列化事件为 JSON 字符串。
     *
     * <p>JSON 中包含 {@code eventType} 字段，消费端据此反序列化为具体子类。
     */
    private String serialize(PaymentEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new EventStoreException("Failed to serialize event: " + event, e);
        }
    }

    /**
     * 反序列化 JSON 为具体事件子类（供消费端使用）。
     *
     * @param json   JSON 字符串
     * @param target 目标事件类型
     * @return 反序列化后的事件对象
     */
    public PaymentEvent deserialize(String json, Class<? extends PaymentEvent> target) {
        try {
            return objectMapper.readValue(json, target);
        } catch (JsonProcessingException e) {
            throw new EventStoreException("Failed to deserialize event: " + json, e);
        }
    }
}