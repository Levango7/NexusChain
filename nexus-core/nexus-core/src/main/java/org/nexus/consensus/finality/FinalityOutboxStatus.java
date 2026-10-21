package org.nexus.consensus.finality;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最终性 outbox 的观测快照（PLAN-018）。
 *
 * <p>回答运维三个问题：现在有多少张票等着落库？它们是本节点签的（local）
 * 还是从对端收到的（received）？最近一次补写失败的原因是什么？</p>
 *
 * <p>与 {@code AccountStoreHealth} 同样的口径：只反映本节点视角，
 * 不代表其它节点的状态。</p>
 */
public final class FinalityOutboxStatus {

    private final int localPending;
    private final int receivedPending;
    private final int databasePending;
    private final long lastFlushAt;
    private final long lastEnqueueAt;
    private final long totalEnqueued;
    private final long totalFlushed;
    private final long totalFailed;
    private final String lastError;

    public FinalityOutboxStatus(int localPending, int receivedPending, int databasePending,
                                long lastFlushAt, long lastEnqueueAt,
                                long totalEnqueued, long totalFlushed, long totalFailed,
                                String lastError) {
        this.localPending = localPending;
        this.receivedPending = receivedPending;
        this.databasePending = databasePending;
        this.lastFlushAt = lastFlushAt;
        this.lastEnqueueAt = lastEnqueueAt;
        this.totalEnqueued = totalEnqueued;
        this.totalFlushed = totalFlushed;
        this.totalFailed = totalFailed;
        this.lastError = lastError == null ? "" : lastError;
    }

    public int getLocalPending() {
        return localPending;
    }

    public int getReceivedPending() {
        return receivedPending;
    }

    public int getDatabasePending() {
        return databasePending;
    }

    public boolean isDegraded() {
        return localPending > 0 || receivedPending > 0 || databasePending > 0;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", isDegraded() ? "DEGRADED" : "UP");
        data.put("localPending", localPending);
        data.put("receivedPending", receivedPending);
        data.put("databasePending", databasePending);
        data.put("lastFlushAt", lastFlushAt);
        data.put("lastEnqueueAt", lastEnqueueAt);
        data.put("totalEnqueued", totalEnqueued);
        data.put("totalFlushed", totalFlushed);
        data.put("totalFailed", totalFailed);
        data.put("lastError", lastError);
        return data;
    }
}
