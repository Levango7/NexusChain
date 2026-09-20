package org.nexus.gateway.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版防重放 nonce 存储（单实例语义：dev / sandbox / test）。
 *
 * <p>沿用原 {@code RequestSignatureInterceptor#seenNonces} 的
 * ConcurrentHashMap + 过期惰性驱逐逻辑，行为不变。</p>
 */
public class InMemoryReplayNonceStore implements ReplayNonceStore {

    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    @Override
    public boolean register(String nonce, long ttlMillis) {
        long now = System.currentTimeMillis();
        long expiry = now + ttlMillis;
        Long previous = seenNonces.put(nonce, expiry);
        // 惰性驱逐过期项（防无界增长）
        seenNonces.entrySet().removeIf(e -> e.getValue() < now);
        return previous == null || previous <= now;
    }
}
