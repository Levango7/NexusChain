package org.nexus.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * <h3>链上治理集成（since 2.1）</h3>
 * <p>当 {@code nexus.governance.on-chain.enabled=true} 且 {@link OnChainGovernanceClient} 可用时，
 * {@link #schedule()} / {@link #execute()} / {@link #cancel()} 在完成内存版操作后，
 * 会将对应操作同步到链上 NexusGovernor 合约。链上调用失败仅记录 warn 日志，
 * 不影响内存版执行结果（内存版作为 fallback）。</p>
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
     * 可选注入的链上治理客户端。
     *
     * <p>仅当 {@code nexus.governance.on-chain-enabled=true} 时由 Spring 容器注入
     * （见 {@link OnChainGovernanceClient} 上的 {@code @ConditionalOnProperty}）。
     * 否则为 null，链上集成逻辑自动跳过。</p>
     */
    @Autowired(required = false)
    private OnChainGovernanceClient onChainGovernanceClient;

    /**
     * 链上治理执行开关。
     *
     * <p>独立于 {@code nexus.governance.on-chain-enabled}（后者控制
     * {@link OnChainGovernanceClient} bean 是否创建），本开关控制
     * {@link GovernanceExecutor} 是否实际调用链上方法。
     * 默认 false 保证向后兼容。</p>
     */
    @Value("${nexus.governance.on-chain.enabled:false}")
    private boolean onChainExecutionEnabled;

    /**
     * 本地提案 ID（String）到链上提案 ID（long）的映射。
     *
     * <p>{@link GovernanceProposal#getProposalId()} 返回 String（通常为 UUID），
     * 而 {@link OnChainGovernanceClient} 的方法接受 long proposalId。
     * 本映射用于桥接两种 ID 类型：外部代码可通过 {@link #mapOnChainProposalId}
     * 注册映射，或 {@link #resolveOnChainProposalId} 尝试直接解析数字字符串。</p>
     */
    private final Map<String, Long> onChainProposalIdMap = new ConcurrentHashMap<>();

    /**
     * 提案通过后调度延迟执行。
     *
     * <p>执行流程：
     * <ol>
     *   <li>内存版：经 {@link TimelockController} 排队，延迟到期后回调 {@link #execute}</li>
     *   <li>链上版（可选）：若 {@code nexus.governance.on-chain.enabled=true} 且
     *       {@link OnChainGovernanceClient} 可用，调用 {@link OnChainGovernanceClient#queueOnChain}
     *       将提案排度到链上 NexusGovernor。链上调用失败仅记录 warn 日志，不影响内存版排队。</li>
     * </ol></p>
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

        // 链上治理集成：内存版排队成功后，同步到链上 NexusGovernor.queue()
        queueOnChainIfEnabled(proposal);

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
     * <p>执行流程：
     * <ol>
     *   <li>内存版：捕获快照 → 二次校验 → 应用参数变更 → 提交/回滚</li>
     *   <li>链上版（可选）：内存版执行成功后，若 {@code nexus.governance.on-chain.enabled=true} 且
     *       {@link OnChainGovernanceClient} 可用，调用 {@link OnChainGovernanceClient#executeOnChain}
     *       在链上执行提案。链上调用失败仅记录 warn 日志，不影响内存版执行结果。</li>
     * </ol></p>
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

            // 链上治理集成：内存版执行成功后，同步到链上 NexusGovernor.execute()
            executeOnChainIfEnabled(proposal);

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
     * <p>执行流程：
     * <ol>
     *   <li>内存版：取消 {@link TimelockController} 中排队的操作</li>
     *   <li>链上版（可选）：内存版取消成功后，若 {@code nexus.governance.on-chain.enabled=true} 且
     *       {@link OnChainGovernanceClient} 可用，调用 {@link OnChainGovernanceClient#cancelOnChain}
     *       在链上取消提案。链上调用失败仅记录 warn 日志，不影响内存版取消结果。</li>
     * </ol></p>
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

            // 链上治理集成：内存版取消成功后，同步到链上 NexusGovernor.cancel()
            cancelOnChainIfEnabled(proposal);
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

    // ==================== 链上治理集成（since 2.1） ====================

    /**
     * 判断链上治理集成是否已启用。
     *
     * <p>需同时满足：
     * <ol>
     *   <li>{@code nexus.governance.on-chain.enabled=true}（本执行器开关）</li>
     *   <li>{@link OnChainGovernanceClient} 已注入且 {@link OnChainGovernanceClient#isReady} 返回 true</li>
     * </ol></p>
     *
     * @return true 表示链上集成可用
     */
    public boolean isOnChainIntegrationEnabled() {
        return onChainExecutionEnabled
                && onChainGovernanceClient != null
                && onChainGovernanceClient.isReady();
    }

    /**
     * 注册本地提案 ID 到链上提案 ID 的映射。
     *
     * <p>由于 {@link GovernanceProposal#getProposalId()} 返回 String（通常为 UUID），
     * 而 {@link OnChainGovernanceClient} 的方法接受 long proposalId，
     * 外部代码（如提案创建服务）在链上创建提案后，应通过本方法注册映射关系，
     * 以便后续 {@link #schedule()} / {@link #execute()} / {@link #cancel()} 能正确调用链上方法。</p>
     *
     * @param localProposalId 本地提案 ID（String）
     * @param onChainProposalId 链上提案 ID（long）
     */
    public void mapOnChainProposalId(String localProposalId, long onChainProposalId) {
        if (localProposalId == null) {
            return;
        }
        onChainProposalIdMap.put(localProposalId, onChainProposalId);
        logger.debug("Mapped local proposalId {} -> on-chain proposalId {}", localProposalId, onChainProposalId);
    }

    /**
     * 解析本地提案 ID 对应的链上提案 ID。
     *
     * <p>解析顺序：
     * <ol>
     *   <li>查 {@link #onChainProposalIdMap} 映射表，命中则返回</li>
     *   <li>尝试将 String 解析为 long（适用于数字型 proposalId），成功则缓存并返回</li>
     *   <li>均失败返回 null（调用方应跳过链上操作）</li>
     * </ol></p>
     *
     * @param localProposalId 本地提案 ID
     * @return 链上提案 ID；无法解析返回 null
     */
    public Long resolveOnChainProposalId(String localProposalId) {
        if (localProposalId == null) {
            return null;
        }
        Long mapped = onChainProposalIdMap.get(localProposalId);
        if (mapped != null) {
            return mapped;
        }
        try {
            long parsed = Long.parseLong(localProposalId);
            onChainProposalIdMap.put(localProposalId, parsed);
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 若链上集成启用，将提案排度到链上 NexusGovernor.queue()。
     *
     * <p>链上调用失败仅记录 warn 日志，不影响内存版排队结果。
     * 提案 ID 无法解析为 long 时同样跳过并记录 warn。</p>
     *
     * @param proposal 提案
     */
    private void queueOnChainIfEnabled(GovernanceProposal proposal) {
        if (!isOnChainIntegrationEnabled()) {
            return;
        }
        String localId = proposal.getProposalId();
        Long onChainId = resolveOnChainProposalId(localId);
        if (onChainId == null) {
            logger.warn("Skip on-chain queue: cannot resolve on-chain proposalId for local id {}", localId);
            return;
        }
        try {
            boolean ok = onChainGovernanceClient.queueOnChain(onChainId);
            if (ok) {
                logger.info("On-chain queue succeeded for proposal {} (onChainId={})", localId, onChainId);
            } else {
                logger.warn("On-chain queue returned false for proposal {} (onChainId={}); "
                        + "in-memory schedule remains active as fallback", localId, onChainId);
            }
        } catch (Exception e) {
            logger.warn("On-chain queue failed for proposal {} (onChainId={}): {}; "
                    + "in-memory schedule remains active as fallback", localId, onChainId, e.getMessage(), e);
        }
    }

    /**
     * 若链上集成启用，在链上执行提案 NexusGovernor.execute()。
     *
     * <p>仅在内存版执行成功后调用。链上调用失败仅记录 warn 日志，
     * 不影响内存版执行结果（内存版已提交，链上失败需人工介入对账）。</p>
     *
     * @param proposal 提案
     */
    private void executeOnChainIfEnabled(GovernanceProposal proposal) {
        if (!isOnChainIntegrationEnabled()) {
            return;
        }
        String localId = proposal.getProposalId();
        Long onChainId = resolveOnChainProposalId(localId);
        if (onChainId == null) {
            logger.warn("Skip on-chain execute: cannot resolve on-chain proposalId for local id {}", localId);
            return;
        }
        try {
            boolean ok = onChainGovernanceClient.executeOnChain(onChainId);
            if (ok) {
                logger.info("On-chain execute succeeded for proposal {} (onChainId={})", localId, onChainId);
            } else {
                logger.warn("On-chain execute returned false for proposal {} (onChainId={}); "
                        + "in-memory execution already committed, manual reconciliation may be needed",
                        localId, onChainId);
            }
        } catch (Exception e) {
            logger.warn("On-chain execute failed for proposal {} (onChainId={}): {}; "
                    + "in-memory execution already committed, manual reconciliation may be needed",
                    localId, onChainId, e.getMessage(), e);
        }
    }

    /**
     * 若链上集成启用，在链上取消提案 NexusGovernor.cancel()。
     *
     * <p>仅在内存版取消成功后调用。链上调用失败仅记录 warn 日志，
     * 不影响内存版取消结果。</p>
     *
     * @param proposal 提案
     */
    private void cancelOnChainIfEnabled(GovernanceProposal proposal) {
        if (!isOnChainIntegrationEnabled()) {
            return;
        }
        String localId = proposal.getProposalId();
        Long onChainId = resolveOnChainProposalId(localId);
        if (onChainId == null) {
            logger.warn("Skip on-chain cancel: cannot resolve on-chain proposalId for local id {}", localId);
            return;
        }
        try {
            boolean ok = onChainGovernanceClient.cancelOnChain(onChainId);
            if (ok) {
                logger.info("On-chain cancel succeeded for proposal {} (onChainId={})", localId, onChainId);
            } else {
                logger.warn("On-chain cancel returned false for proposal {} (onChainId={}); "
                        + "in-memory cancel already done, manual reconciliation may be needed",
                        localId, onChainId);
            }
        } catch (Exception e) {
            logger.warn("On-chain cancel failed for proposal {} (onChainId={}): {}; "
                    + "in-memory cancel already done, manual reconciliation may be needed",
                    localId, onChainId, e.getMessage(), e);
        }
    }
}
