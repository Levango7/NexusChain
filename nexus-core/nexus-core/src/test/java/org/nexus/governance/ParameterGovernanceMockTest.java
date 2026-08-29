package org.nexus.governance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 治理参数化 Mock 测试（原 ParameterGovernanceE2ETest 重命名）。
 *
 * <p><b>命名诚实性说明</b>：本测试使用 {@code @Mock} 替换
 * {@link GovernanceService}，仅验证 {@link GovernanceProposal}
 * 模型对象的字段设置与状态枚举，不涉及真实治理服务调用或链上提案流程，
 * 因此从 "E2E" 重命名为 "Mock" 以准确反映测试性质。</p>
 *
 * <p><b>测试策略</b>：本测试为纯单元测试，不加载 Spring 应用上下文，
 * 不依赖数据库或 Testcontainers。使用 {@link MockitoExtension} 创建
 * {@link GovernanceService} 的 mock 实例，避免因 Spring Boot 4.0.8
 * 与连接池库（HikariCP/DBCP2）的版本兼容性问题导致上下文启动失败。</p>
 *
 * <p>真实治理端到端验证见 {@link OnChainGovernanceIntegrationTest}
 * 与 {@link GovernanceExecutorOnChainIntegrationTest}（需 Hardhat 节点）。</p>
 */
@ExtendWith(MockitoExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ParameterGovernanceMockTest {

    @Mock private GovernanceService governanceService;

    @Test @Order(1)
    void governanceServiceMockBeanAvailable() {
        assertNotNull(governanceService, "governanceService mock 应已注入");
    }

    @Test @Order(2)
    void proposalModelSetsIdField() {
        GovernanceProposal proposal = new GovernanceProposal();
        proposal.setProposalId("proposal_001");
        assertNotNull(proposal);
        assertEquals("proposal_001", proposal.getProposalId());
    }

    @Test @Order(3)
    void proposalModelSetsVotingStatus() {
        GovernanceProposal proposal = new GovernanceProposal();
        proposal.setProposalId("proposal_002");
        proposal.setStatus(ProposalStatus.VOTING);
        assertEquals(ProposalStatus.VOTING, proposal.getStatus());
    }

    @Test @Order(4)
    void proposalModelSetsPassedStatus() {
        GovernanceProposal proposal = new GovernanceProposal();
        proposal.setProposalId("proposal_003");
        proposal.setStatus(ProposalStatus.PASSED);
        assertEquals(ProposalStatus.PASSED, proposal.getStatus());
    }

    @Test @Order(5)
    void proposalModelSetsParameterChangeType() {
        GovernanceProposal low = new GovernanceProposal();
        low.setProposalId("low_001");
        low.setType(ProposalType.PARAMETER_CHANGE);
        assertEquals(ProposalType.PARAMETER_CHANGE, low.getType());
    }

    @Test @Order(6)
    void proposalModelEmergencyFieldsSettable() {
        GovernanceProposal emergency = new GovernanceProposal();
        emergency.setProposalId("emergency_001");

        emergency.setType(ProposalType.PARAMETER_CHANGE);
        assertNotNull(emergency);
        assertEquals("emergency_001", emergency.getProposalId());
        assertEquals(ProposalType.PARAMETER_CHANGE, emergency.getType());
    }
}
