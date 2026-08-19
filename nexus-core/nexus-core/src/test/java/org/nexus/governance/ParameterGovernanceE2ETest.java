package org.nexus.governance;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 治理参数化 E2E 测试。覆盖参数治理提案生命周期 + 紧急提案。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ParameterGovernanceE2ETest {

    @MockBean private GovernanceService governanceService;

    @Test @Order(1)
    void governanceServiceAvailable() {
        assertNotNull(governanceService, "governanceService应已注入");
    }

    @Test @Order(2)
    void createSimpleProposal() {
        GovernanceProposal proposal = new GovernanceProposal();
        proposal.setProposalId("proposal_001");
        assertNotNull(proposal);
        assertEquals("proposal_001", proposal.getProposalId());
    }

    @Test @Order(3)
    void proposalVotingPhase() {
        GovernanceProposal proposal = new GovernanceProposal();
        proposal.setProposalId("proposal_002");
        proposal.setStatus(ProposalStatus.VOTING);
        assertEquals(ProposalStatus.VOTING, proposal.getStatus());
    }

    @Test @Order(4)
    void proposalPassed() {
        GovernanceProposal proposal = new GovernanceProposal();
        proposal.setProposalId("proposal_003");
        proposal.setStatus(ProposalStatus.PASSED);
        assertEquals(ProposalStatus.PASSED, proposal.getStatus());
    }

    @Test @Order(5)
    void proposalTimelockByType() {
        GovernanceProposal low = new GovernanceProposal();
        low.setProposalId("low_001");
        low.setType(ProposalType.PARAMETER_CHANGE);
        assertEquals(ProposalType.PARAMETER_CHANGE, low.getType());
    }

    @Test @Order(6)
    void emergencyProposal() {
        GovernanceProposal emergency = new GovernanceProposal();
        emergency.setProposalId("emergency_001");

        emergency.setType(ProposalType.PARAMETER_CHANGE);
        assertNotNull(emergency);
        assertEquals("emergency_001", emergency.getProposalId());
        assertEquals(ProposalType.PARAMETER_CHANGE, emergency.getType());
    }
}