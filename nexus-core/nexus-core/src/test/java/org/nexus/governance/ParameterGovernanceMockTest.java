package org.nexus.governance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * <p><b>命名诚实性说明</b>：本测试使用 {@code @MockitoBean} 替换
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
 *
 * <p><b>环境前置条件</b>：本测试依赖数据源（容器化或本地 PostgreSQL）。
 * 通过 {@link EnabledIf} 前置探测：仅当 Docker 可用（Testcontainers 可启动
 * 一次性 PG 容器）或本地 PostgreSQL 端口可达时才启用；否则整类优雅 SKIP，
 * 而不是让 Spring 上下文启动失败导致全部用例 FAILED。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf(value = "postgresEnvironmentAvailable",
        disabledReason = "无Docker且本地PostgreSQL不可达，跳过依赖数据源的治理Mock测试")
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

    /**
     * 判断当前环境是否具备运行本测试的数据源前置条件，供
     * {@code @EnabledIf("postgresEnvironmentAvailable")} 引用。
     *
     * <p>判定顺序：</p>
     * <ol>
     *   <li>Docker 可达（Testcontainers 可启动一次性 PG 容器）→ 启用；</li>
     *   <li>回落探测本地 PG：从环境变量 {@code DATA_SOURCE_URL}
     *       （缺省 {@code jdbc:postgresql://localhost:5432/nexus_test}）
     *       提取 host/port 后做 TCP 连通性探测（800ms 超时），
     *       端口可达 → 启用。</li>
     * </ol>
     *
     * @return true 表示数据源环境可用；任何异常一律视为不可用（返回 false）
     */
    static boolean postgresEnvironmentAvailable() {
        // 1) Docker daemon 探测：异常（daemon 未装/未启动等）视为不可用
        try {
            if (org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) {
                return true;
            }
        } catch (Throwable t) {
            /* Docker daemon 异常视为不可用 */
        }

        // 2) 回落探测本地 PostgreSQL：解析 DATA_SOURCE_URL 提取 host/port
        String dataSourceUrl = System.getenv("DATA_SOURCE_URL");
        if (dataSourceUrl == null || dataSourceUrl.isBlank()) {
            dataSourceUrl = "jdbc:postgresql://localhost:5432/nexus_test";
        }

        try (java.net.Socket socket = new java.net.Socket()) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("jdbc:postgresql://([^:/]+):(\\d+)/")
                    .matcher(dataSourceUrl);
            if (!matcher.find()) {
                return false; // URL 格式不符合预期，视为不可用
            }
            String host = matcher.group(1);
            int port = Integer.parseInt(matcher.group(2));
            socket.connect(new java.net.InetSocketAddress(host, port), 800);
            return true;
        } catch (Throwable t) {
            return false; // 任何连接/解析异常均视为本地 PG 不可达
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

    @MockitoBean private GovernanceService governanceService;

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