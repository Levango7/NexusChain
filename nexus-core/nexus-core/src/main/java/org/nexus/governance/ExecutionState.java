package org.nexus.governance;

import java.time.Instant;

/**
 * 治理提案执行状态实体。
 *
 * <p>记录提案被调度执行后的时间锁事务 ID 与调度时间。
 * 由 {@link GovernanceExecutor} 在 {@code schedule()} 时创建，
 * 在 {@code execute()} 成功或 {@code cancel()} 后清除。</p>
 *
 * @since 1.3
 */
public class ExecutionState {

    /** 时间锁事务 ID */
    private final String txId;

    /** 调度时间 */
    private final Instant scheduledAt;

    public ExecutionState(String txId, Instant scheduledAt) {
        this.txId = txId;
        this.scheduledAt = scheduledAt;
    }

    public String getTxId() {
        return txId;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }
}