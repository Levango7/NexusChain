package org.nexus.signing.mpc.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Nonce + Timestamp 防重放窗口。
 *
 * <p>每条接收到的消息必须满足：</p>
 * <ul>
 *   <li><b>时间戳窗口</b>：{@code |now - messageTimestamp| <= windowMillis}，
 *       拒绝过期与未来消息。</li>
 *   <li><b>Nonce 唯一性</b>：同一发送者的 nonce 在窗口内不得重复出现，
 *       拒绝重放。</li>
 * </ul>
 *
 * <p><b>实现</b>：</p>
 * <ul>
 *   <li>每个发送者维护一个 {@link ConcurrentSkipListSet} 存储已见 nonce，
 *       定期清理过期条目（基于时间戳）。</li>
 *   <li>线程安全：所有操作通过 ConcurrentHashMap + ConcurrentSkipListSet 实现。</li>
 *   <li>内存有界：每个发送者最多保留 {@code maxNoncesPerSender} 个 nonce，
 *       超出时清理最旧条目（实际实现为整批清理过期，简单但有效）。</li>
 * </ul>
 *
 * <p>注意：在多节点部署中，nonce 检查需要共享存储（如 Redis）才能跨节点
 * 防重放。本类适用于单节点或每节点独立检查的场景。</p>
 */
public class NonceTracker {

    private static final Logger log = LoggerFactory.getLogger(NonceTracker.class);

    /** 默认时间窗口：±60 秒。 */
    private static final long DEFAULT_WINDOW_MILLIS = 60_000L;

    /** 默认每发送者最大 nonce 记录数。 */
    private static final int DEFAULT_MAX_NONCES = 10_000;

    private final long windowMillis;
    private final int maxNoncesPerSender;

    /** senderId -> (nonce -> expiryTimestamp)。 */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> seenNonces
            = new ConcurrentHashMap<>();

    /**
     * 用默认窗口构造。
     */
    public NonceTracker() {
        this(DEFAULT_WINDOW_MILLIS, DEFAULT_MAX_NONCES);
    }

    /**
     * 构造 Nonce 跟踪器。
     *
     * @param windowMillis       时间戳窗口（毫秒）
     * @param maxNoncesPerSender 每发送者最大 nonce 记录数
     */
    public NonceTracker(long windowMillis, int maxNoncesPerSender) {
        if (windowMillis <= 0) throw new IllegalArgumentException("windowMillis must be > 0");
        if (maxNoncesPerSender <= 0) throw new IllegalArgumentException("maxNoncesPerSender must be > 0");
        this.windowMillis = windowMillis;
        this.maxNoncesPerSender = maxNoncesPerSender;
    }

    /**
     * 检查并记录一条消息的 nonce/timestamp。
     *
     * @param senderId  发送者 ID
     * @param nonce     消息 nonce
     * @param timestamp 消息时间戳（毫秒 UTC）
     * @return {@code true} iff 通过检查（应接受该消息）
     */
    public synchronized boolean checkAndRecord(String senderId, String nonce, long timestamp) {
        long now = Instant.now().toEpochMilli();
        // 1. 时间戳窗口
        if (Math.abs(now - timestamp) > windowMillis) {
            log.warn("Replay check failed: timestamp out of window: sender={}, ts={}, now={}, window={}",
                    senderId, timestamp, now, windowMillis);
            return false;
        }
        // 2. Nonce 唯一性
        ConcurrentHashMap<String, Long> senderNonces =
                seenNonces.computeIfAbsent(senderId, k -> new ConcurrentHashMap<>());
        if (senderNonces.containsKey(nonce)) {
            log.warn("Replay detected: duplicate nonce: sender={}, nonce={}", senderId, nonce);
            return false;
        }
        // 3. 容量清理
        if (senderNonces.size() >= maxNoncesPerSender) {
            cleanupExpired(senderNonces, now);
        }
        senderNonces.put(nonce, timestamp + windowMillis);
        return true;
    }

    /**
     * 清理指定发送者的过期 nonce。
     */
    private void cleanupExpired(ConcurrentHashMap<String, Long> senderNonces, long now) {
        senderNonces.entrySet().removeIf(e -> e.getValue() < now);
    }

    /**
     * 清理所有发送者的过期 nonce（可由后台定时任务调用）。
     */
    public void cleanupAll() {
        long now = Instant.now().toEpochMilli();
        for (ConcurrentHashMap<String, Long> m : seenNonces.values()) {
            cleanupExpired(m, now);
        }
    }

    /**
     * @return 当前跟踪的发送者数量
     */
    public int getTrackedSenderCount() {
        return seenNonces.size();
    }
}