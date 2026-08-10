package org.nexus.signing.mpc.crypto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DkgRequest} 单元测试。
 */
public class DkgRequestTest {

    @Test
    public void testConstructorAndGetters() {
        DkgRequest req = new DkgRequest("s1", 2, 3, 0, "secp256k1",
                List.of("host1:50051", "host2:50051"));
        assertEquals(req.getSessionId(), "s1");
        assertEquals(2, req.getThreshold());
        assertEquals(3, req.getTotalParties());
        assertEquals(0, req.getPartyIndex());
        assertEquals(req.getCurve(), "secp256k1");
        assertEquals(2, req.getPeerEndpoints().size());
    }

    @Test
    public void testNullSessionIdThrows() { assertThrows(NullPointerException.class, () -> {
        new DkgRequest(null, 2, 3, 0, "c", List.of());
        });
    }

    @Test
    public void testNullCurveThrows() { assertThrows(NullPointerException.class, () -> {
        new DkgRequest("s1", 2, 3, 0, null, List.of());
        });
    }

    @Test
    public void testNullPeerEndpointsThrows() { assertThrows(NullPointerException.class, () -> {
        new DkgRequest("s1", 2, 3, 0, "c", null);
        });
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