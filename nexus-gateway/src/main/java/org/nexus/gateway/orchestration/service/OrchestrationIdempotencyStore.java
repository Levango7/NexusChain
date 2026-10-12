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

    /**
     * 占位标记：已抢到 requestId、但支付尚未落库。
     * 与 {@code OrderServiceImpl.IN_FLIGHT}（2026-09-17 同类修复）同值同语义。
     */
    static final String IN_FLIGHT = "__IN_FLIGHT__";

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
        // 占位不是结果：把它当 paymentId 返回会让调用方去 findById("__IN_FLIGHT__")，
        // 进而误判为"映射丢失可重新建单" —— 正是要防的二次动账。
        if (IN_FLIGHT.equals(paymentId)) return null;
        if (paymentId != null) {
            log.debug("Idempotency hit: requestId={} -> paymentId={}", requestId, paymentId);
        }
        return paymentId;
    }

    /**
     * 原子占位（P1，2026-10-12）：抢到才允许走渠道动账。
     *
     * <p>原实现是 {@code checkDuplicate}（读）在入口、{@code record}（写）在
     * {@code persist()} 内 —— 而 persist 发生在渠道调用<b>之后</b>，并发两个相同
     * requestId 会双双通过入口读检并各调一次渠道；走 ChainConnector 即链上真实
     * 转账两次。占位提前到渠道之前，胜负由底层 {@code putIfAbsent}（Redis SET NX
     * / CHM CAS）一次性裁决。</p>
     *
     * @return true = 本请求是唯一胜者，可以继续动账；false = 已有占位或已完成映射
     */
    public boolean tryClaim(String requestId) {
        if (requestId == null || requestId.isBlank()) return true;
        return backing.putIfAbsent(requestId, IN_FLIGHT);
    }

    /**
     * 释放占位 —— <b>仅当仍是占位时</b>删除。
     *
     * <p>条件删除是必需的：若异常发生在 {@code record()} 已把映射换成真实
     * paymentId 之后，无条件 remove 会抹掉已完成映射，后续同 requestId 请求就会
     * 重新建单 —— 比原缺陷更糟。占位期只有持有者会写这个 key，因此 get+remove
     * 对同一 key 不存在竞争写入方。</p>
     */
    public void releaseClaim(String requestId) {
        if (requestId == null || requestId.isBlank()) return;
        if (IN_FLIGHT.equals(backing.get(requestId))) {
            backing.remove(requestId);
        }
    }

    /**
     * Record a processed request (request_id -> payment_id).
     */
    public void record(String requestId, String paymentId) {
        if (requestId == null || requestId.isBlank()) return;
        backing.put(requestId, paymentId);
    }
}
