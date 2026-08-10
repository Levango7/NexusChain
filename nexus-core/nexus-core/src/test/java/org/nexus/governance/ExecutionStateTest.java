package org.nexus.governance;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ExecutionState} 单元测试。
 */
class ExecutionStateTest {

    @Test
    void gettersReturnValues() {
        Instant now = Instant.now();
        ExecutionState s = new ExecutionState("tx-123", now);
        assertEquals("tx-123", s.getTxId());
        assertEquals(now, s.getScheduledAt());
    }

    @Test
    void nullFieldsAllowed() {
        ExecutionState s = new ExecutionState(null, null);
        assertNull(s.getTxId());
        assertNull(s.getScheduledAt());
    }
}