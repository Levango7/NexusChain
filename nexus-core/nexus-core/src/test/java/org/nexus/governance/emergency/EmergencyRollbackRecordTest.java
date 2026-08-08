package org.nexus.governance.emergency;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EmergencyRollbackRecord} 单元测试。
 */
class EmergencyRollbackRecordTest {

    @Test
    void gettersReturnValues() {
        List<String> approvals = Arrays.asList("g1", "g2");
        Instant now = Instant.now();
        EmergencyRollbackRecord r = new EmergencyRollbackRecord(
                "rb-1", "trigger", approvals, 5, "bug", now, true);
        assertEquals("rb-1", r.getRollbackId());
        assertEquals("trigger", r.getTriggeredBy());
        assertEquals(2, r.getGuardianApprovals().size());
        assertEquals(5, r.getTargetSnapshotVersion());
        assertEquals("bug", r.getReason());
        assertEquals(now, r.getExecutedAt());
        assertTrue(r.isSuccess());
    }

    @Test
    void nullApprovalsBecomesEmpty() {
        EmergencyRollbackRecord r = new EmergencyRollbackRecord(
                "rb", "t", null, 0, "r", Instant.now(), false);
        assertNotNull(r.getGuardianApprovals());
        assertTrue(r.getGuardianApprovals().isEmpty());
    }

    @Test
    void approvalsIsUnmodifiable() {
        EmergencyRollbackRecord r = new EmergencyRollbackRecord(
                "rb", "t", Arrays.asList("g1"), 0, "r", Instant.now(), true);
        assertThrows(UnsupportedOperationException.class, () ->
                r.getGuardianApprovals().add("g2"));
    }

    @Test
    void toStringContainsKeyFields() {
        EmergencyRollbackRecord r = new EmergencyRollbackRecord(
                "rb1", "trig", Collections.singletonList("g1"), 3, "reason", Instant.now(), true);
        String s = r.toString();
        assertTrue(s.contains("EmergencyRollbackRecord"));
        assertTrue(s.contains("rb1"));
        assertTrue(s.contains("targetVersion=3"));
        assertTrue(s.contains("success=true"));
    }

    @Test
    void failureRecord() {
        EmergencyRollbackRecord r = new EmergencyRollbackRecord(
                "rb", "t", null, 0, "fail", Instant.now(), false);
        assertFalse(r.isSuccess());
    }
}