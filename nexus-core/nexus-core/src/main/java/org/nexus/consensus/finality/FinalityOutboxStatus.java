package org.nexus.consensus.finality;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最终性 outbox 的观测快照（PLAN-018；PLAN-073 增加可读性与丢票）。
 *
 * <p>回答运维五个问题：现在有多少张票等着落库？它们是本节点签的（local）
 * 还是从对端收到的（received）？数据库里的持久化 outbox 到底积压了多少行？
 * 这个数字现在读得到吗？最近一次补写失败的原因是什么？</p>
 *
 * <p>与 {@code AccountStoreHealth} 同样的口径：只反映本节点视角，
 * 不代表其它节点的状态。</p>
 */
public final class FinalityOutboxStatus {

    private final int localPending;
    private final int receivedPending;
    private final long databasePending;
    private final long lastFlushAt;
    private final long lastEnqueueAt;
    private final long totalEnqueued;
    private final long totalFlushed;
    private final long totalFailed;
    private final long totalDropped;
    private final boolean databaseReadable;
    private final String lastError;

    public FinalityOutboxStatus(int localPending, int receivedPending, int databasePending,
                                long lastFlushAt, long lastEnqueueAt,
                                long totalEnqueued, long totalFlushed, long totalFailed,
                                String lastError) {
        this(localPending, receivedPending, databasePending, lastFlushAt, lastEnqueueAt,
                totalEnqueued, totalFlushed, totalFailed, 0L, true, lastError);
    }

    public FinalityOutboxStatus(int localPending, int receivedPending, long databasePending,
                                long lastFlushAt, long lastEnqueueAt,
                                long totalEnqueued, long totalFlushed, long totalFailed,
                                long totalDropped, boolean databaseReadable, String lastError) {
        this.localPending = localPending;
        this.receivedPending = receivedPending;
        this.databasePending = databasePending;
        this.lastFlushAt = lastFlushAt;
        this.lastEnqueueAt = lastEnqueueAt;
        this.totalEnqueued = totalEnqueued;
        this.totalFlushed = totalFlushed;
        this.totalFailed = totalFailed;
        this.totalDropped = totalDropped;
        this.databaseReadable = databaseReadable;
        this.lastError = lastError == null ? "" : lastError;
    }

    public int getLocalPending() {
        return localPending;
    }

    public int getReceivedPending() {
        return receivedPending;
    }

    public long getDatabasePending() {
        return databasePending;
    }

    public long getTotalDropped() {
        return totalDropped;
    }

    /**
     * 持久化 outbox 的行数是否**真的读到了**。
     *
     * <p>PLAN-073 之前，{@code databasePending=0} 同时表示「没有积压」与
     * 「数据库读不到」——两者在运维上是完全相反的结论。现在用本字段区分：
     * 读不到时为 {@code false}，{@code databasePending} 只作为 0 占位，
     * 不应被当成当前事实。</p>
     */
    public boolean isDatabaseReadable() {
        return databaseReadable;
    }

    public boolean isDegraded() {
        // 「读不到」本身就是降级：不能因为读不到而显示 UP。
        return !databaseReadable || localPending > 0 || receivedPending > 0 || databasePending > 0;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", isDegraded() ? "DEGRADED" : "UP");
        data.put("localPending", localPending);
        data.put("receivedPending", receivedPending);
        data.put("databasePending", databasePending);
        data.put("databaseReadable", databaseReadable);
        data.put("lastFlushAt", lastFlushAt);
        data.put("lastEnqueueAt", lastEnqueueAt);
        data.put("totalEnqueued", totalEnqueued);
        data.put("totalFlushed", totalFlushed);
        data.put("totalFailed", totalFailed);
        data.put("totalDropped", totalDropped);
        data.put("lastError", lastError);
        return data;
    }
}
