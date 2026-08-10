package org.nexus.governance.recall;

import org.junit.jupiter.api.Test;
import org.nexus.governance.recall.RecallEvidence.Type;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RecallEvidence} 单元测试。
 */
class RecallEvidenceTest {

    @Test
    void gettersReturnValues() {
        List<String> ids = Arrays.asList("tx1", "tx2");
        Instant now = Instant.now();
        RecallEvidence e = new RecallEvidence("e1", Type.COLLUSION, "desc", ids, "sub", now);
        assertEquals("e1", e.getEvidenceId());
        assertEquals(Type.COLLUSION, e.getType());
        assertEquals("desc", e.getDescription());
        assertEquals(2, e.getRelatedIds().size());
        assertEquals("sub", e.getSubmittedBy());
        assertEquals(now, e.getSubmittedAt());
    }

    @Test
    void nullRelatedIdsBecomesEmpty() {
        RecallEvidence e = new RecallEvidence("e", Type.OTHER, "d", null, "s", Instant.now());
        assertNotNull(e.getRelatedIds());
        assertTrue(e.getRelatedIds().isEmpty());
    }

    @Test
    void relatedIdsIsUnmodifiable() {
        RecallEvidence e = new RecallEvidence("e", Type.OTHER, "d",
                Arrays.asList("x"), "s", Instant.now());
        assertThrows(UnsupportedOperationException.class, () ->
                e.getRelatedIds().add("y"));
    }

    @Test
    void toStringContainsKeyFields() {
        RecallEvidence e = new RecallEvidence("eid", Type.MALICIOUS_VETO, "d",
                Collections.emptyList(), "sub", Instant.now());
        String s = e.toString();
        assertTrue(s.contains("RecallEvidence"));
        assertTrue(s.contains("eid"));
        assertTrue(s.contains("MALICIOUS_VETO"));
    }

    @Test
    void allTypesAccessible() {
        for (Type t : Type.values()) {
            RecallEvidence e = new RecallEvidence("e", t, "d", null, "s", Instant.now());
            assertEquals(t, e.getType());
        }
    }
}