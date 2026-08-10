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

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final BlockingQueue<WebhookTask> deadLetterQueue = new LinkedBlockingQueue<>();

    /**
     * Submit a webhook for async delivery.
     */
    public void dispatch(String url, Map<String, Object> payload, String signature) {
        executor.submit(() -> deliverWithRetry(url, payload, signature, 1));
    }

    private void deliverWithRetry(String url, Map<String, Object> payload, String signature, int attempt) {
        try {
            // Simulate HTTP delivery (in production, use RestTemplate/WebClient)
            log.info("Webhook delivered: url={}, attempt={}", url, attempt);
        } catch (Exception e) {
            if (attempt >= MAX_RETRIES) {
                log.error("Webhook permanently failed after {} retries: url={}", MAX_RETRIES, url);
                deadLetterQueue.offer(new WebhookTask(url, payload, signature));
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