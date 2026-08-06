package org.nexus.wallet.signing.mpc.router;

import org.nexus.wallet.signing.mpc.transport.MpcMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 消息去重器：基于 messageId 全局唯一性丢弃重复消息。
 *
 * <p>配合 {@code WriteAheadLog} 的 at-least-once 语义实现 exactly-once 投递：
 * WAL 回放可能重发已处理消息，去重器用 {@code messageId} 集合丢弃重复。</p>
 *
 * <p><b>实现</b>：{@link ConcurrentHashMap} + {@link Boolean} 模拟 set，
 * 容量上限 {@code maxCapacity}，超出时清理最旧条目（FIFO 队列辅助）。</p>
 */
@Component
public class MessageDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(MessageDeduplicator.class);

    private final int maxCapacity;
    private final ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> fifo = new ConcurrentLinkedQueue<>();

    /**
     * 构造默认容量（100000）的去重器。
     */
    public MessageDeduplicator() {
        this(100_000);
    }

    /**
     * 构造指定容量的去重器。
     *
     * @param maxCapacity 最大记录数
     */
    public MessageDeduplicator(int maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    /**
     * 检查并记录一条消息。
     *
     * @param message 接收到的消息
     * @return {@code true} iff 该消息是新的（应处理）；{@code false} iff 重复（应丢弃）
     */
    public synchronized boolean checkAndRecord(MpcMessage message) {
        String id = message.getMessageId();
        if (seen.containsKey(id)) {
            log.debug("Duplicate message dropped: {}", id);
            return false;
        }
        if (seen.size() >= maxCapacity) {
            String oldest = fifo.poll();
            if (oldest != null) seen.remove(oldest);
        }
        seen.put(id, Boolean.TRUE);
        fifo.offer(id);
        return true;
    }

    /**
     * 检查并记录一条消息 ID（不要求完整消息对象）。
     *
     * @param messageId 消息 ID
     * @return {@code true} iff 新消息
     */
    public synchronized boolean checkAndRecord(String messageId) {
        if (seen.containsKey(messageId)) return false;
        if (seen.size() >= maxCapacity) {
            String oldest = fifo.poll();
            if (oldest != null) seen.remove(oldest);
        }
        seen.put(messageId, Boolean.TRUE);
        fifo.offer(messageId);
        return true;
    }

    /**
     * @return 当前已记录的消息数
     */
    public int size() {
        return seen.size();
    }

    /**
     * 清空去重器（用于测试或会话重置）。
     */
    public void clear() {
        seen.clear();
        fifo.clear();
    }
}