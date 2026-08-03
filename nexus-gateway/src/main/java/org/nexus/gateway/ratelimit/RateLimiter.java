package org.nexus.gateway.ratelimit;

/**
 * Rate limiter interface. Implementations: InMemory (dev), Redis (prod).
 */
public interface RateLimiter {

    /**
     * Try to acquire a permit for the given key.
     * @return true if allowed, false if rate limited
     */
    boolean tryAcquire(String key);
}