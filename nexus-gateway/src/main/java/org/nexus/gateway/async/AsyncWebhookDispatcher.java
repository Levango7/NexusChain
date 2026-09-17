package org.nexus.gateway.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;

/**
 * C3: Async webhook delivery via in-process queue (dev) or RabbitMQ (prod).
 * Decouples payment confirmation from webhook delivery.
 * Supports retry with exponential backoff and dead-letter after 3 failures.
 */
@Component
@Profile({"dev", "sandbox"})
public class AsyncWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AsyncWebhookDispatcher.class);
    private static final int MAX_RETRIES = 3;

    /** 工作线程数。 */
    private static final int WORKER_THREADS = 4;

    /**
     * 待投递队列容量（P1，2026-09-17 修复）。
     *
     * <p>原实现用 {@code Executors.newFixedThreadPool(4)} —— 其内部是
     * <b>无界</b> {@code LinkedBlockingQueue}。下游（商户回调端点）变慢或不可达时，
     * 待投递任务会无上限堆积，最终 OOM；且无界队列让线程池永远不会触发拒绝策略，
     * 过载时没有任何反馈信号。</p>
     */
    private static final int QUEUE_CAPACITY = 1_000;

    /** 死信队列容量上限，避免持续失败时同样无界增长。 */
    private static final int DEAD_LETTER_CAPACITY = 1_000;

    /** 有界队列 + 显式拒绝策略（队列满时抛 RejectedExecutionException，由 dispatch 处理）。 */
    private final ExecutorService executor = new ThreadPoolExecutor(
            WORKER_THREADS, WORKER_THREADS,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            new ThreadPoolExecutor.AbortPolicy());

    private final BlockingQueue<WebhookTask> deadLetterQueue =
            new ArrayBlockingQueue<>(DEAD_LETTER_CAPACITY);

    /**
     * Submit a webhook for async delivery.
     *
     * <p>队列满时不再静默丢弃：记录 ERROR 并计入死信（死信也满则记 ERROR），
     * 使过载可被监控发现。</p>
     */
    public void dispatch(String url, Map<String, Object> payload, String signature) {
        try {
            executor.submit(() -> deliverWithRetry(url, payload, signature, 1));
        } catch (RejectedExecutionException e) {
            log.error("Webhook dispatch rejected (queue full, capacity={}): url={}",
                    QUEUE_CAPACITY, url);
            if (!deadLetterQueue.offer(new WebhookTask(url, payload, signature))) {
                log.error("Dead-letter queue also full (capacity={}), dropping webhook: url={}",
                        DEAD_LETTER_CAPACITY, url);
            }
        }
    }

    /**
     * 投递并按需重试。
     *
     * <p>⚠️ 已知局限（2026-09-17 审查记录，本次未修）：当前 try 块内只有
     * {@code log.info}，**不可能抛出异常**，因此下方的重试与死信分支实际不可达 ——
     * 该方法是 dev/sandbox 档位的占位实现（注释自述 "Simulate HTTP delivery"）。
     * 接入真实 HTTP 投递时，务必同时让异常传播到此处，否则重试与死信形同虚设。
     * 生产档位由 RabbitMQ 链路承担（见类注释 C3）。</p>
     */
    private void deliverWithRetry(String url, Map<String, Object> payload, String signature, int attempt) {
        try {
            // Simulate HTTP delivery (in production, use RestTemplate/WebClient)
            log.info("Webhook delivered: url={}, attempt={}", url, attempt);
        } catch (RuntimeException e) {
            if (attempt >= MAX_RETRIES) {
                log.error("Webhook permanently failed after {} retries: url={}", MAX_RETRIES, url);
                // offer 返回 false 表示死信队列已满 —— 必须显式记录，不能静默丢
                if (!deadLetterQueue.offer(new WebhookTask(url, payload, signature))) {
                    log.error("Dead-letter queue full (capacity={}), dropping webhook: url={}",
                            DEAD_LETTER_CAPACITY, url);
                }
            } else {
                long delay = (long) Math.pow(2, attempt) * 1000;
                log.warn("Webhook retry {}/{} in {}ms: url={}", attempt, MAX_RETRIES, delay, url);
                try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                deliverWithRetry(url, payload, signature, attempt + 1);
            }
        }
    }

    public int getDeadLetterCount() { return deadLetterQueue.size(); }

    private static class WebhookTask {
        final String url;
        final Map<String, Object> payload;
        final String signature;
        WebhookTask(String url, Map<String, Object> payload, String signature) {
            this.url = url; this.payload = payload; this.signature = signature;
        }
    }
}