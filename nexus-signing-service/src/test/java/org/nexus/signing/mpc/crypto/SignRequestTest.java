package org.nexus.signing.mpc.crypto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SignRequest} 单元测试。
 */
public class SignRequestTest {

    @Test
    public void testConstructorAndGetters() {
        SignRequest req = new SignRequest("s1", "pk", "ks", "mh", 0,
                List.of("h1", "h2"));
        assertEquals(req.getSessionId(), "s1");
        assertEquals(req.getPublicKey(), "pk");
        assertEquals(req.getKeyShare(), "ks");
        assertEquals(req.getMessageHash(), "mh");
        assertEquals(0, req.getPartyIndex());
        assertEquals(2, req.getPeerEndpoints().size());
    }

    @Test
    public void testNullSessionIdThrows() { assertThrows(NullPointerException.class, () -> {
        new SignRequest(null, "pk", "ks", "mh", 0, List.of());
        });
    }

    @Test
    public void testNullPublicKeyThrows() { assertThrows(NullPointerException.class, () -> {
        new SignRequest("s1", null, "ks", "mh", 0, List.of());
        });
    }

    @Test
    public void testNullKeyShareThrows() { assertThrows(NullPointerException.class, () -> {
        new SignRequest("s1", "pk", null, "mh", 0, List.of());
        });
    }

    @Test
    public void testNullMessageHashThrows() { assertThrows(NullPointerException.class, () -> {
        new SignRequest("s1", "pk", "ks", null, 0, List.of());
        });
    }

    @Test
    public void testNullPeerEndpointsThrows() { assertThrows(NullPointerException.class, () -> {
        new SignRequest("s1", "pk", "ks", "mh", 0, null);
        });
    }

    @Test
    public void testEqualsAndHashCode() {
        SignRequest a = new SignRequest("s1", "pk", "ks", "mh", 0, List.of("h1"));
        SignRequest b = new SignRequest("s1", "pk", "ks", "mh", 0, List.of("h1"));
        SignRequest c = new SignRequest("s1", "pk", "ks", "mh", 1, List.of("h1"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    public void testEqualsSelfAndNull() {
        SignRequest a = new SignRequest("s1", "pk", "ks", "mh", 0, List.of());
        assertEquals(a, a);
        assertTrue(!a.equals(null));
        assertTrue(!a.equals("x"));
    }

    @Test
    public void testToStringOmitsKeyShare() {
        SignRequest req = new SignRequest("s1", "pk", "SECRET-KEY-SHARE", "mh", 0, List.of());
        String s = req.toString();
        assertTrue(s.contains("s1"));
        assertTrue(!s.contains("SECRET-KEY-SHARE"));
    }
}