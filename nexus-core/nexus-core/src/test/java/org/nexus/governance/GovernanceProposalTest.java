package org.nexus.governance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GovernanceProposal} 单元测试。
 */
class GovernanceProposalTest {

    @Test
    void defaultConstructorCreatesEmpty() {
        GovernanceProposal p = new GovernanceProposal();
        assertNull(p.getProposalId());
        assertNull(p.getType());
        assertNull(p.getStatus());
    }

    @Test
    void settersAndGetters() {
        GovernanceProposal p = new GovernanceProposal();
        p.setProposalId("p-1");
        p.setType(ProposalType.PARAMETER_CHANGE);
        p.setStatus(ProposalStatus.VOTING);
        p.setProposer("addr-1");
        p.setDepositAmount(new BigDecimal("100"));
        p.setParameterChanges(Collections.singletonList(new ParameterChange("x", "1", "2", 100)));
        Instant now = Instant.now();
        p.setVotingStart(now);
        p.setVotingEnd(now.plusSeconds(60));
        p.setExecutionStart(now.plusSeconds(120));
        p.setExecutionEnd(now.plusSeconds(180));

        assertEquals("p-1", p.getProposalId());
        assertEquals(ProposalType.PARAMETER_CHANGE, p.getType());
        assertEquals(ProposalStatus.VOTING, p.getStatus());
        assertEquals("addr-1", p.getProposer());
        assertEquals(new BigDecimal("100"), p.getDepositAmount());
        assertEquals(1, p.getParameterChanges().size());
        assertEquals(now, p.getVotingStart());
        assertEquals(now.plusSeconds(60), p.getVotingEnd());
        assertEquals(now.plusSeconds(120), p.getExecutionStart());
        assertEquals(now.plusSeconds(180), p.getExecutionEnd());
    }

    @Test
    void depositAmountCanBeNull() {
        GovernanceProposal p = new GovernanceProposal();
        p.setDepositAmount(null);
        assertNull(p.getDepositAmount());
    }

    @Test
    void allProposalTypesAssignable() {
        GovernanceProposal p = new GovernanceProposal();
        for (ProposalType t : ProposalType.values()) {
            p.setType(t);
            assertEquals(t, p.getType());
        }
    }

    @Test
    void allProposalStatusesAssignable() {
        GovernanceProposal p = new GovernanceProposal();
        for (ProposalStatus s : ProposalStatus.values()) {
            p.setStatus(s);
            assertEquals(s, p.getStatus());
        }
    }
}