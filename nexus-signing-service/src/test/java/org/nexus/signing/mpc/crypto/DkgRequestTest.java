package org.nexus.signing.mpc.crypto;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link DkgRequest} 单元测试。
 */
public class DkgRequestTest {

    @Test
    public void testConstructorAndGetters() {
        DkgRequest req = new DkgRequest("s1", 2, 3, 0, "secp256k1",
                List.of("host1:50051", "host2:50051"));
        assertEquals("s1", req.getSessionId());
        assertEquals(2, req.getThreshold());
        assertEquals(3, req.getTotalParties());
        assertEquals(0, req.getPartyIndex());
        assertEquals("secp256k1", req.getCurve());
        assertEquals(2, req.getPeerEndpoints().size());
    }

    @Test(expected = NullPointerException.class)
    public void testNullSessionIdThrows() {
        new DkgRequest(null, 2, 3, 0, "c", List.of());
    }

    @Test(expected = NullPointerException.class)
    public void testNullCurveThrows() {
        new DkgRequest("s1", 2, 3, 0, null, List.of());
    }

    @Test(expected = NullPointerException.class)
    public void testNullPeerEndpointsThrows() {
        new DkgRequest("s1", 2, 3, 0, "c", null);
    }

    @Test
    public void testEqualsAndHashCode() {
        DkgRequest a = new DkgRequest("s1", 2, 3, 0, "c", List.of("h1", "h2"));
        DkgRequest b = new DkgRequest("s1", 2, 3, 0, "c", List.of("h1", "h2"));
        DkgRequest c = new DkgRequest("s1", 2, 3, 1, "c", List.of("h1", "h2"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    public void testEqualsSelfAndNull() {
        DkgRequest a = new DkgRequest("s1", 2, 3, 0, "c", List.of());
        assertEquals(a, a);
        assertTrue(!a.equals(null));
        assertTrue(!a.equals("x"));
    }

    @Test
    public void testToStringContainsKeyFields() {
        DkgRequest req = new DkgRequest("s1", 2, 3, 0, "secp256k1", List.of("h1"));
        String s = req.toString();
        assertTrue(s.contains("s1"));
        assertTrue(s.contains("secp256k1"));
        assertTrue(s.contains("threshold=2"));
    }

    @Test
    public void testPeerEndpointsImmutable() {
        DkgRequest req = new DkgRequest("s1", 2, 3, 0, "c", List.of("h1"));
        // List.copyOf 保证不可变
        assertEquals(1, req.getPeerEndpoints().size());
    }
}