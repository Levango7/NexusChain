package org.nexus.bridge.security;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.nexus.bridge.entity.NonceRecord;
import org.nexus.bridge.repository.NonceRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Anti-replay protection for bridge validator signatures.
 *
 * <p>Each bridge operation (mint / unlock) carries a timestamp and a
 * unique nonce.  This class validates freshness and prevents the same
 * signed payload from being accepted twice.</p>
 *
 * <p><b>B-22 修复</b>：nonce 现在持久化到 DB（{@link NonceRecordRepository}），
 * 节点重启后仍能防止重放旧交易。未注入 Repository 时降级到内存存储
 * （仅适用于单实例测试环境）。</p>
 *
 * <p><b>B-23 修复</b>：nonce 长度不足时返回 {@code false}（拒绝），
 * 不再抛 {@code StringIndexOutOfBoundsException}。添加明确的错误日志。</p>
 *
 * <p>In a multi-instance deployment, the nonce store MUST be backed by
 * Redis (SET NX with TTL) or the provided {@link NonceRecordRepository}
 * (DB-backed) instead of the in-memory fallback.</p>
 */
@Component
public class ReplayProtection {

    private static final Logger log = LoggerFactory.getLogger(ReplayProtection.class);

    /** Maximum allowed clock skew between validator and bridge (seconds). */
    private static final long TIMESTAMP_DRIFT_SECONDS = 300; // 5 min

    /**
     * Minimum nonce length（B-23 修复）。
     *
     * <p>nonce 长度不足此值时拒绝（返回 {@code false}），防止弱 nonce 攻击。
     * 16 字符为保守下限（32 hex 字符 = 128 bit 是推荐长度）。</p>
     */
    private static final int MIN_NONCE_LENGTH = 16;

    /** In-memory nonce store for single-instance deployments (fallback when Repository not injected). */
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    /**
     * DB-backed nonce repository（B-22 修复）。
     *
     * <p>通过 {@code required = false} 注入：未配置时为 {@code null}，
     * 降级到内存存储（仅适用于单实例测试环境）。</p>
     */
    @Autowired(required = false)
    private NonceRecordRepository nonceRecordRepository;

    /** 默认构造函数（Spring 注入用）。 */
    public ReplayProtection() {
    }

    /**
     * 测试构造器：显式注入 Repository。
     *
     * @param nonceRecordRepository nonce 持久化 Repository（可为 null）
     */
    public ReplayProtection(NonceRecordRepository nonceRecordRepository) {
        this.nonceRecordRepository = nonceRecordRepository;
    }

    /**
     * Validate timestamp freshness.
     * @param epochSeconds  timestamp from the request (Unix epoch seconds)
     * @throws SecurityException if the timestamp is outside the allowed window
     */
    public void validateTimestamp(long epochSeconds) {
        long now = System.currentTimeMillis() / 1000;
        long drift = Math.abs(now - epochSeconds);
        if (drift > TIMESTAMP_DRIFT_SECONDS) {
            throw new SecurityException(
                "Timestamp drift too large: " + drift + "s (max " + TIMESTAMP_DRIFT_SECONDS + "s)");
        }
    }

    /**
     * Check-and-set a nonce.  If the nonce has already been seen, throw.
     *
     * <p><b>B-23 修复</b>：nonce 长度不足 {@value #MIN_NONCE_LENGTH} 字符时
     * 抛 {@link SecurityException}（明确的拒绝异常），不再因
     * {@code substring(0, 16)} 抛 {@code StringIndexOutOfBoundsException}。</p>
     *
     * @param nonce  hex-encoded nonce from the signed payload
     * @throws SecurityException if the nonce has been used before or is too short
     */
    public void checkAndRecordNonce(String nonce) {
        if (!isNonceFormatValid(nonce)) {
            throw new SecurityException("Nonce too short or null: length="
                    + (nonce == null ? "null" : nonce.length())
                    + ", minimum required=" + MIN_NONCE_LENGTH);
        }
        if (!tryRecordNonce(nonce)) {
            String safePrefix = nonce.length() >= MIN_NONCE_LENGTH
                    ? nonce.substring(0, MIN_NONCE_LENGTH)
                    : nonce;
            throw new SecurityException("Duplicate nonce — possible replay attack: " + safePrefix + "...");
        }
    }

