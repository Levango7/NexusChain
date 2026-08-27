package org.nexus.gateway.orchestration.service;

import org.nexus.gateway.ratelimit.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Idempotency guard for the orchestration layer.
 * Ensures the same request_id returns the same payment without re-executing.
 *
 * <p>Backed by the shared {@link IdempotencyStore}: {@code RedisIdempotencyStore}
 * (24h TTL) in the {@code prod} profile, {@code InMemoryIdempotencyStore} in
 * {@code dev}/{@code sandbox}. This is what previously made those beans dead code
 * for the orchestration path - they are now the backing store here.</p>
 */
@Component
public class OrchestrationIdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationIdempotencyStore.class);

    private final IdempotencyStore backing;

    public OrchestrationIdempotencyStore(IdempotencyStore backing) {
        this.backing = backing;
    }

    /**
     * Check if a request has already been processed.
     * @return existing payment ID if duplicate, null if new
     */
    public String checkDuplicate(String requestId) {
        if (requestId == null || requestId.isBlank()) return null;
        String paymentId = backing.get(requestId);
        if (paymentId != null) {
            log.debug("Idempotency hit: requestId={} -> paymentId={}", requestId, paymentId);
        }
        return paymentId;
    }

    /**
     * Record a processed request (request_id -> payment_id).
     */
    public void record(String requestId, String paymentId) {
        if (requestId == null || requestId.isBlank()) return;
        backing.put(requestId, paymentId);
    }
}
