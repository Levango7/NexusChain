package org.nexus.oracle.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link GovernanceService} 默认实现。
 *
 * <p>提案生命周期：
 * <ol>
 *   <li>{@link #createProposal}：校验参数 → 分配 proposalId → 置 PENDING / ACTIVE
 *       （投票期已到则直接 ACTIVE）</li>
 *   <li>{@link #vote}：校验投票期与防重投 → 累计权重 → 计票</li>
 *   <li>投票期结束后按 YES / (YES+NO) 过半判定 PASSED 或 REJECTED（状态惰性推进）</li>
 *   <li>{@link #executeProposal}：校验 PASSED + 已过执行延迟 → 标记 EXECUTED</li>
 * </ol>
 *
 * <p>当前为进程内存储，后续接入链上治理合约时替换持久化与执行分发层。
 */
@Slf4j
@Service
public class DefaultGovernanceService implements GovernanceService {

    /** 提案存储（proposalId → proposal） */
    private final Map<String, Proposal> proposals = new ConcurrentHashMap<>();

    /** 每提案投票权重累计（proposalId → option → 权重和） */
    private final Map<String, Map<Vote.Option, BigInteger>> voteTally = new ConcurrentHashMap<>();

    /** 每提案已投票者集合（防重投） */
    private final Map<String, Set<String>> voters = new ConcurrentHashMap<>();

    /** 通过阈值：YES / (YES+NO) 需 > 0.5 */
    private static final double PASS_THRESHOLD = 0.5d;

    @Override
    public Proposal createProposal(Proposal proposal) {
        if (proposal == null) {
            throw new IllegalArgumentException("Proposal must not be null");
        }
        if (proposal.getTitle() == null || proposal.getTitle().isBlank()) {
            throw new IllegalArgumentException("Proposal title is required");
        }
        if (proposal.getType() == null) {
            throw new IllegalArgumentException("Proposal type is required");
        }
        if (proposal.getProposer() == null || proposal.getProposer().isBlank()) {
            throw new IllegalArgumentException("Proposer is required");
        }
        if (proposal.getVotingPeriod() == null) {
            proposal.setVotingPeriod(Duration.ofDays(7));
        }
        if (proposal.getExecutionDelay() == null) {
            proposal.setExecutionDelay(Duration.ofDays(1));
        }

        proposal.setProposalId("PROP-" + UUID.randomUUID().toString().replace("-", ""));
        Instant now = Instant.now();
        if (proposal.getVotingStart() == null) {
            proposal.setVotingStart(now);
        }
        proposal.setState(proposal.getVotingStart().isAfter(now)
                ? ProposalState.PENDING : ProposalState.ACTIVE);

        proposals.put(proposal.getProposalId(), proposal);
        voteTally.put(proposal.getProposalId(), new ConcurrentHashMap<>());
        voters.put(proposal.getProposalId(), ConcurrentHashMap.newKeySet());
        log.info("Proposal created: id={}, title={}, type={}, state={}",
                proposal.getProposalId(), proposal.getTitle(), proposal.getType(), proposal.getState());
        return proposal;
    }

    @Override
    public boolean vote(String proposalId, Vote vote) {
        if (proposalId == null || vote == null || vote.getVoter() == null) {
            return false;
        }
        Proposal proposal = proposals.get(proposalId);
        if (proposal == null) {
            return false;
        }
        advanceState(proposal);
        if (proposal.getState() != ProposalState.ACTIVE) {
            log.debug("Vote rejected: proposal not ACTIVE, state={}", proposal.getState());
            return false;
        }
        // 防重投
        Set<String> voterSet = voters.get(proposalId);
        if (!voterSet.add(vote.getVoter())) {
            log.debug("Vote rejected: voter already voted: {}", vote.getVoter());
            return false;
        }
        BigInteger weight = vote.getWeight() != null ? vote.getWeight() : BigInteger.ONE;
        voteTally.get(proposalId).merge(
                vote.getOption() != null ? vote.getOption() : Vote.Option.ABSTAIN,
                weight, BigInteger::add);
        log.info("Vote counted: proposalId={}, voter={}, option={}, weight={}",
                proposalId, vote.getVoter(), vote.getOption(), weight);
        return true;
    }

    @Override
    public boolean executeProposal(String proposalId) {
        if (proposalId == null) {
            return false;
        }
        Proposal proposal = proposals.get(proposalId);
        if (proposal == null) {
            return false;
        }
        advanceState(proposal);
        if (proposal.getState() != ProposalState.PASSED) {
            log.debug("Execute rejected: proposal not PASSED, state={}", proposal.getState());
            return false;
        }
        // 校验执行延迟
        Instant votingEnd = proposal.getVotingStart().plus(proposal.getVotingPeriod());
        Instant executableAt = votingEnd.plus(proposal.getExecutionDelay());
        if (Instant.now().isBefore(executableAt)) {
            log.debug("Execute rejected: execution delay not elapsed, executableAt={}", executableAt);
            return false;
        }
        // TODO: 按 type 分发真实执行（PARAMETER_CHANGE 改参数 / SOFTWARE_UPGRADE 触发升级 / TREASURY_SPEND 调国库）
        proposal.setState(ProposalState.EXECUTED);
        log.info("Proposal executed: id={}, type={}", proposalId, proposal.getType());
        return true;
    }

    @Override
    public ProposalState getProposalState(String proposalId) {
        Proposal proposal = proposals.get(proposalId);
        if (proposal == null) {
            return null;
        }
        advanceState(proposal);
        return proposal.getState();
    }

    @Override
    public Proposal getProposal(String proposalId) {
        Proposal proposal = proposals.get(proposalId);
        if (proposal != null) {
            advanceState(proposal);
        }
        return proposal;
    }

    /**
     * 查询提案当前计票快照（测试 / 审计用）。
     *
     * @param proposalId 提案 ID
     * @return option → 权重和 的映射，提案不存在时返回空
     */
    public Map<Vote.Option, BigInteger> getTally(String proposalId) {
        Map<Vote.Option, BigInteger> tally = voteTally.get(proposalId);
        return tally == null ? Map.of() : new HashMap<>(tally);
    }

    /**
     * 惰性状态推进：投票期结束后按计票结果置 PASSED / REJECTED。
     */
    private void advanceState(Proposal proposal) {
        if (proposal.getState() != ProposalState.ACTIVE) {
            return;
        }
        Instant votingEnd = proposal.getVotingStart().plus(proposal.getVotingPeriod());
        if (Instant.now().isBefore(votingEnd)) {
            return;
        }
        Map<Vote.Option, BigInteger> tally = voteTally.getOrDefault(
                proposal.getProposalId(), Map.of());
        BigInteger yes = tally.getOrDefault(Vote.Option.YES, BigInteger.ZERO);
        BigInteger no = tally.getOrDefault(Vote.Option.NO, BigInteger.ZERO);
        BigInteger decisive = yes.add(no);
        boolean passed = decisive.signum() > 0
                && yes.doubleValue() / decisive.doubleValue() > PASS_THRESHOLD;
        proposal.setState(passed ? ProposalState.PASSED : ProposalState.REJECTED);
        log.info("Proposal voting closed: id={}, state={}, yes={}, no={}",
                proposal.getProposalId(), proposal.getState(), yes, no);
    }
}
