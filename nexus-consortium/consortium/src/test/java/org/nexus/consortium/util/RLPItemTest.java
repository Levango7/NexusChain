package org.nexus.consortium.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RLPItem 单元测试。
 * 覆盖各种 getRLP* 方法的转换行为。
 */
public class RLPItemTest {

    @Test
    public void testGetRLPBytes() {
        RLPItem item = new RLPItem(new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, item.getRLPBytes());
    }

    @Test
    public void testGetRLPBytesEmpty() {
        RLPItem item = new RLPItem(new byte[0]);
        assertNull(item.getRLPBytes());
    }

    @Test
    public void testGetRLPHexString() {
        RLPItem item = new RLPItem(new byte[]{0x01, 0x02, 0x03});
        assertEquals("010203", item.getRLPHexString());
    }

    @Test
    public void testGetRLPHexStringEmpty() {
        RLPItem item = new RLPItem(new byte[0]);
        assertNull(item.getRLPHexString());
    }

    @Test
    public void testGetRLPString() {
        RLPItem item = new RLPItem(new byte[]{65, 66, 67});
        assertEquals("ABC", item.getRLPString());
    }

    @Test
    public void testGetRLPStringEmpty() {
        RLPItem item = new RLPItem(new byte[0]);
        assertNull(item.getRLPString());
    }

    @Test
    public void testGetRLPInt() {
        RLPItem item = new RLPItem(new byte[]{0x01, 0x02});
        assertEquals(0x0102, item.getRLPInt());
    }

    @Test
    public void testGetRLPIntEmpty() {
        RLPItem item = new RLPItem(new byte[0]);
        assertEquals(-1, item.getRLPInt());
    }

    @Test
    public void testGetRLPByte() {
        RLPItem item = new RLPItem(new byte[]{0x05, 0x06});
        assertEquals(0x05, item.getRLPByte());
    }

    @Test
    public void testGetRLPByteEmpty() {
        RLPItem item = new RLPItem(new byte[0]);
        assertEquals(-1, item.getRLPByte());
    }

    @Test
    public void testGetRLPLong() {
        RLPItem item = new RLPItem(new byte[]{0x01, 0x02});
        assertEquals(0x0102L, item.getRLPLong());
    }

    @Test
    public void testGetRLPLongEmpty() {
        RLPItem item = new RLPItem(new byte[0]);
        assertEquals(-1L, item.getRLPLong());
    }

    @Test
    public void testSingleByte() {
        RLPItem item = new RLPItem(new byte[]{42});
        assertEquals(42, item.getRLPByte());
        assertEquals(42, item.getRLPInt());
        assertEquals(42L, item.getRLPLong());
    }
}