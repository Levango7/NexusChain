package org.nexus.gateway.orchestration.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Webhook 重试策略服务：指数退避 + 随机抖动（P4-T5 / Wave 8-A5 增强）。
 *
 * <p>重试调度（Wave 8-A5 增强后）：
 * <ul>
 *   <li>初始延迟 1s，指数退避 {@code 2^n}：1s, 2s, 4s, 8s, 16s</li>
 *   <li>最大延迟封顶 60s（不再无限指数增长）</li>
 *   <li>每次重试附加 0-50% 随机抖动，避免惊群效应（thundering herd）</li>
 *   <li>最大重试次数默认 5 次（可配置 {@code nexus.webhook.retry.max-retries}）</li>
 *   <li>超过最大重试次数后，调用方应将消息发送到死信队列</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>纯函数式计算，无副作用，便于单元测试</li>
 *   <li>{@link #computeDelayMs(int)} 返回第 {@code attempt} 次重试的延迟（含抖动）</li>
 *   <li>{@link #calculateRetryDelay(int)} 返回第 {@code retryCount} 次重试的基础延迟（不含抖动，毫秒）</li>
 *   <li>{@link #shouldRetry(int)} 判断当前重试次数是否还在允许范围内</li>
 *   <li>抖动使用 {@link ThreadLocalRandom} 避免竞争</li>
 * </ul>
 *
 * @since Phase 4 - P4-T5 Webhook 重试与死信队列增强 / Wave 8-A5 指数退避增强
 */
@Component
public class WebhookRetryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookRetryService.class);

    /** 默认最大重试次数（不含首次投递）。 */
    public static final int DEFAULT_MAX_RETRIES = 5;

    /** 初始延迟基数（毫秒）：1s。 */
    public static final long INITIAL_DELAY_MS = 1_000L;

    /** 最大延迟封顶（毫秒）：60s。 */
    public static final long MAX_DELAY_MS = 60_000L;

    /** 抖动因子上限：0.5 表示 0-50% 随机抖动。 */
    public static final double JITTER_FACTOR = 0.5;

    /** 最大重试次数（不含首次投递），可通过配置覆盖。 */
    private final int maxRetries;

    /**
     * 重试间隔基数（毫秒）：指数退避 2^n * INITIAL_DELAY_MS，封顶 MAX_DELAY_MS。
     * 预计算便于 getBaseDelayMs 查询。
     * 1s, 2s, 4s, 8s, 16s, 32s(→封顶60s), 60s, 60s, ...
     */
    static final long[] BASE_DELAYS_MS = {
            1_000L,      // 2^0 * 1s = 1s
            2_000L,      // 2^1 * 1s = 2s
            4_000L,      // 2^2 * 1s = 4s
            8_000L,      // 2^3 * 1s = 8s
            16_000L,     // 2^4 * 1s = 16s
            32_000L,     // 2^5 * 1s = 32s
            60_000L,     // 2^6 * 1s = 64s → 封顶 60s
            60_000L      // 2^7 * 1s = 128s → 封顶 60s
    };

    /**
     * 默认构造器：使用默认最大重试次数 5。
     */
    public WebhookRetryService() {
        this(DEFAULT_MAX_RETRIES);
    }

    /**
     * 配置构造器：通过 {@code nexus.webhook.retry.max-retries} 注入最大重试次数。
     */
    public WebhookRetryService(@Value("${nexus.webhook.retry.max-retries:" + DEFAULT_MAX_RETRIES + "}") int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got: " + maxRetries);
        }
        this.maxRetries = maxRetries;
    }

    /**
     * 判断是否应该继续重试。
     *
     * @param attempt 当前已重试次数（0 表示首次投递失败，准备第 1 次重试）
     * @return {@code true} 若 {@code attempt < maxRetries}
     */
    public boolean shouldRetry(int attempt) {
        return attempt < maxRetries;
    }

    /**
     * 计算第 {@code attempt} 次重试的延迟（含随机抖动）。
     *
     * <p>延迟 = baseDelay * (1 + jitter)，其中：
     * <ul>
     *   <li>baseDelay = min(2^attempt * INITIAL_DELAY_MS, MAX_DELAY_MS)</li>
     *   <li>jitter ∈ [0, JITTER_FACTOR)（即 0-50%）</li>
     * </ul>
     *
     * @param attempt 重试次数（0-based，0 表示第 1 次重试）
     * @return 延迟毫秒数（含抖动）
     * @throws IllegalArgumentException 若 attempt 超出 {@link #maxRetries}
     */
    public long computeDelayMs(int attempt) {
        if (attempt < 0 || attempt >= maxRetries) {
            throw new IllegalArgumentException(
                    "attempt " + attempt + " out of range [0, " + maxRetries + ")");
        }
        long baseDelay = getBaseDelayMs(attempt);
        long jitter = computeJitterMs(baseDelay);
        long total = baseDelay + jitter;
        log.trace("Retry delay: attempt={}, base={}ms, jitter={}ms, total={}ms",
                attempt, baseDelay, jitter, total);
        return total;
    }

    /**
     * 计算重试延迟（不含抖动），供外部调用方使用。
     *
     * <p>Wave 8-A5 新增方法：返回第 {@code retryCount} 次重试的基础延迟（毫秒），
     * 不含随机抖动，便于调度框架（如 ScheduledExecutor）精确安排重试时间。
     *
     * <p>指数退避序列：1s → 2s → 4s → 8s → 16s → 32s → 60s（封顶）→ 60s → ...
     *
     * @param retryCount 重试次数（0-based，0 表示第 1 次重试）
     * @return 基础延迟毫秒数（不含抖动）
     * @throws IllegalArgumentException 若 retryCount 为负数
     */
    public long calculateRetryDelay(int retryCount) {
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must be >= 0, got: " + retryCount);
        }
        return getBaseDelayMs(retryCount);
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
     * <p>指数退避：2^attempt * INITIAL_DELAY_MS，封顶 MAX_DELAY_MS。
     *
     * @param attempt 重试次数（0-based）
     * @return 基础延迟毫秒数
     */
    public long getBaseDelayMs(int attempt) {
        if (attempt < 0) {
            throw new IllegalArgumentException(
                    "attempt " + attempt + " out of range (must be >= 0)");
        }
        // 使用预计算数组（若在范围内），否则动态计算并封顶
        if (attempt < BASE_DELAYS_MS.length) {
            return BASE_DELAYS_MS[attempt];
        }
        // 超出预计算范围时，动态计算并封顶
        long exponentialDelay = (1L << attempt) * INITIAL_DELAY_MS;
        return Math.min(exponentialDelay, MAX_DELAY_MS);
    }

    /**
     * 获取最大重试次数。
     */
    public int getMaxRetries() {
        return maxRetries;
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
