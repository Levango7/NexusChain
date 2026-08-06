package org.nexus.governance.emergency;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 紧急回滚审计日志实体。
 *
 * <p>记录一次紧急回滚操作的完整审计信息，包括触发人、守护人批准集合、
 * 目标快照版本、回滚原因与执行时间。审计日志一旦生成不可变，
 * 供事后追溯与合规审查使用。</p>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code rollbackId}：回滚操作唯一 ID（UUID）</li>
 *   <li>{@code triggeredBy}：发起紧急回滚的地址（通常是首位守护人）</li>
 *   <li>{@code guardianApprovals}：参与批准的守护人地址集合（m-of-n 中的 m）</li>
 *   <li>{@code targetSnapshotVersion}：回滚目标快照版本号</li>
 *   <li>{@code reason}：回滚原因（自由文本，应包含安全事件描述）</li>
 *   <li>{@code executedAt}：回滚执行时间</li>
 *   <li>{@code success}：回滚是否成功落盘</li>
 * </ul>
 *
 * @since 1.5
 */
public final class EmergencyRollbackRecord {

    /** 回滚操作唯一 ID */
    private final String rollbackId;

    /** 发起人地址 */
    private final String triggeredBy;

    /** 参与批准的守护人地址集合（只读） */
    private final List<String> guardianApprovals;

    /** 目标快照版本号 */
    private final int targetSnapshotVersion;

    /** 回滚原因 */
    private final String reason;

    /** 执行时间 */
    private final Instant executedAt;

    /** 是否成功 */
    private final boolean success;

    public EmergencyRollbackRecord(String rollbackId, String triggeredBy,
                                   List<String> guardianApprovals, int targetSnapshotVersion,
                                   String reason, Instant executedAt, boolean success) {
        this.rollbackId = rollbackId;
        this.triggeredBy = triggeredBy;
        this.guardianApprovals = guardianApprovals == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(guardianApprovals);
        this.targetSnapshotVersion = targetSnapshotVersion;
        this.reason = reason;
        this.executedAt = executedAt;
        this.success = success;
    }

    public String getRollbackId() {
        return rollbackId;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    /**
     * 返回参与批准的守护人地址集合（只读）。
     *
     * @return 守护人批准集合
     */
    public List<String> getGuardianApprovals() {
        return guardianApprovals;
    }

    public int getTargetSnapshotVersion() {
        return targetSnapshotVersion;
    }

    public String getReason() {
        return reason;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public boolean isSuccess() {
        return success;
    }

    @Override
    public String toString() {
        return "EmergencyRollbackRecord{rollbackId='" + rollbackId + '\''
                + ", triggeredBy='" + triggeredBy + '\''
                + ", approvals=" + guardianApprovals.size()
                + ", targetVersion=" + targetSnapshotVersion
                + ", reason='" + reason + '\''
                + ", executedAt=" + executedAt
                + ", success=" + success + '}';
    }
}