package org.nexus.gateway.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 限流 / 幂等存储单元测试：InMemory + Redis 双实现。
 */
class RatelimitTest {

    // === InMemoryIdempotencyStore ===

    @Test
    @DisplayName("InMemoryIdempotencyStore: put/get 基本契约")
    void inMemoryIdempotency_putGet() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        assertNull(store.get("k1"));
        store.put("k1", "v1");
        assertEquals("v1", store.get("k1"));
    }

    // === InMemoryRateLimiter ===

    @Test
    @DisplayName("InMemoryRateLimiter: 首次获取通过")
    void inMemoryRateLimiter_firstAcquire() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter();
        assertTrue(limiter.tryAcquire("k1"));
    }

    @Test
    @DisplayName("InMemoryRateLimiter: 同 key 多次获取最终被限流")
    void inMemoryRateLimiter_throttleAtLimit() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter();
        boolean allowed = true;
        int lastAllowed = 0;
        for (int i = 0; i < 500; i++) {
            allowed = limiter.tryAcquire("throttle");
            if (allowed) lastAllowed = i + 1;
        }
        // 上限 300，最终一次应被拒
        assertFalse(allowed);
        assertTrue(lastAllowed <= 300);
    }

    // === RedisIdempotencyStore ===

    @Test
    @DisplayName("RedisIdempotencyStore: get 委托 redisTemplate.opsForValue().get")
    void redisIdempotency_get() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("nexus:idempotency:k1")).thenReturn("v1");

        RedisIdempotencyStore store = new RedisIdempotencyStore(redis, 24);
        assertEquals("v1", store.get("k1"));
    }

    @Test
    @DisplayName("RedisIdempotencyStore: put 带 24h TTL")
    void redisIdempotency_put() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        RedisIdempotencyStore store = new RedisIdempotencyStore(redis, 24);
        store.put("k1", "v1");

        verify(ops).set("nexus:idempotency:k1", "v1", 24, TimeUnit.HOURS);
    }

    // === RedisRateLimiter ===

    /**
     * P1（2026-09-17 契约变更）：原实现为「INCR → 若返回 1 再 EXPIRE」两条命令，
     * 非原子 —— 若在两者之间进程崩溃，key 将不带 TTL 永久存在，该客户端被永久限流。
     * 现改为「SET NX EX 创建即带 TTL」。
     * 本用例断言随之调整：不再期望显式 expire（TTL 由 setIfAbsent 原子设置）。
     */
    @Test
    @DisplayName("RedisRateLimiter: 以 SET NX EX 原子设置 TTL，返回 true")
    void redisRateLimiter_firstAcquire() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("nexus:ratelimit:k1")).thenReturn(1L);

        RedisRateLimiter limiter = new RedisRateLimiter(redis);
        assertTrue(limiter.tryAcquire("k1"));

        // 创建 key 与设置 TTL 必须是单条原子命令
        verify(ops).setIfAbsent("nexus:ratelimit:k1", "0", Duration.ofSeconds(60));
        // TTL 已由 setIfAbsent 保证，无需额外 expire（getExpire 返回 null → 不触发修复分支）
        verify(redis, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    /**
     * 修复分支：修复前遗留的「存在但无 TTL」key（getExpire 返回 -1）应被补设 TTL，
     * 使受影响客户端能自行恢复，无需人工清理 Redis。
     */
    @Test
    @DisplayName("RedisRateLimiter: 遗留无 TTL key 应被补设 TTL")
    void redisRateLimiter_repairsLegacyKeyWithoutTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("nexus:ratelimit:k1")).thenReturn(5L);
        when(redis.getExpire("nexus:ratelimit:k1", TimeUnit.SECONDS)).thenReturn(-1L);

        RedisRateLimiter limiter = new RedisRateLimiter(redis);
        assertTrue(limiter.tryAcquire("k1"));

        verify(redis).expire("nexus:ratelimit:k1", 60, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("RedisRateLimiter: increment > 1 不再 expire；未超限返回 true")
    void redisRateLimiter_subsequentAcquire() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("nexus:ratelimit:k1")).thenReturn(2L);

        RedisRateLimiter limiter = new RedisRateLimiter(redis);
        assertTrue(limiter.tryAcquire("k1"));
        verify(redis, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("RedisRateLimiter: increment 超过上限返回 false")
    void redisRateLimiter_overLimit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment("nexus:ratelimit:k1")).thenReturn(301L);

        RedisRateLimiter limiter = new RedisRateLimiter(redis);
        assertFalse(limiter.tryAcquire("k1"));
    }

    @Test
    @DisplayName("RedisRateLimiter: increment 返回 null 时返回 false")
    void redisRateLimiter_nullCount() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(null);

        RedisRateLimiter limiter = new RedisRateLimiter(redis);
        assertFalse(limiter.tryAcquire("k1"));
    }
}