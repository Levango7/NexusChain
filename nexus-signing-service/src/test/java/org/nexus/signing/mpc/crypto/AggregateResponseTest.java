package org.nexus.signing.mpc.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AggregateResponse} 单元测试。
 */
public class AggregateResponseTest {

    @Test
    public void testConstructorAndGetters() {
        AggregateResponse resp = new AggregateResponse("r||s", "r", "s", 0, true, null);
        assertEquals(resp.getSignature(), "r||s");
        assertEquals(resp.getR(), "r");
        assertEquals(resp.getS(), "s");
        assertEquals(0, resp.getRecoveryId());
        assertTrue(resp.isSuccess());
        assertEquals(null, resp.getError());
    }

    @Test
    public void testFailedResponse() {
        AggregateResponse resp = new AggregateResponse(null, null, null, 0, false, "engine down");
        assertTrue(!resp.isSuccess());
        assertEquals(resp.getError(), "engine down");
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