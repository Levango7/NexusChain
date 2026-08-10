package org.nexus.oracle.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultGovernanceService} 与 {@link DefaultTreasury} 单元测试。
 */
class GovernanceTest {

    private DefaultGovernanceService governance;
    private DefaultTreasury treasury;

    @BeforeEach
    void setUp() {
        governance = new DefaultGovernanceService();
        treasury = new DefaultTreasury(governance);
    }

    private Proposal newProposal(Proposal.Type type, Duration votingPeriod, Instant votingStart) {
        Proposal p = Proposal.builder()
                .title("Test Proposal")
                .description("desc")
                .type(type)
                .proposer("proposer-1")
                .votingPeriod(votingPeriod)
                .executionDelay(Duration.ZERO)
                .votingStart(votingStart)
                .build();
        return governance.createProposal(p);
    }

    @Test
    void createProposal_shouldAssignIdAndActiveState() {
        Proposal created = newProposal(Proposal.Type.PARAMETER_CHANGE, Duration.ofDays(7), Instant.now());

        assertNotNull(created.getProposalId());
        assertTrue(created.getProposalId().startsWith("PROP-"));
        assertEquals(ProposalState.ACTIVE, created.getState());
    }

    @Test
    void createProposal_futureVotingStart_shouldBePending() {
        Proposal created = newProposal(Proposal.Type.PARAMETER_CHANGE,
                Duration.ofDays(7), Instant.now().plusSeconds(3600));

        assertEquals(ProposalState.PENDING, created.getState());
    }

    @Test
    void createProposal_missingTitle_shouldThrow() {
        Proposal p = Proposal.builder().type(Proposal.Type.PARAMETER_CHANGE).proposer("x").build();
        assertThrows(IllegalArgumentException.class, () -> governance.createProposal(p));
    }

    @Test
    void vote_activeProposal_shouldCount() {
        Proposal created = newProposal(Proposal.Type.PARAMETER_CHANGE, Duration.ofDays(7), Instant.now());
        Vote vote = Vote.builder().voter("voter-1").option(Vote.Option.YES)
                .weight(BigInteger.valueOf(100)).build();

        assertTrue(governance.vote(created.getProposalId(), vote));
        assertEquals(BigInteger.valueOf(100),
                governance.getTally(created.getProposalId()).get(Vote.Option.YES));
    }

    @Test
    void vote_duplicateVoter_shouldReject() {
        Proposal created = newProposal(Proposal.Type.PARAMETER_CHANGE, Duration.ofDays(7), Instant.now());
        Vote vote = Vote.builder().voter("voter-1").option(Vote.Option.YES).build();

        assertTrue(governance.vote(created.getProposalId(), vote));
        assertFalse(governance.vote(created.getProposalId(), vote));
    }

    @Test
    void vote_pendingProposal_shouldReject() {
        Proposal created = newProposal(Proposal.Type.PARAMETER_CHANGE,
                Duration.ofDays(7), Instant.now().plusSeconds(3600));
        Vote vote = Vote.builder().voter("voter-1").option(Vote.Option.YES).build();

        assertFalse(governance.vote(created.getProposalId(), vote));
    }

    /**
     * 创建提案 → 窗口内投票 → 等待窗口结束，返回已推进状态的提案。
     * 用于模拟「投票结束并按计票结果定状态」的场景。
     */
    private Proposal createVoteAndClose(Proposal.Type type, Vote.Option option) throws InterruptedException {
        // 投票期 50ms：创建后立即投票（窗口内），随后等待窗口结束
        Proposal created = newProposal(type, Duration.ofMillis(50), Instant.now());
        governance.vote(created.getProposalId(),
                Vote.builder().voter("v1").option(option).weight(BigInteger.TEN).build());
        Thread.sleep(120);
        return created;
    }

    @Test
    void votingClosed_majorityYes_shouldPass() throws Exception {
        Proposal created = createVoteAndClose(Proposal.Type.PARAMETER_CHANGE, Vote.Option.YES);

        // getProposalState 触发惰性推进并计票
        assertEquals(ProposalState.PASSED, governance.getProposalState(created.getProposalId()));
    }

    @Test
    void votingClosed_majorityNo_shouldReject() throws Exception {
        Proposal created = createVoteAndClose(Proposal.Type.PARAMETER_CHANGE, Vote.Option.NO);

        assertEquals(ProposalState.REJECTED, governance.getProposalState(created.getProposalId()));
    }

    @Test
    void executeProposal_passedAndDelayElapsed_shouldExecute() throws Exception {
        Proposal created = createVoteAndClose(Proposal.Type.PARAMETER_CHANGE, Vote.Option.YES);

        assertTrue(governance.executeProposal(created.getProposalId()));
        assertEquals(ProposalState.EXECUTED, governance.getProposalState(created.getProposalId()));
    }

    @Test
    void executeProposal_notPassed_shouldReject() {
        Proposal created = newProposal(Proposal.Type.PARAMETER_CHANGE, Duration.ofDays(7), Instant.now());
        assertFalse(governance.executeProposal(created.getProposalId()));
    }

    // ---------- Treasury ----------

    @Test
    void treasury_spendWithValidProposal_shouldSucceed() throws Exception {
        treasury.fund(new BigDecimal("1000"));
        Proposal spendProposal = createVoteAndClose(Proposal.Type.TREASURY_SPEND, Vote.Option.YES);
        // 推进到 PASSED
        governance.getProposalState(spendProposal.getProposalId());

        assertTrue(treasury.spend(new BigDecimal("300"), "recipient-addr", spendProposal.getProposalId()));
        assertEquals(0, new BigDecimal("700").compareTo(treasury.balance()));
        assertEquals(1, treasury.getHistory().size());
    }

    @Test
    void treasury_spendWrongProposalType_shouldReject() throws Exception {
        treasury.fund(new BigDecimal("1000"));
        Proposal paramProposal = createVoteAndClose(Proposal.Type.PARAMETER_CHANGE, Vote.Option.YES);
        governance.getProposalState(paramProposal.getProposalId());

        assertFalse(treasury.spend(new BigDecimal("300"), "recipient", paramProposal.getProposalId()));
        assertEquals(0, new BigDecimal("1000").compareTo(treasury.balance()));
    }

    @Test
    void treasury_spendInsufficientBalance_shouldReject() throws Exception {
        treasury.fund(new BigDecimal("100"));
        Proposal spendProposal = createVoteAndClose(Proposal.Type.TREASURY_SPEND, Vote.Option.YES);
        governance.getProposalState(spendProposal.getProposalId());

        assertFalse(treasury.spend(new BigDecimal("500"), "recipient", spendProposal.getProposalId()));
    }
}
