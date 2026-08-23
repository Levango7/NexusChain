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

    /**
     * Atomically reserve an idempotency key.
     *
     * <p>P0 安全修复（幂等 TOCTOU）：原 {@code get} + {@code put} 两步非原子，
     * 并发请求可同时通过 {@code get} 检查后重复 {@code put}。此方法在单个原子操作内
     * 完成"键不存在则写入并返回 true，键已存在则返回 false"，消除竞态窗口。</p>
     *
     * <p>默认实现为非原子的 get+put 回退，仅用于向后兼容（如测试 mock）。
     * 生产实现（{@link RedisIdempotencyStore} / {@link InMemoryIdempotencyStore}）
     * 必须覆盖此方法以提供真正的原子语义。</p>
     *
     * @param idempotencyKey 幂等键，不能为 null/空
     * @param value          要写入的值（如 paymentId）
     * @return true 表示预留成功（键此前不存在），false 表示键已存在（重复请求）
     */
    default boolean tryReserve(String idempotencyKey, String value) {
        if (get(idempotencyKey) != null) {
            return false;
        }
        put(idempotencyKey, value);
        return true;
    }

    /**
     * Release a previously reserved idempotency key.
     *
     * <p>用于原子预留成功后、业务执行失败时回滚预留，允许相同 requestId 重试。
     * 若键不存在则为 no-op。</p>
     *
     * <p>默认 no-op 回退，向后兼容。生产实现应覆盖以真正删除键。</p>
     *
     * @param idempotencyKey 幂等键
     */
    default void release(String idempotencyKey) {
        // no-op default for backward compatibility
    }
}