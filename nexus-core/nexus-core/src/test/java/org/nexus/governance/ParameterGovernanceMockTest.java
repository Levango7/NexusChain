package org.nexus.governance;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 治理参数化 Mock 测试（原 ParameterGovernanceE2ETest 重命名）。
 *
 * <p><b>命名诚实性说明</b>：本测试使用 {@code @MockBean} 替换
 * {@link GovernanceService}，仅验证 {@link GovernanceProposal}
 * 模型对象的字段设置与状态枚举，不涉及真实治理服务调用或链上提案流程，
 * 因此从 "E2E" 重命名为 "Mock" 以准确反映测试性质。</p>
 *
 * <p><b>数据源策略（REQ-TEST-01）</b>：检测到 Docker 时通过 Testcontainers 启动
 * 一次性 PostgreSQL 容器并覆盖数据源配置，使测试不再依赖本机
 * {@code nexus_test} 库的角色/密码漂移；无 Docker 时回落到
 * {@code application-test.yml} 的本地库默认值。</p>
 *
 * <p>真实治理端到端验证见 {@link OnChainGovernanceIntegrationTest}
 * 与 {@link GovernanceExecutorOnChainIntegrationTest}（需 Hardhat 节点）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ParameterGovernanceMockTest {

    /** 一次性 PG 容器（单例静态启动，整个测试类共享）。 */
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("nexus_test")
                    .withUsername("nexus_test")
                    .withPassword("nexus_test_pw");

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            Startables.deepStart(POSTGRES).join();
        }
    }

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        if (POSTGRES.isRunning()) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        }
    }

    @MockBean private GovernanceService governanceService;

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