package org.nexus.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 治理提案执行器。
 *
 * <p>提案投票通过后，先经 {@link TimelockController} 延迟排队，
 * 延迟到期后实际执行（应用参数变更等）。执行前可取消。</p>
 *
 * @since 1.2
 */
@Component
public class GovernanceExecutor {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceExecutor.class);

    @Autowired
    private TimelockController timelock;

    /** proposalId -> 执行状态 */
    private final Map<String, ExecutionState> executionState = new ConcurrentHashMap<>();

    /**
     * 提案通过后调度延迟执行。
     *
     * @param proposal 提案
     * @param now      当前时间
     * @return 调度成功返回 true；提案状态非 PASSED 返回 false
     */
    public boolean schedule(GovernanceProposal proposal, Instant now) {
        if (proposal == null) {
            return false;
        }
        if (proposal.getStatus() != ProposalStatus.PASSED) {
            logger.warn("Cannot schedule proposal {} with status {}", proposal.getProposalId(), proposal.getStatus());
            return false;
        }
        String txId = "gov-" + proposal.getProposalId();
        timelock.schedule(txId, () -> execute(proposal), now);
        executionState.put(proposal.getProposalId(), new ExecutionState(txId, now));
        logger.info("Scheduled execution for proposal {} (timelock eta={})",
                proposal.getProposalId(), timelock.getEta(txId));
        return true;
    }

    /**
     * 实际执行提案（应用参数变更）。
     *
     * <p>由 {@link TimelockController} 在延迟到期后回调，
     * 也可手动调用以立即执行（如测试场景）。</p>
     *
     * @param proposal 提案
     * @return 执行成功返回 true
     */
    public boolean execute(GovernanceProposal proposal) {
        if (proposal == null) {
            return false;
        }
        try {
            if (proposal.getParameterChanges() != null) {
                for (ParameterChange change : proposal.getParameterChanges()) {
                    logger.info("Applying parameter change: {} {} -> {} at height {}",
                            change.getParameterName(), change.getOldValue(),
                            change.getNewValue(), change.getEffectiveHeight());
                    // TODO: 实际将参数写入链上配置 / 状态机
                }
            }
            proposal.setStatus(ProposalStatus.EXECUTED);
            executionState.remove(proposal.getProposalId());
            logger.info("Proposal {} executed", proposal.getProposalId());
            return true;
        } catch (Exception e) {
            logger.error("Failed to execute proposal {}", proposal.getProposalId(), e);
            return false;
        }
    }

    /**
     * 取消已调度但未执行的提案。
     *
     * @param proposal 提案
     * @return 取消成功返回 true
     */
    public boolean cancel(GovernanceProposal proposal) {
        if (proposal == null) {
            return false;
        }
        ExecutionState state = executionState.get(proposal.getProposalId());
        if (state == null) {
            return false;
        }
        boolean cancelled = timelock.cancel(state.txId);
        if (cancelled) {
            executionState.remove(proposal.getProposalId());
        }
        return cancelled;
    }

    /**
     * 尝试触发已到期提案的执行。
     *
     * @param proposal 提案
     * @param now      当前时间
     * @return 执行成功返回 true；未到期或已执行返回 false
     */
    public boolean tryExecute(GovernanceProposal proposal, Instant now) {
        if (proposal == null) {
            return false;
        }
        ExecutionState state = executionState.get(proposal.getProposalId());
        if (state == null) {
            return false;
        }
        return timelock.execute(state.txId, now);
    }

    /** 提案执行状态 */
    private static final class ExecutionState {
        final String txId;
        final Instant scheduledAt;

        ExecutionState(String txId, Instant scheduledAt) {
            this.txId = txId;
            this.scheduledAt = scheduledAt;
        }
    }
}