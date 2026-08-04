package org.nexus.bridge.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-replay protection for bridge validator signatures.
 *
 * <p>Each bridge operation (mint / unlock) carries a timestamp and a
 * unique nonce.  This class validates freshness and prevents the same
 * signed payload from being accepted twice.</p>
 *
 * <p>In a multi-instance deployment, the nonce store MUST be backed by
 * Redis (SET NX with TTL) instead of this in-memory implementation.</p>
 */
public class ReplayProtection {

    /** Maximum allowed clock skew between validator and bridge (seconds). */
    private static final long TIMESTAMP_DRIFT_SECONDS = 300; // 5 min

    /** In-memory nonce store for single-instance deployments. */
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

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
     * @param nonce  hex-encoded nonce from the signed payload
     * @throws SecurityException if the nonce has been used before
     */
    public void checkAndRecordNonce(String nonce) {
        Long prev = seenNonces.putIfAbsent(nonce, System.currentTimeMillis());
        if (prev != null) {
            throw new SecurityException("Duplicate nonce — possible replay attack: " + nonce.substring(0, 16) + "...");
        }
    }

    /**
     * Periodic cleanup — remove nonces older than the drift window
     * to prevent unbounded memory growth.
     */
    public void evictExpired() {
        long cutoff = System.currentTimeMillis() - TIMESTAMP_DRIFT_SECONDS * 1000 * 2;
        seenNonces.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    /** Number of nonces currently tracked. */
    public int size() { return seenNonces.size(); }
}
