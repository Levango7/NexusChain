package org.nexus.gateway.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis-backed idempotency store for production (distributed, configurable TTL).
 *
 * <p>Wave 12 增强：TTL 从硬编码 24 小时改为 {@code @Value} 配置注入，
 * 默认 24 小时，范围 1~168 小时。与 {@code ReplayProtectionConfigService}
 * 的默认值保持一致。</p>
 *
 * <p>设计依据：Wave 12 设计文档 §6.4.2。</p>
 */
@Component
@Profile("prod")
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String PREFIX = "nexus:idempotency:";

    private final StringRedisTemplate redisTemplate;
    private final int ttlHours;

    public RedisIdempotencyStore(
            StringRedisTemplate redisTemplate,
            @Value("${nexus.security.idempotency-ttl-hours:24}") int ttlHours) {
        this.redisTemplate = redisTemplate;
        this.ttlHours = Math.max(1, Math.min(168, ttlHours));
    }

    @Override
    public String get(String idempotencyKey) {
        return redisTemplate.opsForValue().get(PREFIX + idempotencyKey);
    }

    @Override
    public void put(String idempotencyKey, String value) {
        redisTemplate.opsForValue().set(PREFIX + idempotencyKey, value, ttlHours, TimeUnit.HOURS);
    }

    @Override
    public boolean putIfAbsent(String idempotencyKey, String value) {
        // Redis SET NX EX：单条命令完成「不存在则写入 + 设置 TTL」，天然原子。
        // 返回 null 表示未写入（key 已存在）。
        Boolean written = redisTemplate.opsForValue()
                .setIfAbsent(PREFIX + idempotencyKey, value, ttlHours, TimeUnit.HOURS);
        return Boolean.TRUE.equals(written);
    }

    @Override
    public void remove(String idempotencyKey) {
        redisTemplate.delete(PREFIX + idempotencyKey);
    }
}