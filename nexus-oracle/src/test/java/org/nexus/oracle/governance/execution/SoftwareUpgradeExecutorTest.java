package org.nexus.oracle.governance.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nexus.oracle.governance.Proposal;
import org.nexus.oracle.governance.ProposalState;
import org.nexus.oracle.governance.event.GovernanceExecutionCompletedEvent;
import org.nexus.oracle.governance.event.SoftwareUpgradeEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link SoftwareUpgradeExecutor} 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>正常执行流程（payload 合法、事件发布、状态回写、审计记录）</li>
 *   <li>异常路径（payload 缺失、target 不支持、version 缺失）</li>
 *   <li>边界条件（null proposal、null parameters）</li>
 * </ul>
 */
class SoftwareUpgradeExecutorTest {

    private ApplicationEventPublisher eventPublisher;
    private GovernanceAuditLog auditLog;
    private SoftwareUpgradeExecutor executor;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(ApplicationEventPublisher.class);
        auditLog = new GovernanceAuditLog();
        executor = new SoftwareUpgradeExecutor(eventPublisher, auditLog);
    }

    private Proposal buildUpgradeProposal(String target, String version) {
        return Proposal.builder()
                .proposalId("PROP-UPG-001")
                .title("Upgrade gateway to 2.1.0")
                .type(Proposal.Type.SOFTWARE_UPGRADE)
                .state(ProposalState.PASSED)
                .proposer("devops-1")
                .parameters(Map.of("target", target, "version", version))
                .build();
    }

    @Test
    void execute_validPayload_shouldSucceedAndWriteBackState() {
        Proposal proposal = buildUpgradeProposal("gateway", "2.1.0");

        SoftwareUpgradeExecutor.ExecutionResult result = executor.execute(proposal);

        assertTrue(result.isSuccess());
        assertEquals("gateway", result.getTarget());
        assertEquals("2.1.0", result.getVersion());
        assertEquals(ProposalState.EXECUTED, proposal.getState());
        assertNotNull(proposal.getExecutionResult());
        assertEquals(true, proposal.getExecutionResult().get("success"));
        assertEquals("gateway", proposal.getExecutionResult().get("target"));
        assertEquals("2.1.0", proposal.getExecutionResult().get("version"));
    }

    @Test
    void execute_validPayload_shouldPublishSoftwareUpgradeEvent() {
        Proposal proposal = buildUpgradeProposal("bridge", "1.5.0");

        executor.execute(proposal);

        ArgumentCaptor<SoftwareUpgradeEvent> captor = ArgumentCaptor.forClass(SoftwareUpgradeEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        SoftwareUpgradeEvent event = captor.getValue();
        assertEquals("PROP-UPG-001", event.getProposalId());
        assertEquals("bridge", event.getTarget());
        assertEquals("1.5.0", event.getVersion());
    }

    @Test
    void execute_validPayload_shouldPublishGovernanceExecutionCompletedEvent() {
        Proposal proposal = buildUpgradeProposal("signing", "3.0.0");

        executor.execute(proposal);

        ArgumentCaptor<GovernanceExecutionCompletedEvent> captor =
                ArgumentCaptor.forClass(GovernanceExecutionCompletedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        GovernanceExecutionCompletedEvent event = captor.getValue();
        assertTrue(event.isSuccess());
        assertEquals(ProposalState.EXECUTED, event.getFinalState());
    }

    @Test
    void execute_validPayload_shouldRecordAuditLog() {
        Proposal proposal = buildUpgradeProposal("wallet", "2.2.0");

        executor.execute(proposal);

        var records = auditLog.getAuditLog("PROP-UPG-001");
        assertEquals(1, records.size());
        var record = records.get(0);
        assertEquals("SOFTWARE_UPGRADE", record.getProposalType());
        assertEquals("devops-1", record.getOperator());
        assertEquals(ProposalState.PASSED, record.getPreviousState());
        assertEquals(ProposalState.EXECUTED, record.getNewState());
        assertTrue(record.isSuccess());
    }

    @Test
    void execute_nullProposal_shouldReturnFailure() {
        SoftwareUpgradeExecutor.ExecutionResult result = executor.execute(null);

        assertFalse(result.isSuccess());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void execute_nullParameters_shouldFailAndWriteBackFailedState() {
        Proposal proposal = Proposal.builder()
                .proposalId("PROP-UPG-002")
                .title("bad")
                .type(Proposal.Type.SOFTWARE_UPGRADE)
                .state(ProposalState.PASSED)
                .proposer("p")
                .build();

        SoftwareUpgradeExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
        assertNotNull(proposal.getExecutionResult());
        assertEquals(false, proposal.getExecutionResult().get("success"));
    }

    @Test
    void execute_missingTarget_shouldFail() {
        Proposal proposal = Proposal.builder()
                .proposalId("PROP-UPG-003")
                .title("bad")
                .type(Proposal.Type.SOFTWARE_UPGRADE)
                .state(ProposalState.PASSED)
                .proposer("p")
                .parameters(Map.of("version", "2.0.0"))
                .build();

        SoftwareUpgradeExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
    }

    @Test
    void execute_missingVersion_shouldFail() {
        Proposal proposal = Proposal.builder()
                .proposalId("PROP-UPG-004")
                .title("bad")
                .type(Proposal.Type.SOFTWARE_UPGRADE)
                .state(ProposalState.PASSED)
                .proposer("p")
                .parameters(Map.of("target", "gateway"))
                .build();

        SoftwareUpgradeExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
    }

    @Test
    void execute_unsupportedTarget_shouldFail() {
        Proposal proposal = buildUpgradeProposal("unknown-service", "1.0.0");

        SoftwareUpgradeExecutor.ExecutionResult result = executor.execute(proposal);

        assertFalse(result.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, proposal.getState());
    }

    @Test
    void execute_failure_shouldPublishCompletedEventWithError() {
        Proposal proposal = Proposal.builder()
                .proposalId("PROP-UPG-FAIL")
                .title("bad")
                .type(Proposal.Type.SOFTWARE_UPGRADE)
                .state(ProposalState.PASSED)
                .proposer("p")
                .parameters(Map.of("target", "unknown", "version", "1.0"))
                .build();

        executor.execute(proposal);

        ArgumentCaptor<GovernanceExecutionCompletedEvent> captor =
                ArgumentCaptor.forClass(GovernanceExecutionCompletedEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        GovernanceExecutionCompletedEvent event = captor.getValue();
        assertFalse(event.isSuccess());
        assertEquals(ProposalState.EXECUTION_FAILED, event.getFinalState());
        assertNotNull(event.getErrorMessage());
    }

    @Test
    void execute_allSupportedTargets_shouldSucceed() {
        for (String target : new String[]{"gateway", "bridge", "signing", "wallet"}) {
            auditLog.clear();
            Proposal proposal = buildUpgradeProposal(target, "1.0.0");
            SoftwareUpgradeExecutor.ExecutionResult result = executor.execute(proposal);
            assertTrue(result.isSuccess(), "Target should succeed: " + target);
            assertEquals(ProposalState.EXECUTED, proposal.getState());
        }
    }
}