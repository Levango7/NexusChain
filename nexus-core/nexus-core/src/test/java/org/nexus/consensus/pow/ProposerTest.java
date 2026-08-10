package org.nexus.consensus.pow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Proposer} 单元测试。
 */
class ProposerTest {

    @Test
    void constructorSetsFields() {
        Proposer p = new Proposer("abc123", 100L, 200L);
        assertEquals("abc123", p.pubkeyHash);
        assertEquals(100L, p.startTimeStamp);
        assertEquals(200L, p.endTimeStamp);
    }

    @Test
    void fieldsArePublicMutable() {
        Proposer p = new Proposer("x", 0, 1);
        p.pubkeyHash = "y";
        p.startTimeStamp = 10;
        p.endTimeStamp = 20;
        assertEquals("y", p.pubkeyHash);
        assertEquals(10, p.startTimeStamp);
        assertEquals(20, p.endTimeStamp);
    }

    @Test
    void nullPubkeyHashAllowed() {
        Proposer p = new Proposer(null, 0, 0);
        assertNull(p.pubkeyHash);
    }
}