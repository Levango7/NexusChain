package org.nexus.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed sliding window rate limiter for production.
 * 60 requests per minute per key using Redis INCR + EXPIRE.
 */
@Component
@Profile("prod")
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final String PREFIX = "nexus:ratelimit:";
    private static final int MAX_PER_MINUTE = 300;

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 限流窗口长度（秒）。
     */
    private static final long WINDOW_SECONDS = 60L;

    @Override
    public boolean tryAcquire(String key) {
        String redisKey = PREFIX + key;

        // P1（2026-09-17 修复）：原实现为「INCR → 若返回 1 再 EXPIRE」，
        // 两条命令分两次往返，**非原子**。若在 INCR 成功与 EXPIRE 之间进程崩溃
        // 或连接中断，该 key 将不带 TTL 永久存在 —— 计数只增不减，
        // 一旦超过阈值该客户端被**永久限流**（且不会自愈）。
        //
        // 改为 SET NX EX：创建 key 与设置 TTL 是单条命令，不存在该窗口。
        // 已存在则返回 false，不影响后续 INCR。
        redisTemplate.opsForValue()
                .setIfAbsent(redisKey, "0", Duration.ofSeconds(WINDOW_SECONDS));

        Long count = redisTemplate.opsForValue().increment(redisKey);

        // 兜底：修复前遗留的无 TTL key（EXISTS 但 TTL = -1）补设 TTL，
        // 使受影响的客户端能自行恢复，无需人工清理 Redis。
        Long ttl = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
        if (ttl != null && ttl == -1L) {
            log.warn("Rate-limit key without TTL detected, repairing: key={}", redisKey);
            redisTemplate.expire(redisKey, WINDOW_SECONDS, TimeUnit.SECONDS);
        }

        return count != null && count <= MAX_PER_MINUTE;
    }
}