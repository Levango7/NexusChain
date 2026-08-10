package org.nexus.oracle.governance.execution;

import lombok.extern.slf4j.Slf4j;
import org.nexus.oracle.governance.GovernanceService;
import org.nexus.oracle.governance.Proposal;
import org.nexus.oracle.governance.ProposalState;
import org.nexus.oracle.governance.event.GovernanceExecutionCompletedEvent;
import org.nexus.oracle.governance.event.ProposalStatusChangedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 治理执行调度器。
 *
 * <p>监听 {@link ProposalStatusChangedEvent}，当提案状态变为
 * {@link ProposalState#PASSED}（投票通过）时，根据提案类型分发到对应执行器：
 * <ul>
 *   <li>{@link Proposal.Type#SOFTWARE_UPGRADE} → {@link SoftwareUpgradeExecutor}</li>
 *   <li>{@link Proposal.Type#TREASURY_SPEND} → {@link TreasurySpendExecutor}</li>
 *   <li>{@link Proposal.Type#PARAMETER_CHANGE} — 由 {@link GovernanceService} 内联执行，不在此分发</li>
 * </ul>
 *
 * <p>支持 {@code @Async} 异步执行（通过 {@code governance.execution.async-execution} 配置控制），
 * 异常处理：执行失败时回写提案状态为 {@link ProposalState#EXECUTION_FAILED} + 错误信息。
 *
 * <p>可通过 {@code governance.execution.enabled=false} 禁用整个调度器
 * （如维护窗口期或回退到旧的占位执行模式）。
 *
 * <p><b>GOV-P0-01 安全修复</b>：通过 {@code governance.execution.trusted-sources} 白名单
 * 校验事件来源（{@link ProposalStatusChangedEvent#getSource()}），仅处理白名单内来源的事件，
 * 非白名单来源记录 WARN 日志并跳过，避免任意组件伪造事件触发治理执行。
 *
 * <p><b>GOV-P1-01 安全修复</b>：状态变更通过 {@link Proposal#transitionTo(ProposalState)}
 * 进行，校验转换合法性。
 *
 * <p><b>GOV-P2-02 安全修复</b>：{@code @Async} 方法异常通过 try-catch 兜底处理，
 * 记录 ERROR 日志并回写提案状态为 {@link ProposalState#EXECUTION_FAILED}。
 * 同时建议配置 {@link GovernanceAsyncUncaughtExceptionHandler} 作为最后防线。
 *
 * @since 2.0.0
 */
@Slf4j
@Component
public class GovernanceExecutionDispatcher {

    private final GovernanceService governanceService;
    private final SoftwareUpgradeExecutor softwareUpgradeExecutor;
    private final TreasurySpendExecutor treasurySpendExecutor;
    private final ApplicationEventPublisher eventPublisher;
    private final GovernanceAuditLog auditLog;

    /** 治理执行总开关 */
    @Value("${governance.execution.enabled:true}")
    private boolean enabled;

    /** 受信任的事件来源白名单（GOV-P0-01），逗号分隔，默认 "governance-service" */
    @Value("${governance.execution.trusted-sources:governance-service}")
    private String trustedSourcesConfig;

    /**
     * 构造调度器。
     *
     * @param governanceService        治理服务（用于查询提案）
     * @param softwareUpgradeExecutor  软件升级执行器
     * @param treasurySpendExecutor    国库转账执行器
     * @param eventPublisher           Spring 事件发布器
     * @param auditLog                 治理审计日志
     */
    @Autowired
    public GovernanceExecutionDispatcher(GovernanceService governanceService,
                                         SoftwareUpgradeExecutor softwareUpgradeExecutor,
                                         TreasurySpendExecutor treasurySpendExecutor,
                                         ApplicationEventPublisher eventPublisher,
                                         GovernanceAuditLog auditLog) {
        this.governanceService = Objects.requireNonNull(governanceService, "governanceService must not be null");
        this.softwareUpgradeExecutor = Objects.requireNonNull(softwareUpgradeExecutor,
                "softwareUpgradeExecutor must not be null");
        this.treasurySpendExecutor = Objects.requireNonNull(treasurySpendExecutor,
                "treasurySpendExecutor must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog must not be null");
    }

    /**
     * 监听提案状态变更事件，当状态变为 PASSED 时分发执行。
     *
     * <p>使用 {@code @Async} 异步执行，避免阻塞事件发布方。
     * 通过 {@code @Transactional} 保证提案状态回写的事务一致性。
     *
     * <p><b>GOV-P0-01</b>：校验事件来源是否在白名单内，非白名单来源记录 WARN 日志并跳过。
     *
     * <p><b>GOV-P2-02</b>：整个方法体包裹在 try-catch 中，确保 {@code @Async}
     * 异常不被吞掉，记录 ERROR 日志并回写提案状态为 {@link ProposalState#EXECUTION_FAILED}。
     *
     * @param event 提案状态变更事件
     */
    @Async
    @EventListener
    @Transactional
    public void onProposalStatusChanged(ProposalStatusChangedEvent event) {
        try {
            if (!enabled) {
                log.debug("Governance execution disabled; skip dispatch for proposalId={}",
                        event.getProposalId());
                return;
            }
            // GOV-P0-01: 校验事件来源是否在白名单内
            if (!isTrustedSource(event.getSource())) {
                log.warn("GOV-P0-01: Untrusted event source rejected: proposalId={}, source={}, trustedSources={}",
                        event.getProposalId(), event.getSource(), trustedSourcesConfig);
                return;
            }
            if (event.getNewState() != ProposalState.PASSED) {
                log.debug("Proposal state changed but not PASSED; skip dispatch: proposalId={}, newState={}",
                        event.getProposalId(), event.getNewState());
                return;
            }
            dispatch(event.getProposalId(), event.getProposal());
        } catch (Exception e) {
            // GOV-P2-02: @Async 异常兜底处理，确保不被吞掉
            log.error("GOV-P2-02: Async governance execution failed unexpectedly: proposalId={}, error={}",
                    event == null ? "null" : event.getProposalId(), e.getMessage(), e);

            // 尝试回写提案状态为 EXECUTION_FAILED
            if (event != null && event.getProposal() != null) {
                try {
                    markExecutionFailed(event.getProposal(),
                            "Async execution error: " + ErrorMessageSanitizer.sanitizeErrorMessage(e));
                } catch (Exception fallbackErr) {
                    log.error("GOV-P2-02: Fallback markExecutionFailed also failed: proposalId={}",
                            event.getProposalId(), fallbackErr);
                }
            }
        }
    }

    /**
     * 判断事件来源是否在受信任白名单内（GOV-P0-01）。
     *
     * @param source 事件来源标识
     * @return 在白名单内返回 true；否则返回 false
     */
    private boolean isTrustedSource(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        Set<String> trusted = parseTrustedSources(trustedSourcesConfig);
        return trusted.contains(source);
    }

    /**
     * 解析受信任来源白名单配置（逗号分隔）。
     *
     * @param config 原始配置字符串
     * @return 去空白后的来源集合
     */
    private static Set<String> parseTrustedSources(String config) {
        if (config == null || config.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(config.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * 手动触发提案执行分发（用于非事件驱动的同步调用场景）。
     *
     * @param proposalId 提案 ID
     * @return 执行是否成功（提案不存在或类型不匹配返回 false）
     */
    public boolean dispatch(String proposalId) {
        if (!enabled) {
            log.debug("Governance execution disabled; skip manual dispatch for proposalId={}", proposalId);
            return false;
        }
        Proposal proposal = governanceService.getProposal(proposalId);
        if (proposal == null) {
            log.warn("Manual dispatch: proposal not found: proposalId={}", proposalId);
            return false;
        }
        if (proposal.getState() != ProposalState.PASSED) {
            log.warn("Manual dispatch: proposal not PASSED: proposalId={}, state={}",
                    proposalId, proposal.getState());
            return false;
        }
        return dispatch(proposalId, proposal);
    }

    /**
     * 内部分发逻辑：根据提案类型路由到对应执行器。
     *
     * @param proposalId 提案 ID
     * @param proposal   提案对象
     * @return 执行是否成功
     */
    private boolean dispatch(String proposalId, Proposal proposal) {
        if (proposal == null) {
            log.error("Dispatch aborted: proposal is null, proposalId={}", proposalId);
            return false;
        }
        Proposal.Type type = proposal.getType();
        log.info("Dispatching governance execution: proposalId={}, type={}", proposalId, type);

        try {
            switch (type) {
                case SOFTWARE_UPGRADE:
                    SoftwareUpgradeExecutor.ExecutionResult upgradeResult =
                            softwareUpgradeExecutor.execute(proposal);
                    return upgradeResult.isSuccess();

                case TREASURY_SPEND:
                    TreasurySpendExecutor.ExecutionResult spendResult =
                            treasurySpendExecutor.execute(proposal);
                    return spendResult.isSuccess();

                case PARAMETER_CHANGE:
                    // PARAMETER_CHANGE 由 GovernanceService.executeProposal 内联处理，不在此分发
                    log.debug("PARAMETER_CHANGE proposal not dispatched (handled by GovernanceService): {}",
                            proposalId);
                    return true;

                default:
                    log.error("Unknown proposal type for dispatch: proposalId={}, type={}", proposalId, type);
                    markExecutionFailed(proposal, "Unknown proposal type: " + type);
                    return false;
            }
        } catch (Exception e) {
            // 执行器内部已捕获异常并回写状态，此处兜底防止调度器崩溃
            log.error("Dispatch unexpected error: proposalId={}, type={}, error={}",
                    proposalId, type, e.getMessage(), e);
            markExecutionFailed(proposal, "Dispatch error: " + e.getMessage());
            return false;
        }
    }

    /**
     * 标记提案执行失败（兜底异常处理）。
     *
     * <p>更新提案状态为 {@link ProposalState#EXECUTION_FAILED}，记录审计日志，
     * 并发出 {@link GovernanceExecutionCompletedEvent} 事件。
     *
     * <p>GOV-P1-01：状态变更通过 {@link Proposal#transitionTo(ProposalState)} 进行，
     * 校验转换合法性。如果转换非法（如从终态转换），记录警告但不抛异常（兜底场景容错）。
     *
     * <p>GOV-P2-01：错误信息经 {@link ErrorMessageSanitizer} 脱敏后存入审计与事件。
     *
     * @param proposal      提案对象
     * @param errorMessage  错误信息
     */
    private void markExecutionFailed(Proposal proposal, String errorMessage) {
        ProposalState previousState = proposal.getState();
        // GOV-P2-01: 脱敏错误信息
        String sanitizedMessage = ErrorMessageSanitizer.sanitizeErrorMessage(errorMessage);

        // GOV-P1-01: 使用 transitionTo 校验状态转换合法性
        try {
            proposal.transitionTo(ProposalState.EXECUTION_FAILED);
        } catch (IllegalStateException e) {
            // 兜底场景：如果状态转换非法（如已终态），记录警告但不抛异常
            log.warn("Cannot transition to EXECUTION_FAILED from {}: proposalId={}, error={}",
                    previousState, proposal.getProposalId(), e.getMessage());
        }

        auditLog.record(proposal.getProposalId(),
                proposal.getType() == null ? "UNKNOWN" : proposal.getType().name(),
                proposal.getProposer(), previousState, ProposalState.EXECUTION_FAILED,
                false, java.util.Map.of("errorMessage", sanitizedMessage));

        eventPublisher.publishEvent(new GovernanceExecutionCompletedEvent(
                proposal.getProposalId(),
                proposal.getType() == null ? "UNKNOWN" : proposal.getType().name(),
                false, ProposalState.EXECUTION_FAILED, sanitizedMessage, proposal));
    }

    /**
     * 查询治理执行是否启用。
     *
     * @return 启用返回 true
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置治理执行开关（测试 / 运维用）。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 设置受信任事件来源白名单配置（测试 / 运维用，GOV-P0-01）。
     *
     * @param trustedSourcesConfig 逗号分隔的来源白名单
     */
    public void setTrustedSourcesConfig(String trustedSourcesConfig) {
        this.trustedSourcesConfig = trustedSourcesConfig;
    }

    /**
     * 查询受信任事件来源白名单配置（GOV-P0-01）。
     *
     * @return 白名单配置字符串
     */
    public String getTrustedSourcesConfig() {
        return trustedSourcesConfig;
    }
}
