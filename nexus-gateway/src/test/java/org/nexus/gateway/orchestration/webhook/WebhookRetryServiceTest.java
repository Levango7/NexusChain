package org.nexus.gateway.orchestration.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WebhookRetryService} 单元测试（P4-T5）。
 *
 * <p>验证：
 * <ul>
 *   <li>指数退避基数：1s, 2s, 4s, 8s, 16s, 32s, 64s, 128s</li>
 *   <li>抖动范围：0-50%</li>
 *   <li>最大重试次数 8</li>
 *   <li>shouldRetry 边界</li>
 * </ul>
 */
class WebhookRetryServiceTest {

    private final WebhookRetryService service = new WebhookRetryService();

    @Test
    @DisplayName("getBaseDelayMs: 返回正确的指数退避基数 1s,2s,4s,...,128s")
    void getBaseDelayMs_exponentialBackoff() {
        long[] expected = {1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 128_000L};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], service.getBaseDelayMs(i),
                    "Base delay for attempt " + i + " should be " + expected[i] + "ms");
        }
    }

    @Test
    @DisplayName("computeDelayMs: 含抖动，延迟在 [base, base*1.5) 范围内")
    void computeDelayMs_withinJitterRange() {
        for (int attempt = 0; attempt < WebhookRetryService.MAX_RETRIES; attempt++) {
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
        // 抖动是随机的，多次调用应该产生不同的值（概率上）
        java.util.Set<Long> delays = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            delays.add(service.computeDelayMs(3)); // attempt=3, base=8s
        }
        // 至少应该有 2 个不同的值（极大概率）
        assertTrue(delays.size() >= 2, "Random jitter should produce different delays: " + delays.size());
    }

    @Test
    @DisplayName("shouldRetry: attempt < 8 时返回 true")
    void shouldRetry_withinMaxRetries() {
        for (int i = 0; i < WebhookRetryService.MAX_RETRIES; i++) {
            assertTrue(service.shouldRetry(i),
                    "Should retry for attempt " + i);
        }
    }

    @Test
    @DisplayName("shouldRetry: attempt >= 8 时返回 false")
    void shouldRetry_exceedsMaxRetries() {
        assertFalse(service.shouldRetry(WebhookRetryService.MAX_RETRIES));
        assertFalse(service.shouldRetry(WebhookRetryService.MAX_RETRIES + 1));
        assertFalse(service.shouldRetry(100));
    }

    @Test
    @DisplayName("getMaxRetries: 返回 8")
    void getMaxRetries() {
        assertEquals(8, service.getMaxRetries());
        assertEquals(WebhookRetryService.MAX_RETRIES, service.getMaxRetries());
    }

    @Test
    @DisplayName("computeDelayMs: attempt 超出范围抛 IllegalArgumentException")
    void computeDelayMs_outOfRange_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.computeDelayMs(-1));
        assertThrows(IllegalArgumentException.class, () -> service.computeDelayMs(WebhookRetryService.MAX_RETRIES));
        assertThrows(IllegalArgumentException.class, () -> service.computeDelayMs(100));
    }

    @Test
    @DisplayName("getBaseDelayMs: attempt 超出范围抛 IllegalArgumentException")
    void getBaseDelayMs_outOfRange_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.getBaseDelayMs(-1));
        assertThrows(IllegalArgumentException.class, () -> service.getBaseDelayMs(WebhookRetryService.MAX_RETRIES));
    }

    @Test
    @DisplayName("awaitRetry: 正常等待返回 true")
    void awaitRetry_normalReturn() throws InterruptedException {
        // 使用 attempt=0，base=1s，含抖动最多 1.5s
        // 为避免测试过慢，用单独线程验证
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
            // 模拟中断
            Thread.currentThread().interrupt();
            boolean result = service.awaitRetry(7); // 128s base, would block long
            assertFalse(result, "awaitRetry should return false when interrupted");
            assertTrue(Thread.currentThread().isInterrupted(),
                    "Interrupt flag should be restored");
        });
        t.start();
        t.join(5_000);
        assertFalse(t.isAlive(), "Thread should have completed");
    }
}