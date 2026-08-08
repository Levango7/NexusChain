package org.nexus.signing.mpc.crypto;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link DkgResponse} 单元测试。
 */
public class DkgResponseTest {

    @Test
    public void testConstructorAndGetters() {
        DkgResponse resp = new DkgResponse("pk-hex", "share-hex", "proof-hex", true, null);
        assertEquals("pk-hex", resp.getPublicKey());
        assertEquals("share-hex", resp.getKeyShare());
        assertEquals("proof-hex", resp.getProof());
        assertTrue(resp.isSuccess());
        assertEquals(null, resp.getError());
    }

    @Test
    public void testFailedResponse() {
        DkgResponse resp = new DkgResponse(null, null, null, false, "engine down");
        assertTrue(!resp.isSuccess());
        assertEquals("engine down", resp.getError());
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