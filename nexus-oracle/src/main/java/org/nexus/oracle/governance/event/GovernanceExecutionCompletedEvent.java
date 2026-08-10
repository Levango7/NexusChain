package org.nexus.oracle.governance.event;

import org.nexus.oracle.governance.Proposal;
import org.nexus.oracle.governance.ProposalState;

import java.time.Instant;

/**
 * 治理执行完成事件。
 *
 * <p>当治理执行器（{@link org.nexus.oracle.governance.execution.SoftwareUpgradeExecutor}
 * 或 {@link org.nexus.oracle.governance.execution.TreasurySpendExecutor}）执行完毕后发布。
 * 监听方可据此事件驱动通知、告警、审计归档等下游流程。
 *
 * <p>事件载荷包含：
 * <ul>
 *   <li>{@code proposalId} — 提案 ID</li>
 *   <li>{@code proposalType} — 提案类型字符串</li>
 *   <li>{@code success} — 执行是否成功</li>
 *   <li>{@code finalState} — 提案最终状态（{@link ProposalState#EXECUTED} 或 {@link ProposalState#EXECUTION_FAILED}）</li>
 *   <li>{@code errorMessage} — 失败原因（成功时为 {@code null}）</li>
 *   <li>{@code timestamp} — 事件发布时间</li>
 * </ul>
 *
 * @since 2.0.0
 */
public class GovernanceExecutionCompletedEvent {

    /** 提案 ID */
    private final String proposalId;

    /** 提案类型字符串 */
    private final String proposalType;

    /** 执行是否成功 */
    private final boolean success;

    /** 提案最终状态 */
    private final ProposalState finalState;

    /** 失败原因 */
    private final String errorMessage;

    /** 事件发布时间 */
    private final Instant timestamp;

    /** 关联提案 */
    private final Proposal proposal;

    /**
     * 构造治理执行完成事件。
     *
     * @param proposalId    提案 ID
     * @param proposalType  提案类型字符串
     * @param success       执行是否成功
     * @param finalState    提案最终状态
     * @param errorMessage  失败原因（可为 {@code null}）
     * @param proposal      关联提案（可为 {@code null}）
     */
    public GovernanceExecutionCompletedEvent(String proposalId, String proposalType, boolean success,
                                             ProposalState finalState, String errorMessage, Proposal proposal) {
        this.proposalId = proposalId;
        this.proposalType = proposalType;
        this.success = success;
        this.finalState = finalState;
        this.errorMessage = errorMessage;
        this.proposal = proposal;
        this.timestamp = Instant.now();
    }

    /** @return 提案 ID */
    public String getProposalId() {
        return proposalId;
    }

    /** @return 提案类型字符串 */
    public String getProposalType() {
        return proposalType;
    }

    /** @return 执行是否成功 */
    public boolean isSuccess() {
        return success;
    }

    /** @return 提案最终状态 */
    public ProposalState getFinalState() {
        return finalState;
    }

    /** @return 失败原因 */
    public String getErrorMessage() {
        return errorMessage;
    }

    /** @return 事件发布时间 */
    public Instant getTimestamp() {
        return timestamp;
    }

    /** @return 关联提案 */
    public Proposal getProposal() {
        return proposal;
    }

    @Override
    public String toString() {
        return "GovernanceExecutionCompletedEvent{proposalId='" + proposalId + "', type='" + proposalType
                + "', success=" + success + ", finalState=" + finalState + ", timestamp=" + timestamp + '}';
    }
}