package org.nexus.signing.mpc.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DkgResponse} 单元测试。
 */
public class DkgResponseTest {

    @Test
    public void testConstructorAndGetters() {
        DkgResponse resp = new DkgResponse("pk-hex", "share-hex", "proof-hex", true, null);
        assertEquals(resp.getPublicKey(), "pk-hex");
        assertEquals(resp.getKeyShare(), "share-hex");
        assertEquals(resp.getProof(), "proof-hex");
        assertTrue(resp.isSuccess());
        assertEquals(null, resp.getError());
    }

    @Test
    public void testFailedResponse() {
        DkgResponse resp = new DkgResponse(null, null, null, false, "engine down");
        assertTrue(!resp.isSuccess());
        assertEquals(resp.getError(), "engine down");
    }

    @Test
    public void testEqualsAndHashCode() {
        DkgResponse a = new DkgResponse("pk", "ks", "p", true, null);
        DkgResponse b = new DkgResponse("pk", "ks", "p", true, null);
        DkgResponse c = new DkgResponse("pk", "ks", "p", false, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    public void testEqualsSelfAndNull() {
        DkgResponse a = new DkgResponse("pk", "ks", "p", true, null);
        assertEquals(a, a);
        assertTrue(!a.equals(null));
        assertTrue(!a.equals("x"));
    }

    @Test
    public void testToStringOmitsKeyShare() {
        DkgResponse resp = new DkgResponse("pk", "SECRET-KEY-SHARE", "p", true, null);
        String s = resp.toString();
        assertTrue(s.contains("pk"));
        assertTrue(!s.contains("SECRET-KEY-SHARE"));
    }
}