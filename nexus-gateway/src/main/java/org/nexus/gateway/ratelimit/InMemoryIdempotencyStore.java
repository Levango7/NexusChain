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

    /**
     * 原子预留：ConcurrentHashMap.putIfAbsent。
     *
     * <p>P0 安全修复（幂等 TOCTOU）：用 putIfAbsent 替代非原子的 get+put，
     * 保证并发下只有一个线程能预留成功。</p>
     */
    @Override
    public boolean tryReserve(String idempotencyKey, String value) {
        return store.putIfAbsent(idempotencyKey, value) == null;
    }

    /**
     * 释放预留：删除键。用于业务执行失败时回滚，允许重试。
     */
    @Override
    public void release(String idempotencyKey) {
        store.remove(idempotencyKey);
    }
}