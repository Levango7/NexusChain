package org.nexus.oracle.governance.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nexus.oracle.governance.GovernanceService;
import org.nexus.oracle.governance.Proposal;
import org.nexus.oracle.governance.ProposalState;
import org.nexus.oracle.governance.event.GovernanceExecutionCompletedEvent;
import org.nexus.oracle.governance.event.ProposalStatusChangedEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GovernanceExecutionDispatcher} 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>正常分发：PASSED + SOFTWARE_UPGRADE / TREASURY_SPEND → 对应执行器</li>
 *   <li>非 PASSED 状态不分发</li>
 *   <li>PARAMETER_CHANGE 不分发（由 GovernanceService 内联处理）</li>
 *   <li>enabled=false 时不分发</li>
 *   <li>手动 dispatch 方法</li>
 *   <li>异常兜底处理</li>
 *   <li>GOV-P0-01: 事件源白名单校验（白名单内通过，白名单外拒绝）</li>
 * </ul>
 */
class GovernanceExecutionDispatcherTest {

    private GovernanceService governanceService;
    private SoftwareUpgradeExecutor softwareUpgradeExecutor;
    private TreasurySpendExecutor treasurySpendExecutor;
    private ValidatorSetExecutor validatorSetExecutor;
    private ApplicationEventPublisher eventPublisher;
    private GovernanceAuditLog auditLog;
    private GovernanceExecutionDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        governanceService = mock(GovernanceService.class);
        softwareUpgradeExecutor = mock(SoftwareUpgradeExecutor.class);
        treasurySpendExecutor = mock(TreasurySpendExecutor.class);
        validatorSetExecutor = mock(ValidatorSetExecutor.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        auditLog = new GovernanceAuditLog();
        dispatcher = new GovernanceExecutionDispatcher(
                governanceService, softwareUpgradeExecutor, treasurySpendExecutor,
                validatorSetExecutor, eventPublisher, auditLog);
        dispatcher.setEnabled(true);
        // 默认白名单：governance-service（与 ProposalStatusChangedEvent.DEFAULT_SOURCE 一致）
        dispatcher.setTrustedSourcesConfig("governance-service");
    }

    private Proposal buildProposal(Proposal.Type type, ProposalState state) {
        return Proposal.builder()
                .proposalId("PROP-" + type.name() + "-001")
                .title("test")
                .type(type)
                .state(state)
                .proposer("p")
                .parameters(type == Proposal.Type.SOFTWARE_UPGRADE
                        ? Map.of("target", "gateway", "version", "2.1.0")
                        : type == Proposal.Type.TREASURY_SPEND
                        ? Map.of("targetAddress", "0x1234567890123456789012345678901234567890",
                                "amount", "100", "token", "USDT", "chain", "ethereum")
                        : Map.of())
                .build();
    }

    // ---------- onProposalStatusChanged ----------

    @Test
    void onProposalStatusChanged_passedSoftwareUpgrade_shouldDispatchToUpgradeExecutor() {
        Proposal proposal = buildProposal(Proposal.Type.SOFTWARE_UPGRADE, ProposalState.PASSED);
        when(softwareUpgradeExecutor.execute(proposal))
                .thenReturn(SoftwareUpgradeExecutor.ExecutionResult.success("gateway", "2.1.0"));

        dispatcher.onProposalStatusChanged(
                new ProposalStatusChangedEvent(proposal.getProposalId(),
                        ProposalState.ACTIVE, ProposalState.PASSED, proposal));

        verify(softwareUpgradeExecutor, times(1)).execute(proposal);
        verify(treasurySpendExecutor, never()).execute(any());
    }

