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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 链上治理服务门面。
 *
 * <p>统一封装提案创建、投票、计票、执行与查询流程：
 * <ol>
 *   <li>{@link #submitProposal} 创建提案进入 VOTING 状态（含参数冲突检测）</li>
 *   <li>{@link #vote} 在投票期内投票，权重 = 质押量</li>
 *   <li>{@link #tallyVotes} 投票期结束后计票，通过则 PASSED</li>
 *   <li>{@link #executeIfPassed} 调度延迟执行，到期后生效</li>
 * </ol>
 *
 * <h3>参数冲突检测</h3>
 * <p>提交提案时扫描所有 {@code PASSED}/{@code QUEUED}/{@code READY} 状态提案，
 * 若存在对同一参数的待生效变更，拒绝提交并抛出 {@link ParameterConflictException}，
 * 避免同一参数被多个未执行提案并发修改。</p>
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

    /** 待执行状态集合（用于冲突检测扫描） */
    private static final Set<ProposalStatus> PENDING_EXECUTION_STATUSES =
            EnumSet.of(ProposalStatus.PASSED, ProposalStatus.QUEUED, ProposalStatus.READY);

    @Autowired
    private GovernanceVotingService votingService;

    @Autowired
    private GovernanceExecutor executor;

    @Autowired
    private StakingService stakingService;

    /** 提案仓储（默认内存实现，Spring 可注入关系库实现） */
    @Autowired
    private GovernanceProposalRepository proposalRepository = new InMemoryProposalRepository();

    /** 可治理参数注册表（用于生成回滚提案与读取快照） */
    @Autowired
    private GovernableParameterRegistry parameterRegistry;

    private final Duration votingPeriod;
    private final BigDecimal quorum;

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
        detectParameterConflicts(proposal);
        String id = proposal.getProposalId() != null ? proposal.getProposalId() : UUID.randomUUID().toString();
        proposal.setProposalId(id);
        Instant now = Instant.now();
        proposal.setVotingStart(now);
        proposal.setVotingEnd(now.plus(votingPeriod));
        proposal.setStatus(ProposalStatus.VOTING);
        proposalRepository.save(proposal);
        logger.info("Proposal submitted: id={} type={} proposer={}", id, proposal.getType(), proposal.getProposer());
        return id;
    }

    /**
     * 参数冲突检测：扫描所有待执行提案，若存在对同一参数的待生效变更则抛出异常。
     *
     * @param proposal 待提交提案
     * @throws ParameterConflictException 存在参数冲突
     */
    private void detectParameterConflicts(GovernanceProposal proposal) {
        if (proposal.getParameterChanges() == null || proposal.getParameterChanges().isEmpty()) {
            return;
        }
        List<String> newParams = new ArrayList<>();
        for (ParameterChange pc : proposal.getParameterChanges()) {
            if (pc != null && pc.getParameterName() != null) {
                newParams.add(pc.getParameterName());
            }
        }
        if (newParams.isEmpty()) {
            return;
        }
        for (ProposalStatus status : PENDING_EXECUTION_STATUSES) {
            for (GovernanceProposal pending : proposalRepository.findByStatus(status)) {
                if (pending.getParameterChanges() == null) {
                    continue;
                }
                for (ParameterChange pendingChange : pending.getParameterChanges()) {
                    if (pendingChange != null && newParams.contains(pendingChange.getParameterName())) {
                        throw new ParameterConflictException(
                                "Parameter '" + pendingChange.getParameterName()
                                        + "' has pending change in proposal " + pending.getProposalId()
                                        + " (status=" + status + "); reject submission to avoid concurrent mutation.");
                    }
                }
            }
        }
    }

    /**
     * 对指定提案投票。
     *
     * @param proposalId 提案 ID
     * @param voter      投票人地址
     * @param option     投票选项
     */
    public void vote(String proposalId, String voter, VoteOption option) {
        Optional<GovernanceProposal> opt = proposalRepository.findById(proposalId);
        if (!opt.isPresent()) {
            logger.warn("Vote rejected: proposal not found {}", proposalId);
            return;
        }
        GovernanceProposal proposal = opt.get();
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
        Optional<GovernanceProposal> opt = proposalRepository.findById(proposalId);
        if (!opt.isPresent()) {
            return false;
        }
        GovernanceProposal proposal = opt.get();
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
        proposalRepository.save(proposal);
        logger.info("Tally for {}: yes={} no={} abstain={} quorum={} -> {}",
                proposalId, tally.getYes(), tally.getNo(), tally.getAbstain(), quorum, proposal.getStatus());
        return passed;
    }

    @Override
    public boolean executeIfPassed(String proposalId) {
        Optional<GovernanceProposal> opt = proposalRepository.findById(proposalId);
        if (!opt.isPresent()) {
            return false;
        }
        GovernanceProposal proposal = opt.get();
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
        return proposalRepository.findById(proposalId).orElse(null);
    }

    /**
     * 列出所有提案。
     *
     * @return 提案列表
     */
    public List<GovernanceProposal> listProposals() {
        return proposalRepository.findAll();
    }

    /**
     * 标记过期提案（投票期结束且未通过，或执行期结束未执行）。
     *
     * @param proposalId 提案 ID
     * @return 标记为过期返回 true
     */
    public boolean markExpiredIfApplicable(String proposalId) {
        Optional<GovernanceProposal> opt = proposalRepository.findById(proposalId);
        if (!opt.isPresent()) {
            return false;
        }
        GovernanceProposal proposal = opt.get();
        Instant now = Instant.now();
        if (proposal.getStatus() == ProposalStatus.VOTING && now.isAfter(proposal.getVotingEnd())) {
            proposal.setStatus(ProposalStatus.EXPIRED);
            proposalRepository.save(proposal);
            logger.info("Proposal {} expired (voting ended)", proposalId);
            return true;
        }
        if (proposal.getStatus() == ProposalStatus.PASSED
                && proposal.getExecutionEnd() != null
                && now.isAfter(proposal.getExecutionEnd())) {
            proposal.setStatus(ProposalStatus.EXPIRED);
            proposalRepository.save(proposal);
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

    /**
     * 创建回滚提案：把所有参数恢复到指定历史快照版本，走正常治理审批流程。
     *
     * <p>回滚不直接改写配置，而是生成一个 {@link ProposalType#PARAMETER_CHANGE} 提案，
     * 其参数变更为每个参数从当前值到快照值。提案需经投票、timelock 审批后才能执行，
     * 确保回滚操作受社区治理约束，避免单点误操作直接改写链上配置。</p>
     *
     * @param targetVersion 目标快照版本号
     * @param proposer      提案发起人地址
     * @return 提案 ID；快照不存在或无参数差异返回 null
     */
    public String createRollbackProposal(int targetVersion, String proposer) {
        ConfigSnapshot snapshot = parameterRegistry.getSnapshot(targetVersion);
        if (snapshot == null) {
            logger.warn("Rollback proposal rejected: snapshot version {} not found", targetVersion);
            return null;
        }
        List<ParameterChange> changes = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : snapshot.getValues().entrySet()) {
            String name = e.getKey();
            GovernableParameter param = parameterRegistry.getParameter(name);
            if (param == null) {
                continue;
            }
            String currentValue = param.getCurrentValue().toPlainString();
            String snapshotValue = e.getValue().toPlainString();
            if (currentValue.equals(snapshotValue)) {
                continue;
            }
            changes.add(new ParameterChange(name, currentValue, snapshotValue, 0L));
        }
        if (changes.isEmpty()) {
            logger.info("Rollback proposal skipped: no parameter differs from snapshot version {}", targetVersion);
            return null;
        }
        GovernanceProposal proposal = new GovernanceProposal();
        proposal.setType(ProposalType.PARAMETER_CHANGE);
        proposal.setParameterChanges(changes);
        proposal.setProposer(proposer);
        String id = submitProposal(proposal);
        logger.info("Rollback proposal submitted: id={} targetVersion={} changes={}",
                id, targetVersion, changes.size());
        return id;
    }
}
