package org.nexus.gateway.ratelimit;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis-backed idempotency store for production (distributed, TTL 24h).
 */
@Component
@Profile("prod")
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String PREFIX = "nexus:idempotency:";
    private static final long TTL_HOURS = 24;

    private final StringRedisTemplate redisTemplate;

    public RedisIdempotencyStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String get(String idempotencyKey) {
        return redisTemplate.opsForValue().get(PREFIX + idempotencyKey);
    }

    @Override
    public void put(String idempotencyKey, String value) {
        redisTemplate.opsForValue().set(PREFIX + idempotencyKey, value, TTL_HOURS, TimeUnit.HOURS);
    }
}