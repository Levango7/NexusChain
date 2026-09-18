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

    @Override
    public boolean putIfAbsent(String idempotencyKey, String value) {
        // Redis SET NX EX：单条命令完成「不存在则写入 + 设置 TTL」，天然原子。
        // 返回 null 表示未写入（key 已存在）。
        Boolean written = redisTemplate.opsForValue()
                .setIfAbsent(PREFIX + idempotencyKey, value, TTL_HOURS, TimeUnit.HOURS);
        return Boolean.TRUE.equals(written);
    }

    @Override
    public void remove(String idempotencyKey) {
        redisTemplate.delete(PREFIX + idempotencyKey);
    }
}