package org.nexus.bridge.messaging;

import org.nexus.bridge.repository.BridgeMessageRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

/**
 * 数据库消息存储实现（PLAN-026 桥持久化）。
 *
 * <p>{@link MessageStore} 的 JPA 落库实现：消息、执行结果与 per 源链 nonce
 * 全部持久化，进程重启后去重 / 顺序 / 追溯状态不丢。语义与
 * {@link InMemoryMessageStore} 逐条对齐，二者可通过装配开关互换
 * （见 {@link MessageStoreConfig}）。</p>
 *
 * <h2>并发语义</h2>
 * <ul>
 *   <li>{@link #save} 先查后插，插入冲突（同 messageId 并发）由主键兜底，
 *       捕获 {@link DataIntegrityViolationException} 返回 false —— 先到者胜；</li>
 *   <li>{@link #recordExecution} 带乐观锁（{@code version} 列），并发执行同一消息时
 *       后到者抛 optimistic lock 失败 —— 与"执行只应发生一次"的语义一致，
 *       由上层幂等检查负责避免到达这里。</li>
 * </ul>
 *
 * @since 2.75.0
 */
public class JdbcMessageStore implements MessageStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcMessageStore.class);

    private final BridgeMessageRecordRepository repository;

    public JdbcMessageStore(BridgeMessageRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean save(CrossChainMessage message) {
        if (message == null || message.getMessageId() == null) {
            return false;
        }
        if (repository.existsById(message.getMessageId())) {
            return false;
        }
        try {
            repository.save(new BridgeMessageRecord(message, Instant.now()));
            return true;
        } catch (DataIntegrityViolationException e) {
            // 并发中继同一消息：主键冲突即重复，先到者胜
            log.debug("Duplicate message insert raced (messageId={}): {}",
                    message.getMessageId(), e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<CrossChainMessage> findById(String messageId) {
        if (messageId == null) {
            return Optional.empty();
        }
        return repository.findById(messageId).map(BridgeMessageRecord::toMessage);
    }

    @Override
    public boolean existsById(String messageId) {
        return messageId != null && repository.existsById(messageId);
    }

    @Override
    public long getMaxNonce(String sourceChain) {
        if (sourceChain == null) {
            return -1L;
        }
        return repository.findMaxNonceBySourceChain(sourceChain);
    }

    @Override
    public boolean recordExecution(String messageId, String txHash) {
        if (messageId == null) {
            return false;
        }
        Optional<BridgeMessageRecord> found = repository.findById(messageId);
        if (found.isEmpty()) {
            return false;
        }
        BridgeMessageRecord record = found.get();
        record.setExecutionTxHash(txHash);
        record.setStatus(MessageStatus.EXECUTED);
        record.setUpdatedAt(Instant.now());
        repository.save(record);
        return true;
    }

    @Override
    public java.util.List<CrossChainMessage> findPending(int limit) {
        if (limit <= 0) {
            return java.util.Collections.emptyList();
        }
        try {
            java.util.List<BridgeMessageRecord> records = repository.findPending(
                    org.springframework.data.domain.PageRequest.of(0, limit));
            java.util.List<CrossChainMessage> out = new java.util.ArrayList<>(records.size());
            for (BridgeMessageRecord r : records) {
                out.add(r.toMessage());
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("findPending failed: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public Optional<String> getExecutionTxHash(String messageId) {
        if (messageId == null) {
            return Optional.empty();
        }
        return repository.findById(messageId).map(BridgeMessageRecord::getExecutionTxHash);
    }

    @Override
    public void clear() {
        repository.deleteAll();
    }
}
