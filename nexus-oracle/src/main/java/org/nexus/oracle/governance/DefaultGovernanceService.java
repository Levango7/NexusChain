package org.nexus.oracle.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 *   <li>{@link #executeProposal}：校验 PASSED + 已过执行延迟 → 按 type 分发执行 → 标记 EXECUTED</li>
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

    /** 可治理参数注册表（PARAMETER_CHANGE 提案落地目标） */
    private final GovernableParameterRegistry parameterRegistry;

    /**
     * 默认构造器：内部创建 {@link DefaultGovernableParameterRegistry}。
     *
     * <p>保留无参构造以兼容直接 {@code new} 实例化的场景（如单元测试）。
     */
    public DefaultGovernanceService() {
        this(new DefaultGovernableParameterRegistry());
    }

    /**
     * 注入构造器：由 Spring 自动注入 {@link GovernableParameterRegistry}。
     *
     * @param parameterRegistry 可治理参数注册表
     */
    @Autowired
    public DefaultGovernanceService(GovernableParameterRegistry parameterRegistry) {
        this.parameterRegistry = parameterRegistry != null
                ? parameterRegistry : new DefaultGovernableParameterRegistry();
    }

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
        // 按 type 分发真实执行
        if (!dispatchExecution(proposal)) {
            log.warn("Proposal execution dispatched but failed: id={}, type={}", proposalId, proposal.getType());
            return false;
        }
        proposal.setState(ProposalState.EXECUTED);
        log.info("Proposal executed: id={}, type={}", proposalId, proposal.getType());
        return true;
    }

    /**
     * 按提案类型分发执行。
     *
     * <ul>
     *   <li>{@link Proposal.Type#PARAMETER_CHANGE}：通过 {@link GovernableParameterRegistry}
     *       应用参数变更，失败时回滚至快照</li>
     *   <li>{@link Proposal.Type#SOFTWARE_UPGRADE}：日志记录目标版本，实际节点滚动重启
     *       需 DevOps 编排（占位）</li>
     *   <li>{@link Proposal.Type#TREASURY_SPEND}：日志记录支出参数，实际转账由
     *       {@link Treasury#spend} 显式触发或后续接入链上国库合约（占位）</li>
     * </ul>
     *
     * @param proposal 已通过且过执行延迟的提案
     * @return 执行成功返回 true；失败返回 false（提案不置 EXECUTED）
     */
    private boolean dispatchExecution(Proposal proposal) {
        String proposalId = proposal.getProposalId();
        Proposal.Type type = proposal.getType();
        Map<String, Object> params = proposal.getParameters();
        if (type == null) {
            log.error("Proposal {} has null type; cannot dispatch execution", proposalId);
            return false;
        }
        switch (type) {
            case PARAMETER_CHANGE:
                return executeParameterChange(proposalId, params);
            case SOFTWARE_UPGRADE:
                executeSoftwareUpgrade(proposalId, params);
                return true;
            case TREASURY_SPEND:
                executeTreasurySpend(proposalId, params);
                return true;
            default:
                log.error("Unknown proposal type {} for proposal {}", type, proposalId);
                return false;
        }
    }

    /**
     * 执行参数变更提案。
     *
     * <p>对 {@code parameters} 中每个键值对依次校验并应用，任一失败则
     * 回滚至执行前快照并返回 false。空参数视为无操作（记录警告后返回 true，
     * 提案仍标记 EXECUTED，表示治理决议已落地但无具体参数变更）。
     *
     * @param proposalId 提案 ID
     * @param params     参数变更映射（参数名 → 新值）
     * @return 全部应用成功或无参数返回 true；任一校验/应用失败返回 false
     */
    private boolean executeParameterChange(String proposalId, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            log.warn("Parameter change proposal {} has no parameters; nothing to apply", proposalId);
            return true;
        }
        Map<String, Object> snapshot = parameterRegistry.snapshot();
        int applied = 0;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue() == null ? null : String.valueOf(entry.getValue());
            if (!parameterRegistry.validate(name, value)) {
                log.error("Parameter validation failed for proposal {}: {} = {} (rolling back)", proposalId, name, value);
                parameterRegistry.restore(snapshot);
                return false;
            }
            if (!parameterRegistry.setParameter(name, value)) {
                log.error("Parameter apply failed for proposal {}: {} = {} (rolling back)", proposalId, name, value);
                parameterRegistry.restore(snapshot);
                return false;
            }
            applied++;
        }
        log.info("Parameter change applied for proposal {}: {} param(s) updated", proposalId, applied);
        return true;
    }

    /**
     * 执行软件升级提案。
     *
     * <p>当前为日志记录 + 占位：实际节点版本切换需 DevOps 配合滚动重启，
     * 此处仅记录目标版本，提案状态仍标记为 EXECUTED（治理决议已落地）。
     *
     * @param proposalId 提案 ID
     * @param params     提案参数（期望包含 {@code targetVersion}）
     */
    private void executeSoftwareUpgrade(String proposalId, Map<String, Object> params) {
        Object targetVersion = params == null ? null : params.get("targetVersion");
        log.warn("Software upgrade proposal {} recorded: targetVersion={}. "
                        + "Actual node rollout requires DevOps orchestration (FROZEN per ADR-001: integrate with rollout pipeline). "
                        + "解冻条件见 docs/adr/ADR-001-research-layer-freeze.md",
                proposalId, targetVersion);
    }

    /**
     * 执行国库支出提案。
     *
     * <p>当前为日志记录 + 占位：实际转账需由 {@link Treasury#spend} 显式触发
     * 或后续接入链上国库合约自动执行。此处仅标注提案已通过治理流程。
     *
     * @param proposalId 提案 ID
     * @param params     提案参数（期望包含 {@code amount} 与 {@code to}）
     */
    private void executeTreasurySpend(String proposalId, Map<String, Object> params) {
        Object amount = params == null ? null : params.get("amount");
        Object to = params == null ? null : params.get("to");
        log.warn("Treasury spend proposal {} recorded: amount={}, to={}. "
                        + "Actual transfer requires Treasury.spend() invocation or on-chain treasury contract "
                        + "(FROZEN per ADR-001). 解冻条件见 docs/adr/ADR-001-research-layer-freeze.md",
                proposalId, amount, to);
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
