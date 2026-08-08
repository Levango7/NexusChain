package org.nexus.signing.mpc;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link ThresholdPolicy} 单元测试。
 */
public class ThresholdPolicyTest {

    @Test
    public void testValidConstructionAndGetters() {
        ThresholdPolicy p = new ThresholdPolicy(3, 5);
        assertEquals(3, p.getThreshold());
        assertEquals(5, p.getTotalParticipants());
        assertEquals(2, p.getMaxOffline());
    }

    @Test
    public void testIsSafeTrueWhenThresholdMajority() {
        // t > n/2 → safe
        assertTrue(new ThresholdPolicy(3, 5).isSafe());
        assertTrue(new ThresholdPolicy(2, 3).isSafe());
        assertTrue(new ThresholdPolicy(1, 1).isSafe());
    }

    @Test
    public void testIsSafeFalseWhenThresholdNotMajority() {
        // t <= n/2 → not safe
        assertFalse(new ThresholdPolicy(2, 4).isSafe());
        assertFalse(new ThresholdPolicy(1, 2).isSafe());
        assertFalse(new ThresholdPolicy(2, 5).isSafe());
    }

    @Test(expected = MpcProtocolException.class)
    public void testInvalidTotalParticipantsZero() {
        new ThresholdPolicy(1, 0);
    }

    @Test(expected = MpcProtocolException.class)
    public void testInvalidTotalParticipantsNegative() {
        new ThresholdPolicy(1, -1);
    }

    @Test(expected = MpcProtocolException.class)
    public void testInvalidThresholdZero() {
        new ThresholdPolicy(0, 5);
    }

    @Test(expected = MpcProtocolException.class)
    public void testInvalidThresholdNegative() {
        new ThresholdPolicy(-1, 5);
    }

    @Test(expected = MpcProtocolException.class)
    public void testInvalidThresholdGreaterThanTotal() {
        new ThresholdPolicy(6, 5);
    }

    @Test
    public void testIsQuorumReached() {
        ThresholdPolicy p = new ThresholdPolicy(2, 3);
        List<MpcParticipant> twoOnline = List.of(
                new MpcParticipant("p1", "h1", "pk1"),
                new MpcParticipant("p2", "h2", "pk2"),
                new MpcParticipant("p3", "h3", "pk3", false));
        assertTrue(p.isQuorumReached(twoOnline));
    }

    @Test
    public void testIsQuorumNotReached() {
        ThresholdPolicy p = new ThresholdPolicy(3, 5);
        List<MpcParticipant> onlyTwoOnline = List.of(
                new MpcParticipant("p1", "h1", "pk1"),
                new MpcParticipant("p2", "h2", "pk2", false),
                new MpcParticipant("p3", "h3", "pk3", false));
        assertFalse(p.isQuorumReached(onlyTwoOnline));
    }

    @Test
    public void testIsQuorumNullListReturnsFalse() {
        ThresholdPolicy p = new ThresholdPolicy(2, 3);
        assertFalse(p.isQuorumReached(null));
    }

    @Test
    public void testIsSufficient() {
        ThresholdPolicy p = new ThresholdPolicy(3, 5);
        assertFalse(p.isSufficient(0));
        assertFalse(p.isSufficient(2));
        assertTrue(p.isSufficient(3));
        assertTrue(p.isSufficient(5));
    }

    @Test
    public void testEqualsAndHashCode() {
        ThresholdPolicy a = new ThresholdPolicy(3, 5);
        ThresholdPolicy b = new ThresholdPolicy(3, 5);
        ThresholdPolicy c = new ThresholdPolicy(2, 5);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    public void testEqualsSelfAndNull() {
        ThresholdPolicy a = new ThresholdPolicy(1, 1);
        assertEquals(a, a);
        assertFalse(a.equals(null));
        assertFalse(a.equals("x"));
    }

    @Test
    public void testToString() {
        ThresholdPolicy p = new ThresholdPolicy(3, 5);
        String s = p.toString();
        assertTrue(s.contains("t=3"));
        assertTrue(s.contains("n=5"));
        assertTrue(s.contains("safe=true"));
    }
}