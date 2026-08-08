package org.nexus.signing.mpc.crypto;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link SignResponse} 单元测试。
 */
public class SignResponseTest {

    @Test
    public void testConstructorAndGetters() {
        SignResponse resp = new SignResponse("sig", "proof", true, null);
        assertEquals("sig", resp.getPartialSignature());
        assertEquals("proof", resp.getProof());
        assertTrue(resp.isSuccess());
        assertEquals(null, resp.getError());
    }

    @Test
    public void testFailedResponse() {
        SignResponse resp = new SignResponse(null, null, false, "engine down");
        assertTrue(!resp.isSuccess());
        assertEquals("engine down", resp.getError());
    }

    @Test
    public void testEqualsAndHashCode() {
        SignResponse a = new SignResponse("sig", "p", true, null);
        SignResponse b = new SignResponse("sig", "p", true, null);
        SignResponse c = new SignResponse("sig", "p", false, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    public void testEqualsSelfAndNull() {
        SignResponse a = new SignResponse("sig", "p", true, null);
        assertEquals(a, a);
        assertTrue(!a.equals(null));
        assertTrue(!a.equals("x"));
    }

    @Test
    public void testToString() {
        SignResponse resp = new SignResponse("sig", "p", true, null);
        String s = resp.toString();
        assertTrue(s.contains("success=true"));
    }
}