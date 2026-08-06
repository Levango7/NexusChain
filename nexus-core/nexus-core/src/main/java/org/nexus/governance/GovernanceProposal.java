package org.nexus.governance;

import java.time.Instant;
import java.util.List;

/**
 * 治理提案实体。
 *
 * <p>描述一次链上治理提案的类型、参数变更、投票期与状态。</p>
 *
 * @since 1.2
 */
public class GovernanceProposal {

    /** 提案 ID */
    private String proposalId;

    /** 提案类型 */
    private ProposalType type;

    /** 参数变更列表 */
    private List<ParameterChange> parameterChanges;

    /** 投票开始时间 */
    private Instant votingStart;

    /** 投票结束时间 */
    private Instant votingEnd;

    /** 执行开始时间（投票通过后可执行时间） */
    private Instant executionStart;

    /** 执行截止时间（超期则标记为 EXPIRED） */
    private Instant executionEnd;

    /** 提案状态 */
    private ProposalStatus status;

    /** 提案发起人地址 */
    private String proposer;

    public GovernanceProposal() {
    }

    public String getProposalId() {
        return proposalId;
    }

    public void setProposalId(String proposalId) {
        this.proposalId = proposalId;
    }

    public ProposalType getType() {
        return type;
    }

    public void setType(ProposalType type) {
        this.type = type;
    }

    public List<ParameterChange> getParameterChanges() {
        return parameterChanges;
    }

    public void setParameterChanges(List<ParameterChange> parameterChanges) {
        this.parameterChanges = parameterChanges;
    }

    public Instant getVotingStart() {
        return votingStart;
    }

    public void setVotingStart(Instant votingStart) {
        this.votingStart = votingStart;
    }

    public Instant getVotingEnd() {
        return votingEnd;
    }

    public void setVotingEnd(Instant votingEnd) {
        this.votingEnd = votingEnd;
    }

    public Instant getExecutionStart() {
        return executionStart;
    }

    public void setExecutionStart(Instant executionStart) {
        this.executionStart = executionStart;
    }

    public Instant getExecutionEnd() {
        return executionEnd;
    }

    public void setExecutionEnd(Instant executionEnd) {
        this.executionEnd = executionEnd;
    }

    public ProposalStatus getStatus() {
        return status;
    }

    public void setStatus(ProposalStatus status) {
        this.status = status;
    }

    public String getProposer() {
        return proposer;
    }

    public void setProposer(String proposer) {
        this.proposer = proposer;
    }
}