package org.nexus.gateway.orchestration.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA 持久化死信队列服务（Wave 8-A5）。
 *
 * <p>将死信消息持久化到数据库 {@code dead_letter_records} 表，替代纯 Kafka/内存方案。
 * 激活条件：{@code nexus.webhook.dlq.store=jpa}。
 *
 * <p>优势：
 * <ul>
 *   <li>即使 Kafka broker 不可用也能保留死信记录</li>
 *   <li>支持运维通过 SQL/API 查询死信记录</li>
 *   <li>支持手动重投与状态追踪</li>
 *   <li>事务性保证：死信记录要么完整写入，要么不写入</li>
 * </ul>
 *
 * @since Wave 8-A5 - Webhook 可靠性增强
 */
@Service
@ConditionalOnProperty(prefix = "nexus.webhook.dlq", name = "store", havingValue = "jpa")
public class JpaDeadLetterQueueService implements DeadLetterSender {

    private static final Logger log = LoggerFactory.getLogger(JpaDeadLetterQueueService.class);

    private final DeadLetterRecordRepository repository;

    public JpaDeadLetterQueueService(DeadLetterRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * 将死信消息持久化到数据库。
     *
     * @param message 死信消息
     */
    @Override
    @Transactional
    public void sendToDeadLetter(DeadLetterMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("dead letter message must not be null");
        }

        DeadLetterRecord record = new DeadLetterRecord();
        record.setWebhookUrl(message.getNotifyUrl());
        record.setEventId(message.getDeliveryId());
        record.setPayload(message.getPayload());
        record.setErrorMessage(message.getFailureReason());
        record.setRetryCount(message.getRetryCount());
        record.setStatus(DeadLetterRecordStatus.PENDING_REPLAY);
        record.setCreatedAt(message.getDeadLetteredAt() != null ? message.getDeadLetteredAt() : Instant.now());
        record.setLastRetryAt(message.getLastAttemptAt());

        record = repository.save(record);
        log.info("Dead letter persisted to DB: id={}, eventId={}, webhookUrl={}, retryCount={}",
                record.getId(), record.getEventId(), record.getWebhookUrl(), record.getRetryCount());
    }

    /**
     * 列出所有待重投的死信记录。
     */
    public List<DeadLetterRecord> listPendingReplay() {
        return repository.findByStatus(DeadLetterRecordStatus.PENDING_REPLAY);
    }

    /**
     * 列出所有死信记录（不分状态）。
     */
    public List<DeadLetterRecord> listAll() {
        return repository.findAll();
    }

    /**
     * 标记死信记录为已重投。
     */
    @Transactional
    public void markReplayed(Long id) {
        repository.findById(id).ifPresent(record -> {
            record.setStatus(DeadLetterRecordStatus.REPLAYED);
            record.setLastRetryAt(Instant.now());
            repository.save(record);
            log.info("Dead letter marked as REPLAYED: id={}, eventId={}", id, record.getEventId());
        });
    }

    /**
     * 标记死信记录为已归档（不再重投）。
     */
    @Transactional
    public void markArchived(Long id) {
        repository.findById(id).ifPresent(record -> {
            record.setStatus(DeadLetterRecordStatus.ARCHIVED);
            repository.save(record);
            log.info("Dead letter marked as ARCHIVED: id={}, eventId={}", id, record.getEventId());
        });
    }

    /**
     * 重投失败后重新标记为待重投。
     */
    @Transactional
    public void markPendingReplay(Long id) {
        repository.findById(id).ifPresent(record -> {
            record.setStatus(DeadLetterRecordStatus.PENDING_REPLAY);
            record.setLastRetryAt(Instant.now());
            repository.save(record);
            log.info("Dead letter re-marked as PENDING_REPLAY: id={}, eventId={}", id, record.getEventId());
        });
    }

    /**
     * 按事件 ID 查询死信记录。
     */
    public List<DeadLetterRecord> findByEventId(String eventId) {
        return repository.findByEventId(eventId);
    }

    /**
     * 统计待重投的死信记录数量。
     */
    public long countPendingReplay() {
        return repository.countByStatus(DeadLetterRecordStatus.PENDING_REPLAY);
    }
}