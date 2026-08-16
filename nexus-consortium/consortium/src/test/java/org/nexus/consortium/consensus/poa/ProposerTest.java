package org.nexus.consortium.consensus.poa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proposer 单元测试。
 * 覆盖构造器、getter/setter。
 */
public class ProposerTest {

    @Test
    public void testDefaultConstructor() {
        Proposer proposer = new Proposer();
        assertNotNull(proposer);
        assertNull(proposer.getAddress());
        assertEquals(0L, proposer.getStartTimeStamp());
        assertEquals(0L, proposer.getEndTimeStamp());
    }

    @Test
    public void testAllArgsConstructor() {
        Proposer proposer = new Proposer("addr1", 1000L, 2000L);
        assertEquals("addr1", proposer.getAddress());
        assertEquals(1000L, proposer.getStartTimeStamp());
        assertEquals(2000L, proposer.getEndTimeStamp());
    }

    @Test
    public void testSetters() {
        Proposer proposer = new Proposer();
        proposer.setAddress("addr2");
        proposer.setStartTimeStamp(3000L);
        proposer.setEndTimeStamp(4000L);
        assertEquals("addr2", proposer.getAddress());
        assertEquals(3000L, proposer.getStartTimeStamp());
        assertEquals(4000L, proposer.getEndTimeStamp());
    }

    @Test
    public void testNullAddress() {
        Proposer proposer = new Proposer(null, 0L, 0L);
        assertNull(proposer.getAddress());
    }

    @Test
    public void testEmptyAddress() {
        Proposer proposer = new Proposer("", 0L, 0L);
        assertEquals("", proposer.getAddress());
    }

    @Test
    public void testNegativeTimestamps() {
        Proposer proposer = new Proposer("addr", -1L, -2L);
        assertEquals(-1L, proposer.getStartTimeStamp());
        assertEquals(-2L, proposer.getEndTimeStamp());
    }
}