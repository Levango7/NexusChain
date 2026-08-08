package org.nexus.signing.mpc.crypto;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link AggregateRequest} 单元测试。
 */
public class AggregateRequestTest {

    @Test
    public void testConstructorAndGetters() {
        AggregateRequest req = new AggregateRequest("s1", "pk", "mh",
                List.of("sig1", "sig2"));
        assertEquals("s1", req.getSessionId());
        assertEquals("pk", req.getPublicKey());
        assertEquals("mh", req.getMessageHash());
        assertEquals(2, req.getPartialSignatures().size());
    }

    @Test(expected = NullPointerException.class)
    public void testNullSessionIdThrows() {
        new AggregateRequest(null, "pk", "mh", List.of());
    }

    @Test(expected = NullPointerException.class)
    public void testNullPublicKeyThrows() {
        new AggregateRequest("s1", null, "mh", List.of());
    }

    @Test(expected = NullPointerException.class)
    public void testNullMessageHashThrows() {
        new AggregateRequest("s1", "pk", null, List.of());
    }

    @Test(expected = NullPointerException.class)
    public void testNullPartialSignaturesThrows() {
        new AggregateRequest("s1", "pk", "mh", null);
    }

    @Test
    public void testEqualsAndHashCode() {
        AggregateRequest a = new AggregateRequest("s1", "pk", "mh", List.of("s1", "s2"));
        AggregateRequest b = new AggregateRequest("s1", "pk", "mh", List.of("s1", "s2"));
        AggregateRequest c = new AggregateRequest("s1", "pk", "mh", List.of("s1"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    public void testEqualsSelfAndNull() {
        AggregateRequest a = new AggregateRequest("s1", "pk", "mh", List.of());
        assertEquals(a, a);
        assertTrue(!a.equals(null));
        assertTrue(!a.equals("x"));
    }

    @Test
    public void testToString() {
        AggregateRequest req = new AggregateRequest("s1", "pk", "mh", List.of("s1", "s2"));
        String s = req.toString();
        assertTrue(s.contains("s1"));
        assertTrue(s.contains("partialSignatures.count=2"));
    }
}