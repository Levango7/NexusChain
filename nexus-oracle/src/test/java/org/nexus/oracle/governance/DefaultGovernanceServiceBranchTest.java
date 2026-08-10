package org.nexus.oracle.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultGovernanceService} 补充分支测试：覆盖 createProposal 各非法入参、
 * vote 多分支、executeProposal 各类型分发与失败路径、getProposal / getProposalState
 * null 路径、注入 null registry 的兜底。
 */
class DefaultGovernanceServiceBranchTest {

    private DefaultGovernanceService governance;

    @BeforeEach
    void setUp() {
        governance = new DefaultGovernanceService();
    }

    // ---------- createProposal 非法入参 ----------

    @Test
    void createProposal_null_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> governance.createProposal(null));
    }

    @Test
    void createProposal_blankTitle_shouldThrow() {
        Proposal p = Proposal.builder()
                .title("  ")
                .type(Proposal.Type.PARAMETER_CHANGE)
                .proposer("p")
                .build();
        assertThrows(IllegalArgumentException.class, () -> governance.createProposal(p));
    }

    @Test
    void createProposal_nullType_shouldThrow() {
        Proposal p = Proposal.builder()
                .title("t")
                .proposer("p")
                .build();
        assertThrows(IllegalArgumentException.class, () -> governance.createProposal(p));
    }

    @Test
    void createProposal_blankProposer_shouldThrow() {
        Proposal p = Proposal.builder()
                .title("t")
                .type(Proposal.Type.PARAMETER_CHANGE)
                .proposer("  ")
                .build();
        assertThrows(IllegalArgumentException.class, () -> governance.createProposal(p));
    }

    @Test
    void createProposal_nullProposer_shouldThrow() {
        Proposal p = Proposal.builder()
                .title("t")
                .type(Proposal.Type.PARAMETER_CHANGE)
                .build();
        assertThrows(IllegalArgumentException.class, () -> governance.createProposal(p));
    }

    @Test
    void createProposal_nullVotingPeriodAndDelay_shouldDefault() {
        Proposal p = Proposal.builder()
                .title("t")
                .type(Proposal.Type.PARAMETER_CHANGE)
                .proposer("p")
                .build();
        // votingPeriod / executionDelay 均为 null → 应默认填充
        Proposal created = governance.createProposal(p);
        assertNotNull(created.getVotingPeriod());
        assertNotNull(created.getExecutionDelay());
    }

    // ---------- vote 多分支 ----------

    @Test
    void vote_nullProposalId_shouldReturnFalse() {
        assertFalse(governance.vote(null, Vote.builder().voter("v").build()));
    }

    @Test
    void vote_nullVote_shouldReturnFalse() {
        assertFalse(governance.vote("P1", null));
    }

    @Test
    void vote_nullVoter_shouldReturnFalse() {
        assertFalse(governance.vote("P1", Vote.builder().build()));
    }

    @Test
    void vote_unknownProposal_shouldReturnFalse() {
        assertFalse(governance.vote("NOPE", Vote.builder().voter("v").build()));
    }

    @Test
    void vote_nullWeight_shouldDefaultToOne() {
        Proposal p = createActiveProposal();
        Vote v = Vote.builder().voter("v1").option(Vote.Option.YES).build();
        // weight 为 null → 默认 1
        assertTrue(governance.vote(p.getProposalId(), v));
        assertEquals(BigInteger.ONE, governance.getTally(p.getProposalId()).get(Vote.Option.YES));
    }

    @Test
    void vote_nullOption_shouldDefaultToAbstain() {
        Proposal p = createActiveProposal();
        Vote v = Vote.builder().voter("v1").build();
        assertTrue(governance.vote(p.getProposalId(), v));
        assertEquals(BigInteger.ONE, governance.getTally(p.getProposalId()).get(Vote.Option.ABSTAIN));
    }

    // ---------- executeProposal 多分支 ----------

    @Test
    void executeProposal_nullId_shouldReturnFalse() {
        assertFalse(governance.executeProposal(null));
    }

    @Test
    void executeProposal_unknownId_shouldReturnFalse() {
        assertFalse(governance.executeProposal("NOPE"));
    }

    @Test
    void executeProposal_softwareUpgrade_shouldExecute() throws Exception {
        Proposal created = createVoteAndClose(Proposal.Type.SOFTWARE_UPGRADE, Vote.Option.YES);
        assertTrue(governance.executeProposal(created.getProposalId()));
        assertEquals(ProposalState.EXECUTED, governance.getProposalState(created.getProposalId()));
    }

    @Test
    void executeProposal_treasurySpend_shouldExecute() throws Exception {
        Proposal created = createVoteAndClose(Proposal.Type.TREASURY_SPEND, Vote.Option.YES);
        assertTrue(governance.executeProposal(created.getProposalId()));
        assertEquals(ProposalState.EXECUTED, governance.getProposalState(created.getProposalId()));
    }

    @Test
    void executeProposal_parameterChangeWithParams_shouldApply() throws Exception {
        // 使用注入的 registry 构造 governance
        DefaultGovernableParameterRegistry registry = new DefaultGovernableParameterRegistry();
        DefaultGovernanceService g = new DefaultGovernanceService(registry);

        Proposal p = Proposal.builder()
                .title("param-change")
                .type(Proposal.Type.PARAMETER_CHANGE)
                .proposer("p")
                .votingPeriod(Duration.ofMillis(50))
                .executionDelay(Duration.ZERO)
                .parameters(Map.of("feeRate", "0.05"))
                .build();
        g.createProposal(p);
        g.vote(p.getProposalId(), Vote.builder().voter("v").option(Vote.Option.YES).weight(BigInteger.TEN).build());
        Thread.sleep(120);

        assertTrue(g.executeProposal(p.getProposalId()));
        assertEquals("0.05", registry.getParameter("feeRate"));
    }

    @Test
    void executeProposal_parameterChangeInvalidValue_shouldRollbackAndFail() throws Exception {
        // 自定义 registry：validate 拒绝特定值
        GovernableParameterRegistry rejecting = new GovernableParameterRegistry() {
            @Override
            public boolean validate(String paramName, String value) {
                return value != null && !value.equals("BAD");
            }

            @Override
            public boolean setParameter(String paramName, String value) {
                return validate(paramName, value);
            }

            @Override
            public Object getParameter(String paramName) {
                return null;
            }

            @Override
            public java.util.Map<String, Object> snapshot() {
                return new java.util.HashMap<>();
            }

            @Override
            public void restore(java.util.Map<String, Object> snapshot) {
            }
        };
        DefaultGovernanceService g = new DefaultGovernanceService(rejecting);

        Proposal p = Proposal.builder()
                .title("bad-param")
                .type(Proposal.Type.PARAMETER_CHANGE)
                .proposer("p")
                .votingPeriod(Duration.ofMillis(50))
                .executionDelay(Duration.ZERO)
                .parameters(Map.of("k", "BAD"))
                .build();
        g.createProposal(p);
        g.vote(p.getProposalId(), Vote.builder().voter("v").option(Vote.Option.YES).weight(BigInteger.TEN).build());
        Thread.sleep(120);

        assertFalse(g.executeProposal(p.getProposalId()));
    }

    @Test
    void executeProposal_executionDelayNotElapsed_shouldReject() throws Exception {
        // executionDelay 设为 1 小时 → 立即执行应被拒绝
        Proposal p = Proposal.builder()
                .title("delayed")
                .type(Proposal.Type.SOFTWARE_UPGRADE)
                .proposer("p")
                .votingPeriod(Duration.ofMillis(50))
                .executionDelay(Duration.ofHours(1))
                .build();
        governance.createProposal(p);
        governance.vote(p.getProposalId(),
                Vote.builder().voter("v").option(Vote.Option.YES).weight(BigInteger.TEN).build());
        Thread.sleep(120);

        // PASSED 但执行延迟未到
        assertEquals(ProposalState.PASSED, governance.getProposalState(p.getProposalId()));
        assertFalse(governance.executeProposal(p.getProposalId()));
    }

    // ---------- getProposal / getProposalState ----------

    @Test
    void getProposal_unknownId_shouldReturnNull() {
        assertNull(governance.getProposal("NOPE"));
    }

    @Test
    void getProposalState_unknownId_shouldReturnNull() {
        assertNull(governance.getProposalState("NOPE"));
    }

    @Test
    void getTally_unknownId_shouldReturnEmpty() {
        assertTrue(governance.getTally("NOPE").isEmpty());
    }

    @Test
    void getProposal_existing_shouldAdvanceStateAndReturn() throws Exception {
        Proposal created = createVoteAndClose(Proposal.Type.PARAMETER_CHANGE, Vote.Option.YES);
        Proposal fetched = governance.getProposal(created.getProposalId());
        assertNotNull(fetched);
        assertEquals(ProposalState.PASSED, fetched.getState());
    }

    // ---------- 构造器 ----------

    @Test
    void constructor_nullRegistry_shouldFallbackToDefault() {
        DefaultGovernanceService g = new DefaultGovernanceService((GovernableParameterRegistry) null);
        // 创建并执行一个空参数的 PARAMETER_CHANGE 提案应成功
        Proposal p = Proposal.builder()
                .title("t")
                .type(Proposal.Type.PARAMETER_CHANGE)
                .proposer("p")
                .votingPeriod(Duration.ofMillis(50))
                .executionDelay(Duration.ZERO)
                .build();
        g.createProposal(p);
        g.vote(p.getProposalId(),
                Vote.builder().voter("v").option(Vote.Option.YES).weight(BigInteger.TEN).build());
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(g.executeProposal(p.getProposalId()));
    }

    // ---------- helpers ----------

    private Proposal createActiveProposal() {
        Proposal p = Proposal.builder()
                .title("t")
                .type(Proposal.Type.PARAMETER_CHANGE)
                .proposer("p")
                .votingPeriod(Duration.ofDays(7))
                .votingStart(Instant.now())
                .build();
        return governance.createProposal(p);
    }

    private Proposal createVoteAndClose(Proposal.Type type, Vote.Option option) throws InterruptedException {
        Proposal p = Proposal.builder()
                .title("t")
                .type(type)
                .proposer("p")
                .votingPeriod(Duration.ofMillis(50))
                .executionDelay(Duration.ZERO)
                .build();
        governance.createProposal(p);
        governance.vote(p.getProposalId(),
                Vote.builder().voter("v1").option(option).weight(BigInteger.TEN).build());
        Thread.sleep(120);
        return p;
    }
}