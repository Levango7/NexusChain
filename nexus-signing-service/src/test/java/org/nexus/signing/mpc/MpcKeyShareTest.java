package org.nexus.signing.mpc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MpcKeyShare} 单元测试。
 */
public class MpcKeyShareTest {

    @Test
    public void testConstructorAndGetters() {
        MpcKeyShare share = new MpcKeyShare("p1", "priv-hex", "pub-hex", "paillier-hex");
        assertEquals(share.getParticipantId(), "p1");
        assertEquals(share.getPrivateShareHex(), "priv-hex");
        assertEquals(share.getPublicShareHex(), "pub-hex");
        assertEquals(share.getPaillierPublicKeyHex(), "paillier-hex");
    }

    @Test
    public void testNullPaillierAllowed() {
        MpcKeyShare share = new MpcKeyShare("p1", "priv", "pub", null);
        assertNull(share.getPaillierPublicKeyHex());
    }

    @Test
    public void testNullParticipantIdThrows() { assertThrows(NullPointerException.class, () -> {
        new MpcKeyShare(null, "priv", "pub", null);
        });
    }

    @Test
    public void testNullPrivateShareThrows() { assertThrows(NullPointerException.class, () -> {
        new MpcKeyShare("p1", null, "pub", null);
        });
    }

    @Test
    public void testNullPublicShareThrows() { assertThrows(NullPointerException.class, () -> {
        new MpcKeyShare("p1", "priv", null, null);
        });
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
        assertTrue(s.contains("p1"), "toString should contain participantId");
        assertTrue(s.contains("pub"), "toString should contain publicShare");
        assertFalse(s.contains("SECRET-PRIVATE"), "toString must not leak private share");
    }
}