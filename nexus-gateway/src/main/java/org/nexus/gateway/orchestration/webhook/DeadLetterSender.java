package org.nexus.gateway.orchestration.webhook;

/**
 * 死信队列发送接口（P4-T5）。
 *
 * <p>统一抽象 {@link DeadLetterQueueService}（Kafka 实现）与
 * {@link InMemoryDeadLetterQueueService}（内存实现），便于
 * {@link WebhookDeliveryService} 在不同环境（生产 Kafka / 测试内存）下透明切换。
 *
 * @since Phase 4 - P4-T5 Webhook 重试与死信队列增强
 */
public interface DeadLetterSender {

    /**
     * 将死信消息发送到死信队列。
     *
     * @param message 死信消息
     */
    void sendToDeadLetter(DeadLetterMessage message);
}