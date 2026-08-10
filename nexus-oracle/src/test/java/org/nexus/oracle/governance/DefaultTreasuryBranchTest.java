package org.nexus.oracle.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultTreasury} 补充分支测试：覆盖 fund / spend 各非法入参与边界、
 * getHistory / getSpend 路径。
 */
class DefaultTreasuryBranchTest {

    private DefaultGovernanceService governance;
    private DefaultTreasury treasury;

    @BeforeEach
    void setUp() {
        governance = new DefaultGovernanceService();
        treasury = new DefaultTreasury(governance);
    }

    @Test
    void fund_nullOrZero_shouldBeNoOp() {
        treasury.fund(null);
        assertEquals(0, BigDecimal.ZERO.compareTo(treasury.balance()));
        treasury.fund(BigDecimal.ZERO);
        assertEquals(0, BigDecimal.ZERO.compareTo(treasury.balance()));
        treasury.fund(new BigDecimal("-100"));
        assertEquals(0, BigDecimal.ZERO.compareTo(treasury.balance()));
    }

    @Test
    void fund_positive_shouldSetBalance() {
        treasury.fund(new BigDecimal("500"));
        assertEquals(0, new BigDecimal("500").compareTo(treasury.balance()));
    }

    @Test
    void spend_nullAmount_shouldReturnFalse() {
        assertFalse(treasury.spend(null, "to", "p"));
    }

    @Test
    void spend_zeroOrNegativeAmount_shouldReturnFalse() {
        assertFalse(treasury.spend(BigDecimal.ZERO, "to", "p"));
        assertFalse(treasury.spend(new BigDecimal("-1"), "to", "p"));
    }

    @Test
    void spend_blankTo_shouldReturnFalse() {
        assertFalse(treasury.spend(BigDecimal.TEN, "", "p"));
        assertFalse(treasury.spend(BigDecimal.TEN, "   ", "p"));
        assertFalse(treasury.spend(BigDecimal.TEN, null, "p"));
    }

    @Test
    void spend_blankProposalId_shouldReturnFalse() {
        assertFalse(treasury.spend(BigDecimal.TEN, "to", ""));
        assertFalse(treasury.spend(BigDecimal.TEN, "to", null));
    }

    @Test
    void spend_unknownProposal_shouldReturnFalse() {
        treasury.fund(new BigDecimal("1000"));
        assertFalse(treasury.spend(BigDecimal.TEN, "to", "NOPE"));
    }

    @Test
    void spend_proposalNotYetPassed_shouldReturnFalse() throws Exception {
        treasury.fund(new BigDecimal("1000"));
        // 创建但未投票通过（直接 ACTIVE）
        Proposal p = Proposal.builder()
                .title("t")
                .type(Proposal.Type.TREASURY_SPEND)
                .proposer("p")
                .votingPeriod(Duration.ofDays(7))
                .votingStart(Instant.now())
                .build();
        governance.createProposal(p);

        assertFalse(treasury.spend(BigDecimal.TEN, "to", p.getProposalId()));
    }

    @Test
    void spend_valid_shouldRecordHistory() throws Exception {
        treasury.fund(new BigDecimal("1000"));
        Proposal p = createPassedTreasuryProposal();

        assertTrue(treasury.spend(new BigDecimal("100"), "recipient", p.getProposalId()));

        List<Map<String, Object>> history = treasury.getHistory();
        assertEquals(1, history.size());
        Map<String, Object> record = history.get(0);
        assertNotNull(record.get("spendId"));
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) record.get("amount")));
        assertEquals("recipient", record.get("to"));
        assertEquals(p.getProposalId(), record.get("proposalId"));

        // getSpend
        String spendId = (String) record.get("spendId");
        Map<String, Object> fetched = treasury.getSpend(spendId);
        assertNotNull(fetched);
        assertEquals(spendId, fetched.get("spendId"));
    }

    @Test
    void getSpend_nullId_shouldReturnNull() {
        assertNull(treasury.getSpend(null));
    }

    @Test
    void getSpend_unknownId_shouldReturnNull() {
        assertNull(treasury.getSpend("NOPE"));
    }

    @Test
    void getHistory_emptyInitially_shouldBeEmpty() {
        assertTrue(treasury.getHistory().isEmpty());
    }

    @Test
    void spend_executedProposal_shouldAlsoSucceed() throws Exception {
        // 先通过 + 执行提案，再触发 spend（EXECUTED 状态也应允许 spend）
        treasury.fund(new BigDecimal("1000"));
        Proposal p = Proposal.builder()
                .title("t")
                .type(Proposal.Type.TREASURY_SPEND)
                .proposer("p")
                .votingPeriod(Duration.ofMillis(50))
                .executionDelay(Duration.ZERO)
                .build();
        governance.createProposal(p);
        governance.vote(p.getProposalId(),
                Vote.builder().voter("v").option(Vote.Option.YES).weight(BigInteger.TEN).build());
        Thread.sleep(120);
        governance.executeProposal(p.getProposalId());

        assertTrue(treasury.spend(new BigDecimal("100"), "to", p.getProposalId()));
    }

    private Proposal createPassedTreasuryProposal() throws InterruptedException {
        Proposal p = Proposal.builder()
                .title("t")
                .type(Proposal.Type.TREASURY_SPEND)
                .proposer("p")
                .votingPeriod(Duration.ofMillis(50))
                .executionDelay(Duration.ZERO)
                .build();
        governance.createProposal(p);
        governance.vote(p.getProposalId(),
                Vote.builder().voter("v").option(Vote.Option.YES).weight(BigInteger.TEN).build());
        Thread.sleep(120);
        governance.getProposalState(p.getProposalId());
        return p;
    }
}