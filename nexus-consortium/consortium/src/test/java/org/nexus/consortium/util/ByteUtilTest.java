package org.nexus.consortium.util;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ByteUtil 单元测试。
 * 覆盖字节转换、位运算、合并/追加、前导零剥离等核心方法。
 */
public class ByteUtilTest {

    @Test
    public void testAppendByte() {
        byte[] bytes = new byte[]{1, 2, 3};
        byte[] result = ByteUtil.appendByte(bytes, (byte) 4);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, result);
    }

    @Test
    public void testAppendByteToEmpty() {
        byte[] result = ByteUtil.appendByte(new byte[0], (byte) 1);
        assertArrayEquals(new byte[]{1}, result);
    }

    @Test
    public void testLongToBytes() {
        byte[] result = ByteUtil.longToBytes(0x0102030405060708L);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, result);
    }

    @Test
    public void testLongToBytesZero() {
        byte[] result = ByteUtil.longToBytes(0L);
        assertArrayEquals(new byte[8], result);
    }

    @Test
    public void testIntToBytes() {
        byte[] result = ByteUtil.intToBytes(0x01020304);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, result);
    }

    @Test
    public void testIntToBytesZero() {
        byte[] result = ByteUtil.intToBytes(0);
        assertArrayEquals(new byte[4], result);
    }

    @Test
    public void testLongToBytesNoLeadZeroes() {
        byte[] result = ByteUtil.longToBytesNoLeadZeroes(0x0102030405060708L);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, result);
    }

    @Test
    public void testLongToBytesNoLeadZeroesZero() {
        byte[] result = ByteUtil.longToBytesNoLeadZeroes(0L);
        assertArrayEquals(new byte[0], result);
    }

    @Test
    public void testIntToBytesNoLeadZeroes() {
        byte[] result = ByteUtil.intToBytesNoLeadZeroes(0x01020304);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, result);
    }

    @Test
    public void testIntToBytesNoLeadZeroesZero() {
        byte[] result = ByteUtil.intToBytesNoLeadZeroes(0);
        assertArrayEquals(new byte[0], result);
    }

    @Test
    public void testToHexString() {
        String result = ByteUtil.toHexString(new byte[]{0x01, 0x02, 0x03});
        assertEquals("010203", result);
    }

    @Test
    public void testToHexStringNull() {
        String result = ByteUtil.toHexString(null);
        assertEquals("", result);
    }

    @Test
    public void testToHexStringEmpty() {
        String result = ByteUtil.toHexString(new byte[0]);
        assertEquals("", result);
    }

    @Test
    public void testByteArrayToInt() {
        int result = ByteUtil.byteArrayToInt(new byte[]{0x01, 0x02});
        assertEquals(0x0102, result);
    }

    @Test
    public void testByteArrayToIntNull() {
        int result = ByteUtil.byteArrayToInt(null);
        assertEquals(0, result);
    }

    @Test
    public void testByteArrayToIntEmpty() {
        int result = ByteUtil.byteArrayToInt(new byte[0]);
        assertEquals(0, result);
    }

    @Test
    public void testByteArrayToLong() {
        long result = ByteUtil.byteArrayToLong(new byte[]{0x01, 0x02});
        assertEquals(0x0102L, result);
    }

    @Test
    public void testByteArrayToLongNull() {
        long result = ByteUtil.byteArrayToLong(null);
        assertEquals(0L, result);
    }

    @Test
    public void testByteArrayToLongEmpty() {
        long result = ByteUtil.byteArrayToLong(new byte[0]);
        assertEquals(0L, result);
    }

    @Test
    public void testBigIntegerToBytes() {
        BigInteger value = new BigInteger("255");
        byte[] result = ByteUtil.bigIntegerToBytes(value);
        assertArrayEquals(new byte[]{(byte) 0xFF}, result);
    }

    @Test
    public void testBigIntegerToBytesNull() {
        byte[] result = ByteUtil.bigIntegerToBytes(null);
        assertNull(result);
    }

    @Test
    public void testBigIntegerToBytesZero() {
        BigInteger value = BigInteger.ZERO;
        byte[] result = ByteUtil.bigIntegerToBytes(value);
        assertArrayEquals(new byte[]{0}, result);
    }

    @Test
    public void testBytesToBigInteger() {
        BigInteger result = ByteUtil.bytesToBigInteger(new byte[]{0x01, 0x02});
        assertEquals(new BigInteger("258"), result);
    }

    @Test
    public void testBytesToBigIntegerNull() {
        BigInteger result = ByteUtil.bytesToBigInteger(null);
        assertEquals(BigInteger.ZERO, result);
    }

    @Test
    public void testBytesToBigIntegerEmpty() {
        BigInteger result = ByteUtil.bytesToBigInteger(new byte[0]);
        assertEquals(BigInteger.ZERO, result);
    }

    @Test
    public void testMatchingNibbleLength() {
        byte[] a = new byte[]{1, 2, 3, 4, 5};
        byte[] b = new byte[]{1, 2, 3, 9, 9};
        assertEquals(3, ByteUtil.matchingNibbleLength(a, b));
    }

    @Test
    public void testMatchingNibbleLengthFullMatch() {
        byte[] a = new byte[]{1, 2, 3};
        byte[] b = new byte[]{1, 2, 3};
        assertEquals(3, ByteUtil.matchingNibbleLength(a, b));
    }

    @Test
    public void testMatchingNibbleLengthNoMatch() {
        byte[] a = new byte[]{1, 2, 3};
        byte[] b = new byte[]{4, 5, 6};
        assertEquals(0, ByteUtil.matchingNibbleLength(a, b));
    }

    @Test
    public void testMerge() {
        byte[] result = ByteUtil.merge(new byte[]{1, 2}, new byte[]{3, 4}, new byte[]{5});
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, result);
    }

    @Test
    public void testMergeSingleArray() {
        byte[] result = ByteUtil.merge(new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, result);
    }

    @Test
    public void testMergeEmptyArrays() {
        byte[] result = ByteUtil.merge(new byte[0], new byte[0]);
        assertArrayEquals(new byte[0], result);
    }

    @Test
    public void testIsNullOrZeroArray() {
        assertTrue(ByteUtil.isNullOrZeroArray(null));
        assertTrue(ByteUtil.isNullOrZeroArray(new byte[0]));
        assertFalse(ByteUtil.isNullOrZeroArray(new byte[]{1}));
    }

    @Test
    public void testIsSingleZero() {
        assertTrue(ByteUtil.isSingleZero(new byte[]{0}));
        assertFalse(ByteUtil.isSingleZero(new byte[]{1}));
        assertFalse(ByteUtil.isSingleZero(new byte[]{0, 0}));
    }

    @Test
    public void testAnd() {
        byte[] b1 = new byte[]{(byte) 0xFF, 0x0F};
        byte[] b2 = new byte[]{(byte) 0xF0, (byte) 0xFF};
        assertArrayEquals(new byte[]{(byte) 0xF0, 0x0F}, ByteUtil.and(b1, b2));
    }

    @Test
    public void testOr() {
        byte[] b1 = new byte[]{(byte) 0xF0, 0x0F};
        byte[] b2 = new byte[]{0x0F, (byte) 0xF0};
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFF}, ByteUtil.or(b1, b2));
    }

    @Test
    public void testXor() {
        byte[] b1 = new byte[]{(byte) 0xFF, (byte) 0xFF};
        byte[] b2 = new byte[]{(byte) 0xFF, (byte) 0xFF};
        assertArrayEquals(new byte[]{0, 0}, ByteUtil.xor(b1, b2));
    }

    @Test
    public void testXorAlignRight() {
        byte[] b1 = new byte[]{1, 2, 3};
        byte[] b2 = new byte[]{5};
        byte[] result = ByteUtil.xorAlignRight(b1, b2);
        assertEquals(3, result.length);
    }

    @Test
    public void testStripLeadingZeroes() {
        byte[] data = new byte[]{0, 0, 1, 2, 3};
        assertArrayEquals(new byte[]{1, 2, 3}, ByteUtil.stripLeadingZeroes(data));
    }

    @Test
    public void testStripLeadingZeroesAllZero() {
        byte[] data = new byte[]{0, 0, 0};
        assertArrayEquals(new byte[]{0}, ByteUtil.stripLeadingZeroes(data));
    }

    @Test
    public void testStripLeadingZeroesNull() {
        assertNull(ByteUtil.stripLeadingZeroes(null));
    }

    @Test
    public void testStripLeadingZeroesNoLeading() {
        byte[] data = new byte[]{1, 2, 3};
        assertArrayEquals(new byte[]{1, 2, 3}, ByteUtil.stripLeadingZeroes(data));
    }

    @Test
    public void testFirstNonZeroByte() {
        assertEquals(2, ByteUtil.firstNonZeroByte(new byte[]{0, 0, 1, 2}));
    }

    @Test
    public void testFirstNonZeroByteAllZero() {
        assertEquals(-1, ByteUtil.firstNonZeroByte(new byte[]{0, 0, 0}));
    }

    @Test
    public void testFirstNonZeroByteFirstNonZero() {
        assertEquals(0, ByteUtil.firstNonZeroByte(new byte[]{1, 0, 0}));
    }

    @Test
    public void testCalcPacketLength() {
        byte[] msg = new byte[256];
        byte[] result = ByteUtil.calcPacketLength(msg);
        assertArrayEquals(new byte[]{0, 0, 1, 0}, result);
    }

    @Test
    public void testNumBytes() {
        assertEquals(1, ByteUtil.numBytes("0"));
        assertEquals(1, ByteUtil.numBytes("255"));
        assertEquals(2, ByteUtil.numBytes("256"));
    }

    @Test
    public void testOneByteToHexString() {
        assertEquals("0a", ByteUtil.oneByteToHexString((byte) 10));
        assertEquals("ff", ByteUtil.oneByteToHexString((byte) 0xFF));
    }

    @Test
    public void testNibblesToPrettyString() {
        byte[] nibbles = new byte[]{0x01, 0x02};
        String result = ByteUtil.nibblesToPrettyString(nibbles);
        assertEquals("\\x01\\x02", result);
    }
}