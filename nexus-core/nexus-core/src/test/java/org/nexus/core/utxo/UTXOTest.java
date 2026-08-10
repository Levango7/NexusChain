package org.nexus.core.utxo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UTXO} 单元测试。
 */
class UTXOTest {

    @Test
    void defaultConstructorCreatesEmpty() {
        UTXO u = new UTXO();
        assertEquals(0, u.getTxtype());
        assertNull(u.getHash());
        assertEquals(0, u.getIndex());
        assertEquals(0, u.getAmount());
        assertEquals(0, u.getHeight());
        assertNull(u.getAddress());
        assertFalse(u.isIs_reference());
        assertFalse(u.isIs_confirm());
    }

    @Test
    void settersAndGetters() {
        UTXO u = new UTXO();
        byte[] hash = new byte[]{1, 2, 3};
        byte[] outscript = new byte[]{4};
        byte[] datascript = new byte[]{5};

        u.setTxtype((byte) 1);
        u.setHash(hash);
        u.setIndex(7);
        u.setAmount(999L);
        u.setHeight(100L);
        u.setAddress("addr");
        u.setOutscript(outscript);
        u.setDatascript(datascript);
        u.setIs_reference(true);
        u.setIs_confirm(false);

        assertEquals((byte) 1, u.getTxtype());
        assertArrayEquals(hash, u.getHash());
        assertEquals(7, u.getIndex());
        assertEquals(999L, u.getAmount());
        assertEquals(100L, u.getHeight());
        assertEquals("addr", u.getAddress());
        assertArrayEquals(outscript, u.getOutscript());
        assertArrayEquals(datascript, u.getDatascript());
        assertTrue(u.isIs_reference());
        assertFalse(u.isIs_confirm());
    }
}