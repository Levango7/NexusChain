package org.nexus.gateway.ratelimit;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory sliding window rate limiter for development.
 * 60 requests per minute per key.
 */
@Component
@Profile({"dev", "sandbox"})
public class InMemoryRateLimiter implements RateLimiter {

    private static final int MAX_PER_MINUTE = 300;
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key) {
        WindowCounter counter = counters.computeIfAbsent(key, k -> new WindowCounter());
        return counter.tryAcquire();
    }

    private static class WindowCounter {
        private volatile long windowStart = System.currentTimeMillis();
        private final AtomicInteger count = new AtomicInteger(0);

        boolean tryAcquire() {
            long now = System.currentTimeMillis();
            if (now - windowStart > 60_000) {
                windowStart = now;
                count.set(1);
                return true;
            }
            return count.incrementAndGet() <= MAX_PER_MINUTE;
        }
    }
}