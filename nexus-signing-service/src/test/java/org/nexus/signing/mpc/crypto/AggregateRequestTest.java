package org.nexus.signing.mpc.crypto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AggregateRequest} 单元测试。
 */
public class AggregateRequestTest {

    @Test
    public void testConstructorAndGetters() {
        AggregateRequest req = new AggregateRequest("s1", "pk", "mh",
                List.of("sig1", "sig2"));
        assertEquals(req.getSessionId(), "s1");
        assertEquals(req.getPublicKey(), "pk");
        assertEquals(req.getMessageHash(), "mh");
        assertEquals(2, req.getPartialSignatures().size());
    }

    @Test
    public void testNullSessionIdThrows() { assertThrows(NullPointerException.class, () -> {
        new AggregateRequest(null, "pk", "mh", List.of());
        });
    }

    @Test
    public void testNullPublicKeyThrows() { assertThrows(NullPointerException.class, () -> {
        new AggregateRequest("s1", null, "mh", List.of());
        });
    }

    @Test
    public void testNullMessageHashThrows() { assertThrows(NullPointerException.class, () -> {
        new AggregateRequest("s1", "pk", null, List.of());
        });
    }

    @Test
    public void testNullPartialSignaturesThrows() { assertThrows(NullPointerException.class, () -> {
        new AggregateRequest("s1", "pk", "mh", null);
        });
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