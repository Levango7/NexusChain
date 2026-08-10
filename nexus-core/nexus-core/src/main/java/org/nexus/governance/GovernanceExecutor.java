package org.nexus.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 治理提案执行器。
 *
 * <p>提案投票通过后，先经 {@link TimelockController} 延迟排队，
 * 延迟到期后实际执行（应用参数变更等）。执行前可取消。</p>
 *
 * <h3>执行流程（配置事务）</h3>
 * <ol>
 *   <li>{@link #captureConfigSnapshot()} 捕获参数注册表快照</li>
 *   <li>{@link #beginConfigTransaction(String)} 开启配置事务</li>
 *   <li>对每个 {@link ParameterChange}：二次 {@link GovernableParameterRegistry#validate}
 *       校验 + {@link GovernableParameterRegistry#setParameter} 落盘</li>
 *   <li>全部成功则 {@link #commitConfigTransaction(String)} 提交，提案置 {@link ProposalStatus#EXECUTED}</li>
 *   <li>任一失败则 {@link #rollbackConfig(String, Map)} 回滚至快照，提案置 {@link ProposalStatus#FAILED}</li>
 * </ol>
 *
 * <p>二次校验用于防止 timelock 期内参数范围被另一提案修改导致越界写入。</p>
 *
 * @since 1.2
 */
@Component
public class GovernanceExecutor {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceExecutor.class);

    @Autowired
    private TimelockController timelock;

    @Autowired
    private GovernableParameterRegistry parameterRegistry;

    @Autowired
    private ExecutionStateRepository executionStateRepository;

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
        Duration timelockDelay = resolveTimelockDelay(proposal);
        timelock.schedule(txId, () -> execute(proposal), now, timelockDelay);
        executionStateRepository.save(proposal.getProposalId(), new ExecutionState(txId, now));
        logger.info("Scheduled execution for proposal {} (timelock eta={} delay={})",
                proposal.getProposalId(), timelock.getEta(txId), timelockDelay);
        return true;
    }

    /**
     * 根据提案涉及的参数解析 timelock 延迟：
     * <ol>
     *   <li>若提案修改 {@link TimelockController#TIMELOCK_DELAY_PARAM}（gov.timelockDelay）本身，强制 14 天</li>
     *   <li>否则取所有变更参数中最高敏感度对应的延迟（LOW=1d/MEDIUM=2d/HIGH=7d）</li>
     * </ol>
     *
     * @param proposal 提案
     * @return timelock 延迟时长
     */
    private Duration resolveTimelockDelay(GovernanceProposal proposal) {
        if (proposal.getParameterChanges() == null || proposal.getParameterChanges().isEmpty()) {
            return timelock.getDelay();
        }
        ParameterSensitivity highest = null;
        for (ParameterChange change : proposal.getParameterChanges()) {
            if (change == null || change.getParameterName() == null) {
                continue;
            }
            if (TimelockController.TIMELOCK_DELAY_PARAM.equals(change.getParameterName())) {
                logger.info("Proposal {} modifies gov.timelockDelay itself; forcing 14d timelock",
                        proposal.getProposalId());
                return TimelockController.DELAY_TIMELOCK_CHANGE;
            }
            GovernableParameter param = parameterRegistry.getParameter(change.getParameterName());
            if (param == null) {
                continue;
            }
            highest = maxSensitivity(highest, param.getSensitivity());
        }
        return timelock.delayFor(highest);
    }

    /**
     * 返回两个敏感度中较高者。
     *
     * @param a 敏感度 a（可为 null）
     * @param b 敏感度 b
     * @return 较高敏感度
     */
    private ParameterSensitivity maxSensitivity(ParameterSensitivity a, ParameterSensitivity b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        int ra = a.ordinal();
        int rb = b.ordinal();
        return ra >= rb ? a : b;
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
        String proposalId = proposal.getProposalId();
        Map<String, BigDecimal> snapshot = captureConfigSnapshot();
        beginConfigTransaction(proposalId);
        try {
            if (proposal.getParameterChanges() != null) {
                for (ParameterChange change : proposal.getParameterChanges()) {
                    String paramName = change.getParameterName();
                    String newValue = change.getNewValue();
                    // 二次校验：防止 timelock 期内参数范围被修改
                    if (!parameterRegistry.validate(paramName, newValue)) {
                        throw new IllegalStateException("Secondary validation failed for parameter "
                                + paramName + " value=" + newValue + " (timelock-window mutation detected?)");
                    }
                    if (!parameterRegistry.setParameter(paramName, newValue)) {
                        throw new IllegalStateException("Apply failed for parameter " + paramName);
                    }
                    logger.info("Applied parameter change: {} -> {} at height {}",
                            paramName, newValue, change.getEffectiveHeight());
                }
            }
            commitConfigTransaction(proposalId);
            proposal.setStatus(ProposalStatus.EXECUTED);
            executionStateRepository.remove(proposalId);
            logger.info("Proposal {} executed", proposalId);
            return true;
        } catch (Exception e) {
            logger.error("Failed to execute proposal {}, rolling back config", proposalId, e);
            rollbackConfig(proposalId, snapshot);
            proposal.setStatus(ProposalStatus.FAILED);
            executionStateRepository.remove(proposalId);
            return false;
        }
    }

    /**
     * 捕获参数注册表当前值快照，用于失败回滚。
     *
     * @return 参数名 -> 当前值 的快照
     */
    protected Map<String, BigDecimal> captureConfigSnapshot() {
        Map<String, BigDecimal> snap = parameterRegistry.snapshot();
        logger.debug("Config snapshot captured ({} params)", snap.size());
        return snap;
    }

    /**
     * 开启配置事务（逻辑标记）。
     *
     * @param proposalId 提案 ID
     */
    protected void beginConfigTransaction(String proposalId) {
        logger.info("Config transaction begun for proposal {}", proposalId);
    }

    /**
     * 提交配置事务。
     *
     * @param proposalId 提案 ID
     */
    protected void commitConfigTransaction(String proposalId) {
        logger.info("Config transaction committed for proposal {}", proposalId);
    }

    /**
     * 回滚配置至快照。
     *
     * @param proposalId 提案 ID
     * @param snapshot   快照
     */
    protected void rollbackConfig(String proposalId, Map<String, BigDecimal> snapshot) {
        parameterRegistry.restore(snapshot);
        logger.warn("Config rolled back for proposal {} to snapshot", proposalId);
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
        ExecutionState state = executionStateRepository.get(proposal.getProposalId());
        if (state == null) {
            return false;
        }
        boolean cancelled = timelock.cancel(state.getTxId());
        if (cancelled) {
            executionStateRepository.remove(proposal.getProposalId());
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
        ExecutionState state = executionStateRepository.get(proposal.getProposalId());
        if (state == null) {
            return false;
        }
        return timelock.execute(state.getTxId(), now);
    }
}
