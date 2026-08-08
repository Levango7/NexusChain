package org.nexus.signing.mpc.crypto;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link AggregateResponse} 单元测试。
 */
public class AggregateResponseTest {

    @Test
    public void testConstructorAndGetters() {
        AggregateResponse resp = new AggregateResponse("r||s", "r", "s", 0, true, null);
        assertEquals("r||s", resp.getSignature());
        assertEquals("r", resp.getR());
        assertEquals("s", resp.getS());
        assertEquals(0, resp.getRecoveryId());
        assertTrue(resp.isSuccess());
        assertEquals(null, resp.getError());
    }

    @Test
    public void testFailedResponse() {
        AggregateResponse resp = new AggregateResponse(null, null, null, 0, false, "engine down");
        assertTrue(!resp.isSuccess());
        assertEquals("engine down", resp.getError());
    }

    @Test
    public void testAllRecoveryIds() {
        for (int i = 0; i <= 3; i++) {
            AggregateResponse resp = new AggregateResponse("sig", "r", "s", i, true, null);
            assertEquals(i, resp.getRecoveryId());
        }
    }

    @Test
    public void testEqualsAndHashCode() {
        AggregateResponse a = new AggregateResponse("sig", "r", "s", 0, true, null);
        AggregateResponse b = new AggregateResponse("sig", "r", "s", 0, true, null);
        AggregateResponse c = new AggregateResponse("sig", "r", "s", 1, true, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    public void testEqualsSelfAndNull() {
        AggregateResponse a = new AggregateResponse("sig", "r", "s", 0, true, null);
        assertEquals(a, a);
        assertTrue(!a.equals(null));
        assertTrue(!a.equals("x"));
    }

    @Test
    public void testToString() {
        AggregateResponse resp = new AggregateResponse("sig", "r", "s", 2, true, null);
        String s = resp.toString();
        assertTrue(s.contains("success=true"));
        assertTrue(s.contains("recoveryId=2"));
    }
}