package org.nexus.gateway.ratelimit;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory idempotency store for development (single instance).
 */
@Component
@Profile({"dev", "sandbox"})
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    @Override
    public String get(String idempotencyKey) {
        return store.get(idempotencyKey);
    }

    @Override
    public void put(String idempotencyKey, String value) {
        store.put(idempotencyKey, value);
    }
}