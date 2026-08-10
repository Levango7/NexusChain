package org.nexus.gateway.orchestration.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Webhook 重试策略服务：指数退避 + 随机抖动（P4-T5）。
 *
 * <p>重试调度：
 * <ul>
 *   <li>初始延迟 1s，指数退避 {@code 2^n}：1s, 2s, 4s, 8s, 16s, 32s, 64s, 128s</li>
 *   <li>每次重试附加 0-50% 随机抖动，避免惊群效应（thundering herd）</li>
 *   <li>最大重试次数 8 次（首次投递 + 8 次重试 = 9 次尝试）</li>
 *   <li>超过最大重试次数后，调用方应将消息发送到死信队列</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>纯函数式计算，无副作用，便于单元测试</li>
 *   <li>{@link #computeDelayMs(int)} 返回第 {@code attempt} 次重试的延迟（含抖动）</li>
 *   <li>{@link #shouldRetry(int)} 判断当前重试次数是否还在允许范围内</li>
 *   <li>抖动使用 {@link ThreadLocalRandom} 避免竞争，测试可通过注入 {@link Random} 替换</li>
 * </ul>
 *
 * @since Phase 4 - P4-T5 Webhook 重试与死信队列增强
 */
@Component
public class WebhookRetryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookRetryService.class);

    /** 最大重试次数（不含首次投递）。 */
    public static final int MAX_RETRIES = 8;

    /** 初始延迟基数（毫秒）：1s。 */
    public static final long INITIAL_DELAY_MS = 1_000L;

    /** 抖动因子上限：0.5 表示 0-50% 随机抖动。 */
    public static final double JITTER_FACTOR = 0.5;

    /** 重试间隔基数（毫秒）：2^n * INITIAL_DELAY_MS。预计算便于 getBaseDelayMs 查询。 */
    static final long[] BASE_DELAYS_MS = {
            1_000L,     // 2^0 * 1s = 1s
            2_000L,     // 2^1 * 1s = 2s
            4_000L,     // 2^2 * 1s = 4s
            8_000L,     // 2^3 * 1s = 8s
            16_000L,    // 2^4 * 1s = 16s
            32_000L,    // 2^5 * 1s = 32s
            64_000L,    // 2^6 * 1s = 64s
            128_000L    // 2^7 * 1s = 128s
    };

    /**
     * 判断是否应该继续重试。
     *
     * @param attempt 当前已重试次数（0 表示首次投递失败，准备第 1 次重试）
     * @return {@code true} 若 {@code attempt < MAX_RETRIES}
     */
    public boolean shouldRetry(int attempt) {
        return attempt < MAX_RETRIES;
    }

    /**
     * 计算第 {@code attempt} 次重试的延迟（含随机抖动）。
     *
     * <p>延迟 = baseDelay * (1 + jitter)，其中：
     * <ul>
     *   <li>baseDelay = 2^attempt * INITIAL_DELAY_MS</li>
     *   <li>jitter ∈ [0, JITTER_FACTOR)（即 0-50%）</li>
     * </ul>
     *
     * @param attempt 重试次数（0-based，0 表示第 1 次重试）
     * @return 延迟毫秒数（含抖动）
     * @throws IllegalArgumentException 若 attempt 超出 {@link #MAX_RETRIES}
     */
    public long computeDelayMs(int attempt) {
        if (attempt < 0 || attempt >= MAX_RETRIES) {
            throw new IllegalArgumentException(
                    "attempt " + attempt + " out of range [0, " + MAX_RETRIES + ")");
        }
        long baseDelay = BASE_DELAYS_MS[attempt];
        long jitter = computeJitterMs(baseDelay);
        long total = baseDelay + jitter;
        log.trace("Retry delay: attempt={}, base={}ms, jitter={}ms, total={}ms",
                attempt, baseDelay, jitter, total);
        return total;
    }

    /**
     * 计算抖动毫秒数：{@code baseDelay * random[0, JITTER_FACTOR)}。
     *
     * <p>使用 {@link ThreadLocalRandom} 避免多线程竞争，且无需创建 Random 实例。
     */
    long computeJitterMs(long baseDelay) {
        double jitterRatio = ThreadLocalRandom.current().nextDouble(0.0, JITTER_FACTOR);
        return (long) (baseDelay * jitterRatio);
    }

    /**
     * 获取第 {@code attempt} 次重试的基础延迟（不含抖动），用于日志/监控/测试。
     *
     * @param attempt 重试次数（0-based）
     * @return 基础延迟毫秒数
     */
    public long getBaseDelayMs(int attempt) {
        if (attempt < 0 || attempt >= MAX_RETRIES) {
            throw new IllegalArgumentException(
                    "attempt " + attempt + " out of range [0, " + MAX_RETRIES + ")");
        }
        return BASE_DELAYS_MS[attempt];
    }

    /**
     * 获取最大重试次数。
     */
    public int getMaxRetries() {
        return MAX_RETRIES;
    }

    /**
     * 阻塞当前线程直到重试延迟到期。
     *
     * <p>响应中断：若线程被中断，立即返回并恢复中断标志。
     *
     * @param attempt 重试次数（0-based）
     * @return {@code true} 若正常等待到期；{@code false} 若被中断
     */
    public boolean awaitRetry(int attempt) {
        long delay = computeDelayMs(attempt);
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}