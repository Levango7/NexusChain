package org.nexus.gateway.ratelimit;

/**
 * Idempotency store interface. Implementations: InMemory (dev), Redis (prod).
 *
 * <p>The stored value is a string (order id for the order flow, payment id for the
 * orchestration flow) so a single backing store can serve both call paths.</p>
 */
public interface IdempotencyStore {

    /**
     * Try to acquire an idempotency key. Returns the previously stored value if duplicate.
     */
    String get(String idempotencyKey);

    /**
     * Store the mapping from idempotency key to the stored value.
     */
    void put(String idempotencyKey, String value);
}