    /**
     * Check-and-record nonce, returning boolean result instead of throwing (B-23 修复).
     *
     * <p>nonce 长度不足或已存在时返回 {@code false}（拒绝），不抛异常。
     * 调用方可据此返回错误响应。添加明确的错误日志。</p>
     *
     * @param nonce hex-encoded nonce from the signed payload
     * @return 首次记录成功返回 {@code true}；重复或格式非法返回 {@code false}
     */
    public boolean checkAndRecordNonceBoolean(String nonce) {
        if (!isNonceFormatValid(nonce)) {
            log.warn("Nonce rejected: too short or null, length={} (minimum={})",
                    nonce == null ? "null" : nonce.length(), MIN_NONCE_LENGTH);
            return false;
        }
        if (!tryRecordNonce(nonce)) {
            String safePrefix = nonce.length() >= MIN_NONCE_LENGTH
                    ? nonce.substring(0, MIN_NONCE_LENGTH)
                    : nonce;
            log.warn("Duplicate nonce rejected — possible replay attack: {}...", safePrefix);
            return false;
        }
        return true;
    }

    /**
     * 校验 nonce 格式（B-23 修复）。
     *
     * @param nonce 待校验的 nonce
     * @return 格式合法（非空、长度 >= {@value #MIN_NONCE_LENGTH}）返回 {@code true}
     */
    private static boolean isNonceFormatValid(String nonce) {
        return nonce != null && nonce.length() >= MIN_NONCE_LENGTH;
    }

    /**
     * 尝试记录 nonce（B-22 修复：优先 DB 持久化，降级内存）。
     *
     * @param nonce 待记录的 nonce（已通过格式校验）
     * @return 首次记录返回 {@code true}；已存在返回 {@code false}
     */
    private boolean tryRecordNonce(String nonce) {
        // B-22 修复：优先 DB 持久化
        if (nonceRecordRepository != null) {
            try {
                if (nonceRecordRepository.existsById(nonce)) {
                    return false;
                }
                nonceRecordRepository.save(new NonceRecord(nonce, Instant.now()));
                return true;
            } catch (RuntimeException e) {
                // DB 操作失败时 fail-closed：拒绝该 nonce（防止 DB 不可用时绕过重放保护）
                log.error("Failed to persist nonce to DB, fail-closed rejecting: nonce={}, error={}",
                        nonce, e.getMessage());
                return false;
            }
        }
        // 降级：内存存储（单实例测试环境）
        Long prev = seenNonces.putIfAbsent(nonce, System.currentTimeMillis());
        return prev == null;
    }

    /**
     * Periodic cleanup — remove nonces older than the drift window
     * to prevent unbounded memory growth.
     */
    public void evictExpired() {
        long cutoff = System.currentTimeMillis() - TIMESTAMP_DRIFT_SECONDS * 1000 * 2;
        // B-22 修复：清理 DB 中的过期 nonce
        if (nonceRecordRepository != null) {
            try {
                java.time.Instant cutoffInstant = java.time.Instant.ofEpochMilli(cutoff);
                var expired = nonceRecordRepository.findByCreatedAtBefore(cutoffInstant);
                if (!expired.isEmpty()) {
                    nonceRecordRepository.deleteAll(expired);
                    log.debug("Evicted {} expired nonces from DB", expired.size());
                }
            } catch (RuntimeException e) {
                log.warn("Failed to evict expired nonces from DB: {}", e.getMessage());
            }
        }
        // 同时清理内存中的过期 nonce（降级模式）
        seenNonces.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    /** Number of nonces currently tracked (memory store only; DB count not included). */
    public int size() { return seenNonces.size(); }
}
