package org.nexus.gateway.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ReplayNonceStore 单元测试（短期项 #4b）。
 *
 * <p>覆盖：内存版首次/重复语义；Redis 版 SET NX 透传、
 * 异常 fail-closed（返回 false，绝不静默放行重放）。</p>
 */
class ReplayNonceStoreTest {

    // ===== InMemory =====

    @Test
    @DisplayName("InMemory: 首次 true、窗口内重复 false、过期后可复用")
    void inMemory_registerSemantics() {
        InMemoryReplayNonceStore store = new InMemoryReplayNonceStore();
        assertTrue(store.register("n1", 60_000), "首次登记放行");
        assertFalse(store.register("n1", 60_000), "窗口内重复拒绝");
        assertTrue(store.register("n2", 60_000), "不同 nonce 各自独立");
        // ttl=0 → 立即过期，可复用（窗口语义边界）
        assertTrue(store.register("n3", 0), "ttl=0 首次放行");
        assertTrue(store.register("n3", 60_000), "ttl=0 已过期，二次登记放行");
    }

    // ===== Redis =====

    @Test
    @DisplayName("Redis: SET NX 首次 true、已存在 false（key/TTL 透传）")
    void redis_setNxSemantics() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true)   // 首次
                .thenReturn(false); // 重复
        RedisReplayNonceStore store = new RedisReplayNonceStore(template);

        assertTrue(store.register("n1", 300_000), "SET NX 成功 → 放行");
        assertFalse(store.register("n1", 300_000), "SET NX 失败 → 重放拒绝");
        verify(ops, times(2)).setIfAbsent(eq(RedisReplayNonceStore.KEY_PREFIX + "n1"), eq("1"), any(Duration.class));
    }

    @Test
    @DisplayName("Redis: 连接异常 fail-closed（返回 false，不静默放行）")
    void redis_failureFailsClosed() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), any(), any(Duration.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("conn refused"));
        RedisReplayNonceStore store = new RedisReplayNonceStore(template);

        assertFalse(store.register("n1", 300_000), "Redis 故障必须 fail-closed（视为重放拒绝）");
    }

    @Test
    @DisplayName("Redis: setIfAbsent 返回 null（pipeline/事务边界）按 false 处理")
    void redis_nullResultTreatedAsReplay() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(null);
        RedisReplayNonceStore store = new RedisReplayNonceStore(template);

        assertFalse(store.register("n1", 300_000), "null 结果不得等价放行");
    }
}
