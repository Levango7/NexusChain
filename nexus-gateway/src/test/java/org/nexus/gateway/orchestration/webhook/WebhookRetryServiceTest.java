package org.nexus.gateway.orchestration.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WebhookRetryService} 单元测试（P4-T5 / Wave 8-A5 增强）。
 *
 * <p>验证：
 * <ul>
 *   <li>指数退避基数：1s, 2s, 4s, 8s, 16s, 32s, 60s（封顶）, 60s</li>
 *   <li>抖动范围：0-50%</li>
 *   <li>最大重试次数默认 5（可配置）</li>
 *   <li>shouldRetry 边界</li>
 *   <li>calculateRetryDelay 方法（不含抖动）</li>
 *   <li>MAX_DELAY_MS 封顶 60s</li>
 * </ul>
 */
class WebhookRetryServiceTest {

    private final WebhookRetryService service = new WebhookRetryService();

    @Test
    @DisplayName("getBaseDelayMs: 返回正确的指数退避基数 1s,2s,4s,8s,16s,32s,60s,60s")
    void getBaseDelayMs_exponentialBackoff() {
        long[] expected = {1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], service.getBaseDelayMs(i),
                    "Base delay for attempt " + i + " should be " + expected[i] + "ms");
        }
    }

    @Test
    @DisplayName("getBaseDelayMs: 超出预计算范围时动态计算并封顶 60s")
    void getBaseDelayMs_beyondPrecomputed_cappedAt60s() {
        // attempt=10: 2^10 * 1s = 1024s → 封顶 60s
        assertEquals(60_000L, service.getBaseDelayMs(10));
        // attempt=20: 2^20 * 1s → 封顶 60s
        assertEquals(60_000L, service.getBaseDelayMs(20));
    }

    @Test
    @DisplayName("getBaseDelayMs: 负数 attempt 抛 IllegalArgumentException")
    void getBaseDelayMs_negativeThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.getBaseDelayMs(-1));
    }

    @Test
    @DisplayName("computeDelayMs: 含抖动，延迟在 [base, base*1.5) 范围内")
    void computeDelayMs_withinJitterRange() {
        for (int attempt = 0; attempt < service.getMaxRetries(); attempt++) {
            long base = service.getBaseDelayMs(attempt);
            for (int trial = 0; trial < 100; trial++) {
                long delay = service.computeDelayMs(attempt);
                assertTrue(delay >= base,
                        "Delay " + delay + " should be >= base " + base + " (attempt=" + attempt + ")");
                assertTrue(delay < base * 1.5,
                        "Delay " + delay + " should be < base*1.5 " + (base * 1.5) + " (attempt=" + attempt + ")");
            }
        }
    }

    @Test
    @DisplayName("computeDelayMs: 多次调用产生不同抖动值（随机性验证）")
    void computeDelayMs_randomJitter() {
        java.util.Set<Long> delays = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            delays.add(service.computeDelayMs(3)); // attempt=3, base=8s
        }
        assertTrue(delays.size() >= 2, "Random jitter should produce different delays: " + delays.size());
    }

    @Test
    @DisplayName("shouldRetry: attempt < maxRetries 时返回 true")
    void shouldRetry_withinMaxRetries() {
        for (int i = 0; i < service.getMaxRetries(); i++) {
            assertTrue(service.shouldRetry(i),
                    "Should retry for attempt " + i);
        }
    }

    @Test
    @DisplayName("shouldRetry: attempt >= maxRetries 时返回 false")
    void shouldRetry_exceedsMaxRetries() {
        assertFalse(service.shouldRetry(service.getMaxRetries()));
        assertFalse(service.shouldRetry(service.getMaxRetries() + 1));
        assertFalse(service.shouldRetry(100));
    }

    @Test
    @DisplayName("getMaxRetries: 默认返回 5")
    void getMaxRetries_default5() {
        assertEquals(5, service.getMaxRetries());
        assertEquals(WebhookRetryService.DEFAULT_MAX_RETRIES, service.getMaxRetries());
    }

    @Test
    @DisplayName("computeDelayMs: attempt 超出范围抛 IllegalArgumentException")
    void computeDelayMs_outOfRange_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.computeDelayMs(-1));
        assertThrows(IllegalArgumentException.class, () -> service.computeDelayMs(service.getMaxRetries()));
        assertThrows(IllegalArgumentException.class, () -> service.computeDelayMs(100));
    }

    @Test
    @DisplayName("awaitRetry: 正常等待返回 true")
    void awaitRetry_normalReturn() throws InterruptedException {
        long start = System.currentTimeMillis();
        boolean result = service.awaitRetry(0);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(result, "awaitRetry should return true on normal completion");
        assertTrue(elapsed >= 1_000, "Should wait at least 1s (base delay)");
        assertTrue(elapsed < 2_000, "Should wait less than 2s (base + max jitter)");
    }

    @Test
    @DisplayName("awaitRetry: 线程被中断时返回 false 并恢复中断标志")
    void awaitRetry_interrupted() throws Exception {
        Thread t = new Thread(() -> {
            Thread.currentThread().interrupt();
            boolean result = service.awaitRetry(4); // 16s base, would block long
            assertFalse(result, "awaitRetry should return false when interrupted");
            assertTrue(Thread.currentThread().isInterrupted(),
                    "Interrupt flag should be restored");
        });
        t.start();
        t.join(5_000);
        assertFalse(t.isAlive(), "Thread should have completed");
    }

    // ─── Wave 8-A5 新增测试 ──────────────────────────────────────────────────

    @Test
    @DisplayName("calculateRetryDelay: 返回不含抖动的基础延迟")
    void calculateRetryDelay_returnsBaseDelay() {
        assertEquals(1_000L, service.calculateRetryDelay(0));
        assertEquals(2_000L, service.calculateRetryDelay(1));
        assertEquals(4_000L, service.calculateRetryDelay(2));
        assertEquals(8_000L, service.calculateRetryDelay(3));
        assertEquals(16_000L, service.calculateRetryDelay(4));
    }

    @Test
    @DisplayName("calculateRetryDelay: 超出预计算范围时封顶 60s")
    void calculateRetryDelay_cappedAt60s() {
        assertEquals(60_000L, service.calculateRetryDelay(6));
        assertEquals(60_000L, service.calculateRetryDelay(10));
        assertEquals(60_000L, service.calculateRetryDelay(20));
    }

    @Test
    @DisplayName("calculateRetryDelay: 负数 retryCount 抛 IllegalArgumentException")
    void calculateRetryDelay_negativeThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.calculateRetryDelay(-1));
    }

    @Test
    @DisplayName("自定义 maxRetries=3 的 WebhookRetryService")
    void customMaxRetries() {
        WebhookRetryService customService = new WebhookRetryService(3);
        assertEquals(3, customService.getMaxRetries());
        assertTrue(customService.shouldRetry(2));
        assertFalse(customService.shouldRetry(3));
    }

    @Test
    @DisplayName("maxRetries=0 时不应重试")
    void zeroMaxRetries_noRetry() {
        WebhookRetryService noRetryService = new WebhookRetryService(0);
        assertEquals(0, noRetryService.getMaxRetries());
        assertFalse(noRetryService.shouldRetry(0));
    }

    @Test
    @DisplayName("负数 maxRetries 抛 IllegalArgumentException")
    void negativeMaxRetries_throws() {
        assertThrows(IllegalArgumentException.class, () -> new WebhookRetryService(-1));
    }

    @Test
    @DisplayName("MAX_DELAY_MS 常量值为 60s")
    void maxDelayMs_constant() {
        assertEquals(60_000L, WebhookRetryService.MAX_DELAY_MS);
    }

    @Test
    @DisplayName("DEFAULT_MAX_RETRIES 常量值为 5")
    void defaultMaxRetries_constant() {
        assertEquals(5, WebhookRetryService.DEFAULT_MAX_RETRIES);
    }
}
