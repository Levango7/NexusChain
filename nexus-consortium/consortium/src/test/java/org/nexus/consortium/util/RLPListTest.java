package org.nexus.consortium.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RLPList 单元测试。
 * 覆盖 ArrayList 继承行为、setRLPData、getRLP* 方法。
 */
public class RLPListTest {

    @Test
    public void testEmptyList() {
        RLPList list = new RLPList();
        assertEquals(0, list.size());
        assertTrue(list.isEmpty());
    }

    @Test
    public void testAddAndGet() {
        RLPList list = new RLPList();
        RLPItem item = new RLPItem(new byte[]{1, 2});
        list.add(item);
        assertEquals(1, list.size());
        assertEquals(item, list.get(0));
    }

    @Test
    public void testAddMultiple() {
        RLPList list = new RLPList();
        list.add(new RLPItem(new byte[]{1}));
        list.add(new RLPItem(new byte[]{2}));
        list.add(new RLPItem(new byte[]{3}));
        assertEquals(3, list.size());
    }

    @Test
    public void testSetRLPData() {
        RLPList list = new RLPList();
        byte[] data = new byte[]{1, 2, 3};
        list.setRLPData(data);
        assertArrayEquals(data, list.getRLPBytes());
    }

    @Test
    public void testGetRLPHexString() {
        RLPList list = new RLPList();
        list.setRLPData(new byte[]{0x01, 0x02});
        assertEquals("0102", list.getRLPHexString());
    }

    @Test
    public void testGetRLPString() {
        RLPList list = new RLPList();
        list.setRLPData(new byte[]{65, 66, 67});
        assertEquals("ABC", list.getRLPString());
    }

    @Test
    public void testGetRLPInt() {
        RLPList list = new RLPList();
        list.setRLPData(new byte[]{0x01, 0x02});
        assertEquals(0x0102, list.getRLPInt());
    }

    @Test
    public void testGetRLPByte() {
        RLPList list = new RLPList();
        list.setRLPData(new byte[]{0x05, 0x06});
        assertEquals(0x05, list.getRLPByte());
    }

    @Test
    public void testGetRLPLong() {
        RLPList list = new RLPList();
        list.setRLPData(new byte[]{0x01, 0x02});
        assertEquals(0x0102L, list.getRLPLong());
    }

    @Test
    public void testRecursivePrintNull() {
        assertThrows(RuntimeException.class, () -> RLPList.recursivePrint(null));
    }

    @Test
    public void testRecursivePrintItem() {
        RLPItem item = new RLPItem(new byte[]{1, 2});
        RLPList.recursivePrint(item);
    }

    @Test
    public void testRecursivePrintList() {
        RLPList list = new RLPList();
        list.add(new RLPItem(new byte[]{1}));
        list.add(new RLPItem(new byte[]{2}));
        RLPList.recursivePrint(list);
    }

    @Test
    public void testNestedList() {
        RLPList outer = new RLPList();
        RLPList inner = new RLPList();
        inner.add(new RLPItem(new byte[]{1}));
        outer.add(inner);
        assertEquals(1, outer.size());
        assertTrue(outer.get(0) instanceof RLPList);
    }
}