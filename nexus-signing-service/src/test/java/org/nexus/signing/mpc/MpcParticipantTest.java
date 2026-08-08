package org.nexus.signing.mpc;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link MpcParticipant} 单元测试。
 */
public class MpcParticipantTest {

    @Test
    public void testFullConstructorAndGetters() {
        MpcParticipant p = new MpcParticipant("p1", "host:50051", "pkshare", true);
        assertEquals("p1", p.getParticipantId());
        assertEquals("host:50051", p.getEndpoint());
        assertEquals("pkshare", p.getPublicKeyShareHex());
        assertTrue(p.isOnline());
    }

    @Test
    public void testShortConstructorDefaultsOnline() {
        MpcParticipant p = new MpcParticipant("p1", "host:50051", "pkshare");
        assertTrue("short constructor should default online=true", p.isOnline());
    }

    @Test
    public void testWithOnlineCopy() {
        MpcParticipant online = new MpcParticipant("p1", "h", "pk");
        MpcParticipant offline = online.withOnline(false);
        assertTrue(online.isOnline());
        assertFalse(offline.isOnline());
        assertEquals(online.getParticipantId(), offline.getParticipantId());
        assertEquals(online.getEndpoint(), offline.getEndpoint());
        assertEquals(online.getPublicKeyShareHex(), offline.getPublicKeyShareHex());
    }

    @Test(expected = NullPointerException.class)
    public void testNullParticipantIdThrows() {
        new MpcParticipant(null, "h", "pk");
    }

    @Test(expected = NullPointerException.class)
    public void testNullEndpointThrows() {
        new MpcParticipant("p1", null, "pk");
    }

    @Test(expected = NullPointerException.class)
    public void testNullPublicKeyShareThrows() {
        new MpcParticipant("p1", "h", null);
    }

    @Test
    public void testEqualsByIdOnly() {
        MpcParticipant a = new MpcParticipant("p1", "h1", "pk1");
        MpcParticipant b = new MpcParticipant("p1", "h2", "pk2", false);
        // equals 仅依赖 participantId
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testNotEqualsDifferentId() {
        MpcParticipant a = new MpcParticipant("p1", "h", "pk");
        MpcParticipant b = new MpcParticipant("p2", "h", "pk");
        assertNotEquals(a, b);
    }

    @Test
    public void testInHashSetById() {
        Set<MpcParticipant> set = new HashSet<>();
        set.add(new MpcParticipant("p1", "h1", "pk1"));
        set.add(new MpcParticipant("p1", "h2", "pk2")); // 同 ID，应去重
        set.add(new MpcParticipant("p2", "h3", "pk3"));
        assertEquals(2, set.size());
    }

    @Test
    public void testToStringContainsIdAndEndpoint() {
        MpcParticipant p = new MpcParticipant("p1", "host:50051", "pk", true);
        String s = p.toString();
        assertTrue(s.contains("p1"));
        assertTrue(s.contains("host:50051"));
        assertTrue(s.contains("online=true"));
    }

    @Test
    public void testEqualsSelfAndNull() {
        MpcParticipant p = new MpcParticipant("p1", "h", "pk");
        assertEquals(p, p);
        assertFalse(p.equals(null));
        assertFalse(p.equals("string"));
    }
}