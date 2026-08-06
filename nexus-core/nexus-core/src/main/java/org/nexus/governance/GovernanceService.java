package org.nexus.governance;

import org.nexus.consensus.pos.StakingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 链上治理服务门面。
 *
 * <p>统一封装提案创建、投票、计票、执行与查询流程：
 * <ol>
 *   <li>{@link #submitProposal} 创建提案进入 VOTING 状态</li>
 *   <li>{@link #vote} 在投票期内投票，权重 = 质押量</li>
 *   <li>{@link #tallyVotes} 投票期结束后计票，通过则 PASSED</li>
 *   <li>{@link #executeIfPassed} 调度延迟执行，到期后生效</li>
 * </ol>
 *
 * @since 1.2
 */
@Component
public class GovernanceService implements OnChainGovernance {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceService.class);

    /** 默认投票期：3 天 */
    private static final Duration DEFAULT_VOTING_PERIOD = Duration.ofDays(3);

    /** 默认法定人数门槛 */
    private static final BigDecimal DEFAULT_QUORUM = new BigDecimal("100");

    @Autowired
    private GovernanceVotingService votingService;

    @Autowired
    private GovernanceExecutor executor;

    @Autowired
    private StakingService stakingService;

    private final Duration votingPeriod;
    private final BigDecimal quorum;

    /** proposalId -> proposal */
    private final Map<String, GovernanceProposal> proposals = new ConcurrentHashMap<>();

    public GovernanceService() {
        this(DEFAULT_VOTING_PERIOD, DEFAULT_QUORUM);
    }

    public GovernanceService(Duration votingPeriod, BigDecimal quorum) {
        this.votingPeriod = votingPeriod;
        this.quorum = quorum;
    }

    @Override
    public String submitProposal(GovernanceProposal proposal) {
        if (proposal == null) {
            throw new IllegalArgumentException("Proposal cannot be null");
        }
        String id = proposal.getProposalId() != null ? proposal.getProposalId() : UUID.randomUUID().toString();
        proposal.setProposalId(id);
        Instant now = Instant.now();
        proposal.setVotingStart(now);
        proposal.setVotingEnd(now.plus(votingPeriod));
        proposal.setStatus(ProposalStatus.VOTING);
        proposals.put(id, proposal);
        logger.info("Proposal submitted: id={} type={} proposer={}", id, proposal.getType(), proposal.getProposer());
        return id;
    }

    /**
     * 对指定提案投票。
     *
     * @param proposalId 提案 ID
     * @param voter      投票人地址
     * @param option     投票选项
     */
    public void vote(String proposalId, String voter, VoteOption option) {
        GovernanceProposal proposal = proposals.get(proposalId);
        if (proposal == null) {
            logger.warn("Vote rejected: proposal not found {}", proposalId);
            return;
        }
        if (Instant.now().isAfter(proposal.getVotingEnd())) {
            logger.warn("Vote rejected: voting period ended for {}", proposalId);
            return;
        }
        votingService.vote(proposalId, voter, option, Instant.now());
    }

    @Override
    public void vote(String proposalId, VoteOption vote) {
        // OnChainGovernance 接口未携带投票人参数，门面层请使用 vote(proposalId, voter, option)
        throw new UnsupportedOperationException(
                "OnChainGovernance.vote lacks voter identity; use GovernanceService.vote(proposalId, voter, option)");
    }

    @Override
    public boolean tallyVotes(String proposalId) {
        GovernanceProposal proposal = proposals.get(proposalId);
        if (proposal == null) {
            return false;
        }
        if (Instant.now().isBefore(proposal.getVotingEnd())) {
            logger.warn("Tally rejected: voting not ended for {}", proposalId);
            return false;
        }
        if (proposal.getStatus() != ProposalStatus.VOTING) {
            logger.warn("Tally rejected: proposal {} already tallied as {}", proposalId, proposal.getStatus());
            return false;
        }
        GovernanceVotingService.VoteTally tally = votingService.tally(proposalId);
        boolean passed = tally.passes(quorum);
        proposal.setStatus(passed ? ProposalStatus.PASSED : ProposalStatus.REJECTED);
        logger.info("Tally for {}: yes={} no={} abstain={} quorum={} -> {}",
                proposalId, tally.getYes(), tally.getNo(), tally.getAbstain(), quorum, proposal.getStatus());
        return passed;
    }

    @Override
    public boolean executeIfPassed(String proposalId) {
        GovernanceProposal proposal = proposals.get(proposalId);
        if (proposal == null) {
            return false;
        }
        if (proposal.getStatus() != ProposalStatus.PASSED) {
            logger.warn("Execute rejected: proposal {} status {} (require PASSED)", proposalId, proposal.getStatus());
            return false;
        }
        return executor.schedule(proposal, Instant.now());
    }

    /**
     * 查询指定提案。
     *
     * @param proposalId 提案 ID
     * @return 提案实体；不存在返回 null
     */
    public GovernanceProposal getProposal(String proposalId) {
        return proposals.get(proposalId);
    }

    /**
     * 列出所有提案。
     *
     * @return 提案列表
     */
    public List<GovernanceProposal> listProposals() {
        return new ArrayList<>(proposals.values());
    }

    /**
     * 标记过期提案（投票期结束且未通过，或执行期结束未执行）。
     *
     * @param proposalId 提案 ID
     * @return 标记为过期返回 true
     */
    public boolean markExpiredIfApplicable(String proposalId) {
        GovernanceProposal proposal = proposals.get(proposalId);
        if (proposal == null) {
            return false;
        }
        Instant now = Instant.now();
        if (proposal.getStatus() == ProposalStatus.VOTING && now.isAfter(proposal.getVotingEnd())) {
            proposal.setStatus(ProposalStatus.EXPIRED);
            logger.info("Proposal {} expired (voting ended)", proposalId);
            return true;
        }
        if (proposal.getStatus() == ProposalStatus.PASSED
                && proposal.getExecutionEnd() != null
                && now.isAfter(proposal.getExecutionEnd())) {
            proposal.setStatus(ProposalStatus.EXPIRED);
            logger.info("Proposal {} expired (execution window ended)", proposalId);
            return true;
        }
        return false;
    }

    public Duration getVotingPeriod() {
        return votingPeriod;
    }

    public BigDecimal getQuorum() {
        return quorum;
    }
}