package org.nexus.governance.recall;

import org.junit.jupiter.api.Test;
import org.nexus.governance.recall.RecallProposal.Status;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RecallProposal} 单元测试。
 */
class RecallProposalTest {

    @Test
    void newProposalStartsInSubmitted() {
        RecallEvidence e = new RecallEvidence("e1",
                RecallEvidence.Type.INACTIVITY, "d", null, "s", Instant.now());
        RecallProposal p = new RecallProposal("rp1", "guardian",
                Arrays.asList(e), "gov-1", "proposer", Instant.now());
        assertEquals("rp1", p.getRecallProposalId());
        assertEquals("guardian", p.getTargetGuardian());
        assertEquals(1, p.getEvidences().size());
        assertEquals("gov-1", p.getGovernanceProposalId());
        assertEquals("proposer", p.getProposer());
        assertEquals(Status.SUBMITTED, p.getStatus());
        assertNull(p.getResolution());
    }

    @Test
    void nullEvidencesBecomesEmpty() {
        RecallProposal p = new RecallProposal("rp", "g", null, "gov", "prop", Instant.now());
        assertNotNull(p.getEvidences());
        assertTrue(p.getEvidences().isEmpty());
    }

    @Test
    void evidencesIsUnmodifiable() {
        RecallEvidence e = new RecallEvidence("e1",
                RecallEvidence.Type.OTHER, "d", null, "s", Instant.now());
        RecallProposal p = new RecallProposal("rp", "g", Arrays.asList(e), "gov", "prop", Instant.now());
        assertThrows(UnsupportedOperationException.class, () ->
                p.getEvidences().add(e));
    }

    @Test
    void statusTransitions() {
        RecallProposal p = new RecallProposal("rp", "g", null, "gov", "prop", Instant.now());
        p.setStatus(Status.PASSED);
        assertEquals(Status.PASSED, p.getStatus());
        p.setStatus(Status.EXECUTED);
        assertEquals(Status.EXECUTED, p.getStatus());
    }

    @Test
    void resolutionCanBeSet() {
        RecallProposal p = new RecallProposal("rp", "g", null, "gov", "prop", Instant.now());
        p.setResolution("guardian removed");
        assertEquals("guardian removed", p.getResolution());
    }

    @Test
    void toStringContainsKeyFields() {
        RecallProposal p = new RecallProposal("rp1", "g",
                Collections.emptyList(), "gov1", "prop", Instant.now());
        String s = p.toString();
        assertTrue(s.contains("RecallProposal"));
        assertTrue(s.contains("rp1"));
        assertTrue(s.contains("gov1"));
        assertTrue(s.contains("SUBMITTED"));
    }
}