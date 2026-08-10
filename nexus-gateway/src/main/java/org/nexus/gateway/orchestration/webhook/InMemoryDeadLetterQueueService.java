package org.nexus.gateway.orchestration.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 内存死信队列服务（P4-T5）。
 *
 * <p>测试环境 / 无 Kafka broker 场景的 DLQ 实现。激活条件：
 * {@code nexus.webhook.dlq.store=memory}。
 *
 * <p>提供 {@link #listMessages()} 与 {@link #drainMessages()} 便于测试断言。
 *
 * @since Phase 4 - P4-T5 Webhook 重试与死信队列增强
 */
@Service
@ConditionalOnProperty(prefix = "nexus.webhook.dlq", name = "store", havingValue = "memory")
public class InMemoryDeadLetterQueueService implements DeadLetterSender {

    private static final Logger log = LoggerFactory.getLogger(InMemoryDeadLetterQueueService.class);

    private final Queue<DeadLetterMessage> deadLetterQueue = new ConcurrentLinkedQueue<>();

    /**
     * 将死信消息存入内存队列。
     */
    @Override
    public void sendToDeadLetter(DeadLetterMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("dead letter message must not be null");
        }
        deadLetterQueue.offer(message);
        log.info("Dead letter stored in memory: deliveryId={}, paymentId={}, retryCount={}",
                message.getDeliveryId(), message.getPaymentId(), message.getRetryCount());
    }

    /**
     * 列出当前队列中的所有死信消息（不消费）。
     */
    public List<DeadLetterMessage> listMessages() {
        return new ArrayList<>(deadLetterQueue);
    }

    /**
     * 取出并移除所有死信消息（用于测试断言 / 重投）。
     */
    public List<DeadLetterMessage> drainMessages() {
        List<DeadLetterMessage> drained = new ArrayList<>();
        DeadLetterMessage msg;
        while ((msg = deadLetterQueue.poll()) != null) {
            drained.add(msg);
        }
        return drained;
    }

    /**
     * 队列大小。
     */
    public int size() {
        return deadLetterQueue.size();
    }
}