    @Test
    void onProposalStatusChanged_passedTreasurySpend_shouldDispatchToSpendExecutor() {
        Proposal proposal = buildProposal(Proposal.Type.TREASURY_SPEND, ProposalState.PASSED);
        when(treasurySpendExecutor.execute(proposal))
                .thenReturn(TreasurySpendExecutor.ExecutionResult.success("0xhash"));

        dispatcher.onProposalStatusChanged(
                new ProposalStatusChangedEvent(proposal.getProposalId(),
                        ProposalState.ACTIVE, ProposalState.PASSED, proposal));

        verify(treasurySpendExecutor, times(1)).execute(proposal);
        verify(softwareUpgradeExecutor, never()).execute(any());
    }

    @Test
    void onProposalStatusChanged_passedParameterChange_shouldNotDispatch() {
        Proposal proposal = buildProposal(Proposal.Type.PARAMETER_CHANGE, ProposalState.PASSED);

        dispatcher.onProposalStatusChanged(
                new ProposalStatusChangedEvent(proposal.getProposalId(),
                        ProposalState.ACTIVE, ProposalState.PASSED, proposal));

        verify(softwareUpgradeExecutor, never()).execute(any());
        verify(treasurySpendExecutor, never()).execute(any());
    }

    @Test
    void onProposalStatusChanged_notPassed_shouldNotDispatch() {
        Proposal proposal = buildProposal(Proposal.Type.SOFTWARE_UPGRADE, ProposalState.REJECTED);

        dispatcher.onProposalStatusChanged(
                new ProposalStatusChangedEvent(proposal.getProposalId(),
                        ProposalState.ACTIVE, ProposalState.REJECTED, proposal));

        verify(softwareUpgradeExecutor, never()).execute(any());
        verify(treasurySpendExecutor, never()).execute(any());
    }

    @Test
    void onProposalStatusChanged_disabled_shouldNotDispatch() {
        dispatcher.setEnabled(false);
        Proposal proposal = buildProposal(Proposal.Type.SOFTWARE_UPGRADE, ProposalState.PASSED);

        dispatcher.onProposalStatusChanged(
                new ProposalStatusChangedEvent(proposal.getProposalId(),
                        ProposalState.ACTIVE, ProposalState.PASSED, proposal));

        verify(softwareUpgradeExecutor, never()).execute(any());
        verify(treasurySpendExecutor, never()).execute(any());
    }

