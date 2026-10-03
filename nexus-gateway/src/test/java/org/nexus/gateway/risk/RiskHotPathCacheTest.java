package org.nexus.gateway.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RiskHotPathCache} 单元测试：TTL 过期、同 key 单飞回源、异常不缓存、非法 TTL。
 */
class RiskHotPathCacheTest {

    @Test
    @DisplayName("TTL 内直接命中：loader 只执行一次")
    void hitWithinTtl() {
        RiskHotPathCache<String> cache = new RiskHotPathCache<>(60_000L);
        AtomicInteger loads = new AtomicInteger();
        String v1 = cache.get("k", () -> {
            loads.incrementAndGet();
            return "value";
        });
        String v2 = cache.get("k", () -> {
            loads.incrementAndGet();
            return "stale-should-not-run";
        });
        assertEquals("value", v1);
        assertEquals("value", v2);
        assertEquals(1, loads.get());
    }

    @Test
    @DisplayName("TTL 过期后穿透回源刷新")
    void expireThenReload() throws InterruptedException {
        RiskHotPathCache<Integer> cache = new RiskHotPathCache<>(20L);
        assertEquals(1, cache.get("k", () -> 1).intValue());
        Thread.sleep(60L);
        assertEquals(2, cache.get("k", () -> 2).intValue());
    }

    @Test
    @DisplayName("loader 抛异常：异常透传且不缓存毒值，下次重试")
    void exceptionNotCached() {
        RiskHotPathCache<Integer> cache = new RiskHotPathCache<>(60_000L);
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () ->
                cache.get("k", () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("db down");
                }));
        // compute 内 loader 抛异常 → 不落任何条目（无毒值缓存）
        assertEquals(0, cache.size());
        assertEquals(3, cache.get("k", () -> 3).intValue());
        // 只有第一个 loader 增计数：异常路径 1 次即止，重试走的是新 loader
        assertEquals(1, attempts.get());
    }

    @Test
    @DisplayName("TTL 非正数：构造即拒绝")
    void nonPositiveTtlRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RiskHotPathCache<>(0));
        assertThrows(IllegalArgumentException.class, () -> new RiskHotPathCache<>(-1));
    }
}
