package org.nexus.consortium.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BytesReader 单元测试。
 * 覆盖 read、readAll、边界条件。
 */
public class BytesReaderTest {

    @Test
    public void testReadFull() {
        BytesReader reader = new BytesReader(new byte[]{1, 2, 3, 4, 5});
        byte[] result = reader.read(5);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, result);
    }

    @Test
    public void testReadPartial() {
        BytesReader reader = new BytesReader(new byte[]{1, 2, 3, 4, 5});
        byte[] first = reader.read(2);
        assertArrayEquals(new byte[]{1, 2}, first);
        byte[] second = reader.read(2);
        assertArrayEquals(new byte[]{3, 4}, second);
    }

    @Test
    public void testReadAndReadAll() {
        BytesReader reader = new BytesReader(new byte[]{1, 2, 3, 4, 5});
        reader.read(2);
        byte[] rest = reader.readAll();
        assertArrayEquals(new byte[]{3, 4, 5}, rest);
    }

    @Test
    public void testReadExceedsLength() {
        BytesReader reader = new BytesReader(new byte[]{1, 2});
        byte[] result = reader.read(5);
        assertArrayEquals(new byte[0], result);
    }

    @Test
    public void testReadAllEmpty() {
        BytesReader reader = new BytesReader(new byte[]{1, 2});
        reader.read(2);
        byte[] result = reader.readAll();
        assertArrayEquals(new byte[0], result);
    }

    @Test
    public void testReadAllFromStart() {
        BytesReader reader = new BytesReader(new byte[]{1, 2, 3});
        byte[] result = reader.readAll();
        assertArrayEquals(new byte[]{1, 2, 3}, result);
    }

    @Test
    public void testReadZero() {
        BytesReader reader = new BytesReader(new byte[]{1, 2, 3});
        byte[] result = reader.read(0);
        assertArrayEquals(new byte[0], result);
    }

    @Test
    public void testEmptyData() {
        BytesReader reader = new BytesReader(new byte[0]);
        byte[] result = reader.read(1);
        assertArrayEquals(new byte[0], result);
        byte[] all = reader.readAll();
        assertArrayEquals(new byte[0], all);
    }

    @Test
    public void testMultipleReads() {
        BytesReader reader = new BytesReader(new byte[]{1, 2, 3, 4, 5, 6});
        assertArrayEquals(new byte[]{1}, reader.read(1));
        assertArrayEquals(new byte[]{2, 3}, reader.read(2));
        assertArrayEquals(new byte[]{4, 5, 6}, reader.readAll());
    }
}