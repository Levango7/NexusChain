package org.nexus.consortium.account;

import org.apache.commons.codec.DecoderException;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PublicKeyHash 单元测试。
 * 覆盖 fromPublicKey、from、getAddress、getHex、getPublicKeyHash。
 */
public class PublicKeyHashTest {

    private static final byte[] PUBLIC_KEY;
    static {
        try {
            PUBLIC_KEY = org.apache.commons.codec.binary.Hex.decodeHex(
                    "d0f1966cee219fcfdbcee698517fcf864f46817c30bc8218eb4889d02f312540".toCharArray());
        } catch (DecoderException e) {
            throw new RuntimeException(e);
        }
    }

    private static final byte[] PUBLIC_KEY_HASH = org.bouncycastle.util.encoders.Hex.decode("039a676b65273eeca96af35e35c05e482650c979");
    private static final String ADDRESS = "1L3zkde4kSpfd1L7NYmNYSBf1Bvh6fZLk";

    @Test
    public void testFromPublicKey() {
        PublicKeyHash pkh = PublicKeyHash.fromPublicKey(PUBLIC_KEY);
        assertNotNull(pkh);
        assertArrayEquals(PUBLIC_KEY_HASH, pkh.getPublicKeyHash());
    }

    @Test
    public void testGetAddress() {
        PublicKeyHash pkh = PublicKeyHash.fromPublicKey(PUBLIC_KEY);
        assertEquals(ADDRESS, pkh.getAddress());
    }

    @Test
    public void testGetAddressCached() {
        PublicKeyHash pkh = PublicKeyHash.fromPublicKey(PUBLIC_KEY);
        String addr1 = pkh.getAddress();
        String addr2 = pkh.getAddress();
        assertEquals(addr1, addr2);
        assertSame(addr1, addr2);
    }

    @Test
    public void testGetHex() {
        PublicKeyHash pkh = PublicKeyHash.fromPublicKey(PUBLIC_KEY);
        String hex = pkh.getHex();
        assertNotNull(hex);
        assertTrue(hex.length() > 0);
    }

    @Test
    public void testGetHexCached() {
        PublicKeyHash pkh = PublicKeyHash.fromPublicKey(PUBLIC_KEY);
        String hex1 = pkh.getHex();
        String hex2 = pkh.getHex();
        assertSame(hex1, hex2);
    }

    @Test
    public void testFromAddress() {
        Optional<PublicKeyHash> opt = PublicKeyHash.from(ADDRESS);
        assertTrue(opt.isPresent());
        assertArrayEquals(PUBLIC_KEY_HASH, opt.get().getPublicKeyHash());
    }

    @Test
    public void testFromInvalidString() {
        Optional<PublicKeyHash> opt = PublicKeyHash.from("invalid");
        assertFalse(opt.isPresent());
    }

    @Test
    public void testConstructorWithHash() {
        PublicKeyHash pkh = new PublicKeyHash(PUBLIC_KEY_HASH);
        assertArrayEquals(PUBLIC_KEY_HASH, pkh.getPublicKeyHash());
    }

    @Test
    public void testGetPublicKeyHash() {
        PublicKeyHash pkh = new PublicKeyHash(PUBLIC_KEY_HASH);
        assertArrayEquals(PUBLIC_KEY_HASH, pkh.getPublicKeyHash());
    }
}
