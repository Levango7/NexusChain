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
     * 原子占位（P1，2026-09-17 修复）。
     *
     * <p>背景：{@link #get} + {@link #put} 构成 check-then-act，并发下两个使用
     * 相同 idempotencyKey 的请求会同时通过 {@code get} 检查（都读到 null），
     * 随后各自创建资源 —— 幂等失效，产生重复订单/重复支付。</p>
     *
     * <p>本方法提供「比较并占位」的原子语义：key 不存在时写入 {@code value}
     * 并返回 {@code true}；已存在（无论值为何）返回 {@code false} 且不覆盖。</p>
     *
     * @return true 表示本次调用成功占位，调用方获得创建权
     */
    boolean putIfAbsent(String idempotencyKey, String value);

    /**
     * 释放占位（P1，2026-09-17 修复）。
     *
     * <p>创建失败时必须调用，否则该 key 会永久停留在占位状态，
     * 使用同一 key 的后续请求全部被拒且无法自愈。</p>
     */
    void remove(String idempotencyKey);
}