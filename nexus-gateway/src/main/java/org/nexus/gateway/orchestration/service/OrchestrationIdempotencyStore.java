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

    /**
     * 原子预留幂等键。
     *
     * <p>P0 安全修复（幂等 TOCTOU）：委托 backing 的原子 {@code tryReserve}，
     * 在单个原子操作内完成"键不存在则写入、键已存在则拒绝"，消除 check+record
     * 之间的竞态窗口。预留成功后业务若失败，须调用 {@link #release} 回滚。</p>
     *
     * @param requestId 幂等请求 ID
     * @param paymentId 待写入的支付 ID
     * @return true 表示预留成功，false 表示键已存在（重复请求）
     */
    public boolean tryReserve(String requestId, String paymentId) {
        if (requestId == null || requestId.isBlank()) return false;
        return backing.tryReserve(requestId, paymentId);
    }

    /**
     * 释放幂等预留。用于预留成功后业务执行失败时回滚，允许相同 requestId 重试。
     *
     * @param requestId 幂等请求 ID
     */
    public void release(String requestId) {
        if (requestId == null || requestId.isBlank()) return;
        backing.release(requestId);
    }
}
