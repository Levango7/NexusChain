package org.nexus.gateway.ratelimit;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis-backed sliding window rate limiter for production.
 * 60 requests per minute per key using Redis INCR + EXPIRE.
 */
@Component
@Profile("prod")
public class RedisRateLimiter implements RateLimiter {

    private static final String PREFIX = "nexus:ratelimit:";
    private static final int MAX_PER_MINUTE = 300;

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(String key) {
        String redisKey = PREFIX + key;
        Long count = redisTemplate.opsForValue().increment(redisKey);
        if (count != null && count == 1L) {
            redisTemplate.expire(redisKey, 60, TimeUnit.SECONDS);
        }
        return count != null && count <= MAX_PER_MINUTE;
    }
}