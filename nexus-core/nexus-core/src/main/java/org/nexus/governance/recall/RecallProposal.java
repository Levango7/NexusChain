package org.nexus.governance.recall;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 守护人罢免提案实体。
 *
 * <p>记录一次守护人罢免提案的完整信息，包括目标守护人、罢免证据、
 * 关联的治理提案 ID、提案状态与处置结果。罢免提案通过正常治理投票流程
 * （投票期 + quorum + timelock）进行，通过后由 {@link GuardianRecallService}
 * 从 {@code GuardianService} 移除该守护人。</p>
 *
 * <h3>状态流转</h3>
 * <pre>
 * SUBMITTED → VOTING → (tally) → PASSED → EXECUTED（移除守护人）
 *                              ↘ REJECTED
 * </pre>
 *
 * @since 1.5
 */
public final class RecallProposal {

    /** 罢免提案状态 */
    public enum Status {
        /** 已提交，关联治理提案投票中 */
        SUBMITTED,
        /** 治理提案通过，待执行罢免 */
        PASSED,
        /** 治理提案被否决 */
        REJECTED,
        /** 已执行，守护人已移除 */
        EXECUTED,
        /** 已过期 */
        EXPIRED
    }

    /** 罢免提案 ID */
    private final String recallProposalId;

    /** 目标守护人地址 */
    private final String targetGuardian;

    /** 罢免证据列表 */
    private final List<RecallEvidence> evidences;

    /** 关联的治理提案 ID（用于走正常投票流程） */
    private final String governanceProposalId;

    /** 提案发起人 */
    private final String proposer;

    /** 提交时间 */
    private final Instant submittedAt;

    /** 当前状态 */
    private volatile Status status;

    /** 处置结果描述（执行后填写） */
    private volatile String resolution;

    public RecallProposal(String recallProposalId, String targetGuardian,
                          List<RecallEvidence> evidences, String governanceProposalId,
                          String proposer, Instant submittedAt) {
        this.recallProposalId = recallProposalId;
        this.targetGuardian = targetGuardian;
        this.evidences = evidences == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(evidences);
        this.governanceProposalId = governanceProposalId;
        this.proposer = proposer;
        this.submittedAt = submittedAt;
        this.status = Status.SUBMITTED;
    }

    public String getRecallProposalId() {
        return recallProposalId;
    }

    public String getTargetGuardian() {
        return targetGuardian;
    }

    /**
     * 返回罢免证据列表（只读）。
     *
     * @return 证据列表
     */
    public List<RecallEvidence> getEvidences() {
        return evidences;
    }

    public String getGovernanceProposalId() {
        return governanceProposalId;
    }

    public String getProposer() {
        return proposer;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    @Override
    public String toString() {
        return "RecallProposal{recallProposalId='" + recallProposalId + '\''
                + ", targetGuardian='" + targetGuardian + '\''
                + ", governanceProposalId='" + governanceProposalId + '\''
                + ", status=" + status
                + ", evidences=" + evidences.size() + '}';
    }
}