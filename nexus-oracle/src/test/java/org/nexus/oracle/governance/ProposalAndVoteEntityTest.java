package org.nexus.oracle.governance;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Proposal} 与 {@link Vote} Lombok 实体单元测试。
 * <p>覆盖 builder、getter/setter、equals/hashCode/toString 与枚举。</p>
 */
class ProposalAndVoteEntityTest {

    // ---------- Proposal ----------

    @Test
    void proposal_builder_shouldSetAllFields() {
        Instant now = Instant.now();
        Proposal p = Proposal.builder()
                .proposalId("P1")
                .title("title")
                .description("desc")
                .type(Proposal.Type.PARAMETER_CHANGE)
                .state(ProposalState.ACTIVE)
                .votingStart(now)
                .votingPeriod(Duration.ofDays(7))
                .executionDelay(Duration.ofDays(1))
                .parameters(Map.of("key", "value"))
                .proposer("proposer-1")
                .build();

        assertEquals("P1", p.getProposalId());
        assertEquals("title", p.getTitle());
        assertEquals("desc", p.getDescription());
        assertEquals(Proposal.Type.PARAMETER_CHANGE, p.getType());
        assertEquals(ProposalState.ACTIVE, p.getState());
        assertEquals(now, p.getVotingStart());
        assertEquals(Duration.ofDays(7), p.getVotingPeriod());
        assertEquals(Duration.ofDays(1), p.getExecutionDelay());
        assertEquals("value", p.getParameters().get("key"));
        assertEquals("proposer-1", p.getProposer());
    }

    @Test
    void proposal_noArgsConstructor_shouldHaveNulls() {
        Proposal p = new Proposal();
        assertNull(p.getProposalId());
        assertNull(p.getTitle());
        assertNull(p.getType());
        assertNull(p.getState());
        assertNull(p.getProposer());
    }

    @Test
    void proposal_setters_shouldRoundTrip() {
        Proposal p = new Proposal();
        p.setProposalId("X");
        p.setTitle("T");
        p.setType(Proposal.Type.SOFTWARE_UPGRADE);

        assertEquals("X", p.getProposalId());
        assertEquals("T", p.getTitle());
        assertEquals(Proposal.Type.SOFTWARE_UPGRADE, p.getType());
    }

    @Test
    void proposal_equalsAndHashCode_shouldWork() {
        Proposal a = Proposal.builder().proposalId("1").title("t").build();
        Proposal b = Proposal.builder().proposalId("1").title("t").build();
        Proposal c = Proposal.builder().proposalId("2").title("t").build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c));
        assertFalse(a.equals(null));
        assertFalse(a.equals("string"));
        assertTrue(a.equals(a));
    }

    @Test
    void proposal_canEqual_shouldDistinguishTypes() {
        Proposal a = new Proposal();
        assertFalse(a.canEqual("string"));
        assertTrue(a.canEqual(new Proposal()));
    }

    @Test
    void proposal_toString_shouldContainFields() {
        Proposal p = Proposal.builder().proposalId("P1").title("title").build();
        String s = p.toString();
        assertNotNull(s);
        assertTrue(s.contains("P1"));
        assertTrue(s.contains("title"));
    }

    @Test
    void proposal_typeEnum_shouldContainAllVariants() {
        assertEquals(3, Proposal.Type.values().length);
        assertEquals(Proposal.Type.PARAMETER_CHANGE, Proposal.Type.valueOf("PARAMETER_CHANGE"));
        assertEquals(Proposal.Type.SOFTWARE_UPGRADE, Proposal.Type.valueOf("SOFTWARE_UPGRADE"));
        assertEquals(Proposal.Type.TREASURY_SPEND, Proposal.Type.valueOf("TREASURY_SPEND"));
    }

    @Test
    void proposalStateEnum_shouldContainAllVariants() {
        // P5-T7: 新增 EXECUTION_FAILED 状态，共 7 个变体
        assertEquals(7, ProposalState.values().length);
        for (ProposalState s : ProposalState.values()) {
            assertEquals(s, ProposalState.valueOf(s.name()));
        }
        // 显式验证新增状态
        assertEquals(ProposalState.EXECUTION_FAILED, ProposalState.valueOf("EXECUTION_FAILED"));
    }

    // ---------- Vote ----------

    @Test
    void vote_builder_shouldSetAllFields() {
        Instant now = Instant.now();
        Vote v = Vote.builder()
                .proposalId("P1")
                .voter("voter-1")
                .option(Vote.Option.YES)
                .weight(BigInteger.valueOf(100))
                .timestamp(now)
                .signature("sig")
                .build();

        assertEquals("P1", v.getProposalId());
        assertEquals("voter-1", v.getVoter());
        assertEquals(Vote.Option.YES, v.getOption());
        assertEquals(BigInteger.valueOf(100), v.getWeight());
        assertEquals(now, v.getTimestamp());
        assertEquals("sig", v.getSignature());
    }

    @Test
    void vote_noArgsConstructor_shouldHaveNulls() {
        Vote v = new Vote();
        assertNull(v.getProposalId());
        assertNull(v.getVoter());
        assertNull(v.getOption());
        assertNull(v.getWeight());
        assertNull(v.getTimestamp());
        assertNull(v.getSignature());
    }

    @Test
    void vote_setters_shouldRoundTrip() {
        Vote v = new Vote();
        v.setProposalId("P2");
        v.setVoter("voter-2");
        v.setOption(Vote.Option.NO);
        v.setWeight(BigInteger.TEN);

        assertEquals("P2", v.getProposalId());
        assertEquals("voter-2", v.getVoter());
        assertEquals(Vote.Option.NO, v.getOption());
        assertEquals(BigInteger.TEN, v.getWeight());
    }

    @Test
    void vote_equalsAndHashCode_shouldWork() {
        Vote a = Vote.builder().voter("a").option(Vote.Option.YES).build();
        Vote b = Vote.builder().voter("a").option(Vote.Option.YES).build();
        Vote c = Vote.builder().voter("b").option(Vote.Option.YES).build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c));
        assertFalse(a.equals(null));
        assertFalse(a.equals("string"));
        assertTrue(a.equals(a));
    }

    @Test
    void vote_canEqual_shouldDistinguishTypes() {
        Vote v = new Vote();
        assertFalse(v.canEqual("string"));
        assertTrue(v.canEqual(new Vote()));
    }

    @Test
    void vote_toString_shouldContainFields() {
        Vote v = Vote.builder().voter("v1").option(Vote.Option.YES).build();
        String s = v.toString();
        assertNotNull(s);
        assertTrue(s.contains("v1"));
        assertTrue(s.contains("YES"));
    }

    @Test
    void vote_optionEnum_shouldContainAllVariants() {
        assertEquals(3, Vote.Option.values().length);
        assertEquals(Vote.Option.YES, Vote.Option.valueOf("YES"));
        assertEquals(Vote.Option.NO, Vote.Option.valueOf("NO"));
        assertEquals(Vote.Option.ABSTAIN, Vote.Option.valueOf("ABSTAIN"));
    }
}