    @Test
    void onProposalStatusChanged_executorThrows_shouldMarkFailedAndNotPropagate() {
        Proposal proposal = buildProposal(Proposal.Type.SOFTWARE_UPGRADE, ProposalState.PASSED);
        when(softwareUpgradeExecutor.execute(proposal))
                .thenThrow(new RuntimeException("upgrade failed"));

        // 不应抛出异常
        dispatcher.onProposalStatusChanged(
                new ProposalStatusChangedEvent(proposal.getProposalId(),
                        ProposalState.ACTIVE, ProposalState.PASSED, proposal));

        // 兜底处理：发布 GovernanceExecutionCompletedEvent
        ArgumentCaptor<GovernanceExecutionCompletedEvent> captor =
                ArgumentCaptor.forClass(GovernanceExecutionCompletedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        GovernanceExecutionCompletedEvent event = captor.getValue();
        assertFalse(event.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, event.getFinalState());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
    }

    // ---------- GOV-P0-01: 事件源白名单校验 ----------

    @Test
    void onProposalStatusChanged_trustedSource_shouldDispatch() {
        Proposal proposal = buildProposal(Proposal.Type.SOFTWARE_UPGRADE, ProposalState.PASSED);
        when(softwareUpgradeExecutor.execute(proposal))
                .thenReturn(SoftwareUpgradeExecutor.ExecutionResult.success("gateway", "2.1.0"));

        // 显式指定受信任来源
        dispatcher.onProposalStatusChanged(
                new ProposalStatusChangedEvent(proposal.getProposalId(),
                        ProposalState.ACTIVE, ProposalState.PASSED, proposal,
                        "governance-service"));

        verify(softwareUpgradeExecutor, times(1)).execute(proposal);
    }

    @Test
    void onProposalStatusChanged_untrustedSource_shouldNotDispatch() {
        Proposal proposal = buildProposal(Proposal.Type.SOFTWARE_UPGRADE, ProposalState.PASSED);

        // 非白名单来源
        dispatcher.onProposalStatusChanged(
                new ProposalStatusChangedEvent(proposal.getProposalId(),
                        ProposalState.ACTIVE, ProposalState.PASSED, proposal,
                        "malicious-component"));

        verify(softwareUpgradeExecutor, never()).execute(any());
        verify(treasurySpendExecutor, never()).execute(any());
    }

    @Test
    void onProposalStatusChanged_nullSource_shouldNotDispatch() {
        Proposal proposal = buildProposal(Proposal.Type.SOFTWARE_UPGRADE, ProposalState.PASSED);

        // null 来源
        dispatcher.onProposalStatusChanged(
                new ProposalStatusChangedEvent(proposal.getProposalId(),
                        ProposalState.ACTIVE, ProposalState.PASSED, proposal, null));

        verify(softwareUpgradeExecutor, never()).execute(any());
    }

    @Test
    void onProposalStatusChanged_multipleTrustedSources_shouldDispatch() {
        dispatcher.setTrustedSourcesConfig("governance-service,governance-internal,admin-cli");
        Proposal proposal = buildProposal(Proposal.Type.SOFTWARE_UPGRADE, ProposalState.PASSED);
        when(softwareUpgradeExecutor.execute(proposal))
                .thenReturn(SoftwareUpgradeExecutor.ExecutionResult.success("gateway", "2.1.0"));

        // 白名单中的第二个来源
        dispatcher.onProposalStatusChanged(
                new ProposalStatusChangedEvent(proposal.getProposalId(),
                        ProposalState.ACTIVE, ProposalState.PASSED, proposal,
                        "governance-internal"));

        verify(softwareUpgradeExecutor, times(1)).execute(proposal);
    }

    @Test
    void onProposalStatusChanged_multipleTrustedSources_untrustedShouldReject() {
        dispatcher.setTrustedSourcesConfig("governance-service,governance-internal");
        Proposal proposal = buildProposal(Proposal.Type.SOFTWARE_UPGRADE, ProposalState.PASSED);

        // 不在白名单
        dispatcher.onProposalStatusChanged(
                new ProposalStatusChangedEvent(proposal.getProposalId(),
                        ProposalState.ACTIVE, ProposalState.PASSED, proposal,
                        "rogue-component"));

        verify(softwareUpgradeExecutor, never()).execute(any());
        verify(treasurySpendExecutor, never()).execute(any());
    }

    @Test
    void onProposalStatusChanged_emptyWhitelist_shouldRejectAll() {
        dispatcher.setTrustedSourcesConfig("");
        Proposal proposal = buildProposal(Proposal.Type.SOFTWARE_UPGRADE, ProposalState.PASSED);

        dispatcher.onProposalStatusChanged(
                new ProposalStatusChangedEvent(proposal.getProposalId(),
                        ProposalState.ACTIVE, ProposalState.PASSED, proposal,
                        "governance-service"));

        verify(softwareUpgradeExecutor, never()).execute(any());
    }

    @Test
    void onProposalStatusChanged_whitespaceInWhitelist_shouldTrimAndMatch() {
        dispatcher.setTrustedSourcesConfig("  governance-service  ,  governance-internal  ");
        Proposal proposal = buildProposal(Proposal.Type.SOFTWARE_UPGRADE, ProposalState.PASSED);
        when(softwareUpgradeExecutor.execute(proposal))
                .thenReturn(SoftwareUpgradeExecutor.ExecutionResult.success("gateway", "2.1.0"));

        dispatcher.onProposalStatusChanged(
                new ProposalStatusChangedEvent(proposal.getProposalId(),
                        ProposalState.ACTIVE, ProposalState.PASSED, proposal,
                        "governance-service"));

        verify(softwareUpgradeExecutor, times(1)).execute(proposal);
    }

    // ---------- manual dispatch ----------

    @Test
    void dispatch_softwareUpgradePassed_shouldReturnTrue() {
        Proposal proposal = buildProposal(Proposal.Type.SOFTWARE_UPGRADE, ProposalState.PASSED);
        when(governanceService.getProposal(proposal.getProposalId())).thenReturn(proposal);
        when(softwareUpgradeExecutor.execute(proposal))
                .thenReturn(SoftwareUpgradeExecutor.ExecutionResult.success("gateway", "2.1.0"));

        boolean result = dispatcher.dispatch(proposal.getProposalId());

        assertTrue(result);
        verify(softwareUpgradeExecutor, times(1)).execute(proposal);
    }

    @Test
    void dispatch_treasurySpendPassed_shouldReturnTrue() {
        Proposal proposal = buildProposal(Proposal.Type.TREASURY_SPEND, ProposalState.PASSED);
        when(governanceService.getProposal(proposal.getProposalId())).thenReturn(proposal);
        when(treasurySpendExecutor.execute(proposal))
                .thenReturn(TreasurySpendExecutor.ExecutionResult.success("0xhash"));

        boolean result = dispatcher.dispatch(proposal.getProposalId());

        assertTrue(result);
        verify(treasurySpendExecutor, times(1)).execute(proposal);
    }

    @Test
    void dispatch_proposalNotFound_shouldReturnFalse() {
        when(governanceService.getProposal("NOPE")).thenReturn(null);

        boolean result = dispatcher.dispatch("NOPE");

        assertFalse(result);
        verify(softwareUpgradeExecutor, never()).execute(any());
        verify(treasurySpendExecutor, never()).execute(any());
    }

    @Test
    void dispatch_proposalNotPassed_shouldReturnFalse() {
        Proposal proposal = buildProposal(Proposal.Type.SOFTWARE_UPGRADE, ProposalState.ACTIVE);
        when(governanceService.getProposal(proposal.getProposalId())).thenReturn(proposal);

        boolean result = dispatcher.dispatch(proposal.getProposalId());

        assertFalse(result);
        verify(softwareUpgradeExecutor, never()).execute(any());
    }

    @Test
    void dispatch_disabled_shouldReturnFalse() {
        dispatcher.setEnabled(false);

        boolean result = dispatcher.dispatch("any");

        assertFalse(result);
    }

    @Test
    void dispatch_executorReturnsFailure_shouldReturnFalse() {
        Proposal proposal = buildProposal(Proposal.Type.SOFTWARE_UPGRADE, ProposalState.PASSED);
        when(governanceService.getProposal(proposal.getProposalId())).thenReturn(proposal);
        when(softwareUpgradeExecutor.execute(proposal))
                .thenReturn(SoftwareUpgradeExecutor.ExecutionResult.failure("bad"));

        boolean result = dispatcher.dispatch(proposal.getProposalId());

        assertFalse(result);
    }

    @Test
    void dispatch_parameterChange_shouldReturnTrueWithoutDispatching() {
        Proposal proposal = buildProposal(Proposal.Type.PARAMETER_CHANGE, ProposalState.PASSED);
        when(governanceService.getProposal(proposal.getProposalId())).thenReturn(proposal);

        boolean result = dispatcher.dispatch(proposal.getProposalId());

        assertTrue(result);
        verify(softwareUpgradeExecutor, never()).execute(any());
        verify(treasurySpendExecutor, never()).execute(any());
    }

    @Test
    void isEnabled_shouldReflectConfiguredValue() {
        dispatcher.setEnabled(true);
        assertTrue(dispatcher.isEnabled());
        dispatcher.setEnabled(false);
        assertFalse(dispatcher.isEnabled());
    }
}
