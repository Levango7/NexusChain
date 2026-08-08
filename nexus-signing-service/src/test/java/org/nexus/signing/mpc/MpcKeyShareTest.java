package org.nexus.signing.mpc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link MpcKeyShare} 单元测试。
 */
public class MpcKeyShareTest {

    @Test
    public void testConstructorAndGetters() {
        MpcKeyShare share = new MpcKeyShare("p1", "priv-hex", "pub-hex", "paillier-hex");
        assertEquals("p1", share.getParticipantId());
        assertEquals("priv-hex", share.getPrivateShareHex());
        assertEquals("pub-hex", share.getPublicShareHex());
        assertEquals("paillier-hex", share.getPaillierPublicKeyHex());
    }

    @Test
    public void testNullPaillierAllowed() {
        MpcKeyShare share = new MpcKeyShare("p1", "priv", "pub", null);
        assertNull(share.getPaillierPublicKeyHex());
    }

    @Test(expected = NullPointerException.class)
    public void testNullParticipantIdThrows() {
        new MpcKeyShare(null, "priv", "pub", null);
    }

    @Test(expected = NullPointerException.class)
    public void testNullPrivateShareThrows() {
        new MpcKeyShare("p1", null, "pub", null);
    }

    @Test(expected = NullPointerException.class)
    public void testNullPublicShareThrows() {
        new MpcKeyShare("p1", "priv", null, null);
    }

    @Test
    public void testEqualsAndHashCodeByValue() {
        MpcKeyShare a = new MpcKeyShare("p1", "priv", "pub", "paillier");
        MpcKeyShare b = new MpcKeyShare("p1", "priv", "pub", "different-paillier");
        MpcKeyShare c = new MpcKeyShare("p2", "priv", "pub", "paillier");
        // equals 依赖 participantId + privateShareHex + publicShareHex
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    public void testEqualsSelf() {
        MpcKeyShare a = new MpcKeyShare("p1", "priv", "pub", null);
        assertEquals(a, a);
    }

    @Test
    public void testNotEqualsToNullAndOtherType() {
        MpcKeyShare a = new MpcKeyShare("p1", "priv", "pub", null);
        assertFalse(a.equals(null));
        assertFalse(a.equals("not a share"));
    }

    @Test
    public void testToStringOmitsPrivateShare() {
        MpcKeyShare share = new MpcKeyShare("p1", "SECRET-PRIVATE", "pub", null);
        String s = share.toString();
        assertNotNull(s);
        assertTrue("toString should contain participantId", s.contains("p1"));
        assertTrue("toString should contain publicShare", s.contains("pub"));
        assertFalse("toString must not leak private share", s.contains("SECRET-PRIVATE"));
    }
}