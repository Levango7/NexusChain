package org.nexus.bridge.messaging;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存消息存储实现。
 *
 * <p>使用 {@link ConcurrentHashMap} 存储消息与执行记录，线程安全，
 * 适用于测试与单实例开发环境。生产环境应替换为 Redis / 数据库实现。</p>
 *
 * <h2>存储结构</h2>
 * <ul>
 *   <li>{@code messages} — messageId → {@link CrossChainMessage}</li>
 *   <li>{@code executionTxHashes} — messageId → 目标链交易哈希</li>
 *   <li>{@code maxNonces} — sourceChain → 最大已中继 nonce（用于顺序保证）</li>
 * </ul>
 *
 * @since 1.9.2
 */
public class InMemoryMessageStore implements MessageStore {

    /** 消息主存储：messageId → message。 */
    private final Map<String, CrossChainMessage> messages = new ConcurrentHashMap<>();

    /** 执行交易哈希：messageId → txHash。 */
    private final Map<String, String> executionTxHashes = new ConcurrentHashMap<>();

    /** 每条源链已中继的最大 nonce：sourceChain → maxNonce。 */
    private final Map<String, Long> maxNonces = new ConcurrentHashMap<>();

    @Override
    public boolean save(CrossChainMessage message) {
        if (message == null || message.getMessageId() == null) {
            return false;
        }
        CrossChainMessage prev = messages.putIfAbsent(message.getMessageId(), message);
        if (prev != null) {
            return false;
        }
        // 更新该源链的最大 nonce
        maxNonces.compute(message.getSourceChain(),
                (k, old) -> (old == null) ? message.getNonce() : Math.max(old, message.getNonce()));
        return true;
    }

    @Override
    public Optional<CrossChainMessage> findById(String messageId) {
        if (messageId == null) return Optional.empty();
        return Optional.ofNullable(messages.get(messageId));
    }

    @Override
    public boolean existsById(String messageId) {
        return messageId != null && messages.containsKey(messageId);
    }

    @Override
    public long getMaxNonce(String sourceChain) {
        if (sourceChain == null) return -1L;
        Long max = maxNonces.get(sourceChain);
        return max == null ? -1L : max;
    }

    @Override
    public boolean recordExecution(String messageId, String txHash) {
        if (messageId == null || !messages.containsKey(messageId)) {
            return false;
        }
        executionTxHashes.put(messageId, txHash);
        // 同步更新消息状态
        CrossChainMessage msg = messages.get(messageId);
        if (msg != null) {
            msg.setStatus(MessageStatus.EXECUTED);
        }
        return true;
    }

    @Override
    public Optional<String> getExecutionTxHash(String messageId) {
        if (messageId == null) return Optional.empty();
        return Optional.ofNullable(executionTxHashes.get(messageId));
    }

    @Override
    public void clear() {
        messages.clear();
        executionTxHashes.clear();
        maxNonces.clear();
    }

    /**
     * 返回当前存储的消息总数（仅供测试 / 监控使用）。
     *
     * @return 消息总数
     */
    public int size() {
        return messages.size();
    }
}