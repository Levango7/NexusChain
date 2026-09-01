package org.nexus.util;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ByteUtil 纯逻辑单测（A 项覆盖率提升：0.25→0.30）。
 * 断言基于源码语义逐条核对（ByteUtil.java:44-822），非弱断言：
 * 编解码往返、边界（null/空/进位溢出）、异常路径（长度不等/越界/超 32 字节）。
 */
class ByteUtilTest {

    // ===== appendByte / prepend（ByteUtil.java:44/50） =====

    @Test
    void appendByteAppendsAtEnd() {
        byte[] out = ByteUtil.appendByte(new byte[]{1, 2}, (byte) 3);
        assertArrayEquals(new byte[]{1, 2, 3}, out);
    }

    @Test
    void prependPutsByteAtStartAndHandlesNull() {
        assertArrayEquals(new byte[]{9, 1, 2}, ByteUtil.prepend(new byte[]{1, 2}, (byte) 9));
        assertArrayEquals(new byte[]{9}, ByteUtil.prepend(null, (byte) 9));
    }

    // ===== paritybyteempty / checkZero（:69/82） =====

    @Test
    void paritybyteemptyTrueWhenAtMostTwoZeroBytes() {
        assertTrue(ByteUtil.paritybyteempty(new byte[]{1, 2, 3}));
        assertTrue(ByteUtil.paritybyteempty(new byte[]{0, 1, 0}));
        assertFalse(ByteUtil.paritybyteempty(new byte[]{0, 0, 0}));
    }

    @Test
    void checkZeroOnlyTrueForAllZeroes() {
        assertTrue(ByteUtil.checkZero(new byte[]{0, 0}));
        assertFalse(ByteUtil.checkZero(new byte[]{0, 1}));
        assertTrue(ByteUtil.checkZero(new byte[]{}));
    }

    // ===== bytearraycopy / bytesInt / byte2Int（:98/109/121） =====

    @Test
    void bytearraycopyExtractsSubrange() {
        assertArrayEquals(
                new byte[]{2, 3},
                ByteUtil.bytearraycopy(new byte[]{1, 2, 3, 4}, 1, 2));
    }

    @Test
    void bytesIntReadsFirstByteUnsigned() {
        // (int)b[0] & 0xff——负 byte 也按无符号解释
        assertEquals(255, ByteUtil.bytesInt(new byte[]{(byte) 0xFF}));
        assertEquals(0, ByteUtil.bytesInt(new byte[]{0}));
    }

    @Test
    void byte2IntBigEndianFullWidth() {
        assertEquals(0x01020304, ByteUtil.byte2Int(new byte[]{1, 2, 3, 4}));
        assertEquals(0, ByteUtil.byte2Int(new byte[]{0, 0, 0, 0}));
    }

    // ===== bigIntegerToBytes 家族（:137/148/170/191） =====

    @Test
    void bigIntegerToBytesPadsToRequestedLength() {
        // 256 = 0x0100；请求 4 字节应左补零
        assertArrayEquals(
                new byte[]{0, 0, 0, 1, 0},
                ByteUtil.bigIntegerToBytes(BigInteger.valueOf(256), 5));
        // null 直接返回 null（源码契约）
        assertNull(ByteUtil.bigIntegerToBytes(null, 4));
    }

    @Test
    void bigIntegerToBytesDropsSignByteWhenExactPlusOne() {
        // BigInteger.ONE.toByteArray() = [0,1]（符号位）；numBytes=1 时 start=1 剥离
        assertArrayEquals(
                new byte[]{1},
                ByteUtil.bigIntegerToBytes(BigInteger.ONE, 1));
    }

    @Test
    void bigIntegerToBytesSignedKeepsLeadingZeroForPositive() {
        // 128 的带符号编码 = [0, -128]；numBytes=2 时保留
        byte[] out = ByteUtil.bigIntegerToBytesSigned(BigInteger.valueOf(128), 2);
        assertArrayEquals(new byte[]{0, (byte) 0x80}, out);
    }

    @Test
    void bigIntegerToBytesNoLengthUsesMinimalWidth() {
        assertArrayEquals(new byte[]{0x0A}, ByteUtil.bigIntegerToBytes(BigInteger.TEN));
        // BigInteger.ZERO 的 toByteArray() = [0]
        assertArrayEquals(new byte[]{0}, ByteUtil.bigIntegerToBytes(BigInteger.ZERO));
    }

    @Test
    void bytesToBigIntegerRoundTripsPositiveValue() {
        byte[] enc = ByteUtil.bigIntegerToBytes(BigInteger.valueOf(0x7F0011));
        assertEquals(BigInteger.valueOf(0x7F0011), ByteUtil.bytesToBigInteger(enc));
    }

    // ===== matchingNibbleLength（:203） =====

    @Test
    void matchingNibbleLengthCountsCommonPrefixBytes() {
        byte[] a = {1, 2, 3, 9};
        byte[] b = {1, 2, 3, 4};
        assertEquals(3, ByteUtil.matchingNibbleLength(a, b));
        assertEquals(0, ByteUtil.matchingNibbleLength(new byte[]{5}, new byte[]{1}));
    }

    // ===== long/int ↔ bytes（:220/230/245/255） =====

    @Test
    void longToBytesIsFull8ByteBigEndian() {
        assertArrayEquals(
                new byte[]{0, 0, 0, 0, 0, 0, 1, 0},
                ByteUtil.longToBytes(256L));
    }

    @Test
    void longToBytesNoLeadZeroesStrips() {
        assertArrayEquals(new byte[]{1, 0}, ByteUtil.longToBytesNoLeadZeroes(256L));
        // 源码 :232——val==0 直接返回 EMPTY_BYTE_ARRAY（与 intToBytesNoLeadZeroes 同契约）
        assertArrayEquals(new byte[]{}, ByteUtil.longToBytesNoLeadZeroes(0L));
    }

    @Test
    void intToBytesAndNoLeadZeroes() {
        assertArrayEquals(
                new byte[]{0, 0, 1, 0},
                ByteUtil.intToBytes(256));
        assertArrayEquals(new byte[]{1, 0}, ByteUtil.intToBytesNoLeadZeroes(256));
        assertArrayEquals(new byte[]{}, ByteUtil.intToBytesNoLeadZeroes(0));
        assertEquals(4, ByteUtil.intToBytes(-1).length);
    }

    // ===== toHexString / calcPacketLength / byteArrayToInt / byteArrayToLong（:292/302/320/335） =====

    @Test
    void toHexStringNullSafe() {
        assertEquals("", ByteUtil.toHexString(null));
        assertEquals("0102", ByteUtil.toHexString(new byte[]{1, 2}));
    }

    @Test
    void calcPacketLengthIsBigEndianInt32() {
        assertArrayEquals(
                new byte[]{0, 0, 0, 5},
                ByteUtil.calcPacketLength(new byte[5]));
    }

    @Test
    void byteArrayToIntTreatsNullAndEmptyAsZero() {
        assertEquals(0, ByteUtil.byteArrayToInt(null));
        assertEquals(0, ByteUtil.byteArrayToInt(new byte[]{}));
        assertEquals(0x0102, ByteUtil.byteArrayToInt(new byte[]{1, 2}));
    }

    @Test
    void byteArrayToLongTreatsNullAndEmptyAsZero() {
        assertEquals(0L, ByteUtil.byteArrayToLong(null));
        assertEquals(0L, ByteUtil.byteArrayToLong(new byte[]{}));
        assertEquals(1L, ByteUtil.byteArrayToLong(new byte[]{1}));
    }

    // ===== nibblesToPrettyString / oneByteToHexString / numBytes（:351/360/373） =====

    @Test
    void nibblesToPrettyStringUsesHexPerNibble() {
        assertEquals("\\x01\\x0a\\xff", ByteUtil.nibblesToPrettyString(new byte[]{1, 0x0A, (byte) 0xFF}));
    }

    @Test
    void oneByteToHexStringZeroPadsSingleDigit() {
        assertEquals("0a", ByteUtil.oneByteToHexString((byte) 0x0A));
        assertEquals("ff", ByteUtil.oneByteToHexString((byte) 0xFF));
    }

    @Test
    void numBytesComputesMinimalEncodingWidth() {
        assertEquals(1, ByteUtil.numBytes("0"));
        assertEquals(1, ByteUtil.numBytes("255"));
        assertEquals(2, ByteUtil.numBytes("256"));
        assertEquals(2, ByteUtil.numBytes("65535"));
        assertEquals(3, ByteUtil.numBytes("65536"));
    }

    // ===== encodeValFor32Bits / encodeDataList（:390/423） =====

    @Test
    void encodeValFor32BitsRightPadsDecimal() {
        byte[] out = ByteUtil.encodeValFor32Bits("1");
        assertEquals(32, out.length);
        assertEquals(1, out[31]);
        assertEquals(0, out[0]);
    }

    @Test
    void encodeValFor32BitsAcceptsHexLiteral() {
        byte[] out = ByteUtil.encodeValFor32Bits("0x10");
        assertEquals(0x10, out[31] & 0xFF);
    }

    @Test
    void encodeValFor32BitsFallsBackToStringBytes() {
        byte[] out = ByteUtil.encodeValFor32Bits("zz");
        // "zz" 的最后两字节，其余右对齐零填充
        assertEquals('z', out[30]);
        assertEquals('z', out[31]);
    }

    @Test
    void encodeValFor32BitsRejectsOversizedInput() {
        // 33 字节负载必然超限 → RuntimeException（源码 :405 契约）
        String big = new BigInteger("2").pow(8 * 33).toString();
        assertThrows(RuntimeException.class, () -> ByteUtil.encodeValFor32Bits(big));
    }

    @Test
    void encodeDataListConcatenates32ByteChunks() {
        byte[] out = ByteUtil.encodeDataList("1", "2");
        assertEquals(64, out.length);
        assertEquals(1, out[31]);
        assertEquals(2, out[63]);
    }

    // ===== firstNonZeroByte / stripLeadingZeroes（:436/445） =====

    @Test
    void firstNonZeroByteFindsIndexOrMinusOne() {
        assertEquals(-1, ByteUtil.firstNonZeroByte(new byte[]{0, 0}));
        assertEquals(1, ByteUtil.firstNonZeroByte(new byte[]{0, 5, 5}));
    }

    @Test
    void stripLeadingZeroesCases() {
        assertNull(ByteUtil.stripLeadingZeroes(null));
        // 全零 → [0]（ZERO_BYTE_ARRAY）
        assertArrayEquals(new byte[]{0}, ByteUtil.stripLeadingZeroes(new byte[]{0, 0}));
        // 首字节非零 → 原样返回
        byte[] identity = {1, 0};
        assertSame(identity, ByteUtil.stripLeadingZeroes(identity));
        assertArrayEquals(new byte[]{5}, ByteUtil.stripLeadingZeroes(new byte[]{0, 5}));
    }

    // ===== increment（:472） =====

    @Test
    void incrementAdvancesWithoutCarry() {
        byte[] b = {0, 5};
        assertTrue(ByteUtil.increment(b));
        assertArrayEquals(new byte[]{0, 6}, b);
    }

    @Test
    void incrementWrapsReturningFalseOnAllZeroOverflow() {
        // [0xFF] +1 → [0x00]，进位耗尽返回 false
        byte[] b = {(byte) 0xFF};
        assertFalse(ByteUtil.increment(b));
        assertArrayEquals(new byte[]{0}, b);
    }

    @Test
    void incrementCarriesAcrossBytes() {
        // {0x00, 0xFF} + 1 → {0x01, 0x00}：低位回绕产生进位，高位吸收
        byte[] b = {0, (byte) 0xFF};
        assertTrue(ByteUtil.increment(b));
        assertArrayEquals(new byte[]{1, 0}, b);
        // 无进位：低位 0x01→0x02，高位不动
        byte[] noCarry = {(byte) 0xFF, 0x01};
        assertTrue(ByteUtil.increment(noCarry));
        assertArrayEquals(new byte[]{(byte) 0xFF, 2}, noCarry);
        // 全 FF 的 2 字节：进位溢出到全零 → 返回 false（数组整体回绕）
        byte[] overflow = {(byte) 0xFF, (byte) 0xFF};
        assertFalse(ByteUtil.increment(overflow));
        assertArrayEquals(new byte[]{0, 0}, overflow);
    }

    // ===== copyToArray（:492） =====

    @Test
    void copyToArrayLeftPadsTo32Bytes() {
        byte[] out = ByteUtil.copyToArray(BigInteger.ONE);
        assertEquals(32, out.length);
        assertEquals(1, out[31]);
        assertEquals(0, out[0]);
    }

    // ===== setBit / getBit（:499/518） =====

    @Test
    void setAndGetBitRoundTrip() {
        byte[] data = new byte[4];
        ByteUtil.setBit(data, 0, 1);
        assertEquals(1, ByteUtil.getBit(data, 0));
        ByteUtil.setBit(data, 0, 0);
        assertEquals(0, ByteUtil.getBit(data, 0));
        // pos 在最后字节最高位（31）
        ByteUtil.setBit(data, 31, 1);
        assertEquals(1, ByteUtil.getBit(data, 31));
        assertEquals(0, ByteUtil.getBit(data, 30));
    }

    @Test
    void setBitRejectsPositionBeyondArray() {
        assertThrows(Error.class, () -> ByteUtil.setBit(new byte[4], 32, 1));
        assertThrows(Error.class, () -> ByteUtil.getBit(new byte[4], 32));
    }

    // ===== and / or / xor / xorAlignRight（:529/538/547/559） =====

    @Test
    void bitwiseOpsRequireEqualLength() {
        assertThrows(RuntimeException.class, () -> ByteUtil.and(new byte[1], new byte[2]));
        assertThrows(RuntimeException.class, () -> ByteUtil.or(new byte[1], new byte[2]));
        assertThrows(RuntimeException.class, () -> ByteUtil.xor(new byte[1], new byte[2]));
    }

    @Test
    void bitwiseOpsComputePerByte() {
        byte[] a = {0x0F, (byte) 0xF0};
        byte[] b = {0x03, (byte) 0xFF};
        assertArrayEquals(new byte[]{0x03, (byte) 0xF0}, ByteUtil.and(a, b));
        assertArrayEquals(new byte[]{0x0F, (byte) 0xFF}, ByteUtil.or(a, b));
        assertArrayEquals(new byte[]{0x0C, 0x0F}, ByteUtil.xor(a, b));
    }

    @Test
    void xorAlignRightPadsShorterOperand() {
        // 1 字节 0x01 ^ 2 字节 [0x00,0x01] → [0x00,0x00]
        assertArrayEquals(
                new byte[]{0, 0},
                ByteUtil.xorAlignRight(new byte[]{1}, new byte[]{0, 1}));
        // [0x0F] ^ [0x00,0x0F] → [0x00,0x00]
        assertArrayEquals(
                new byte[]{0, 0},
                ByteUtil.xorAlignRight(new byte[]{0, 0x0F}, new byte[]{0x0F}));
    }

    // ===== merge / byteMerger（:577/601） =====

    @Test
    void mergeConcatenatesInOrder() {
        assertArrayEquals(
                new byte[]{1, 2, 3, 4},
                ByteUtil.merge(new byte[]{1}, new byte[]{2, 3}, new byte[]{4}));
        assertArrayEquals(new byte[]{}, ByteUtil.merge());
    }

    @Test
    void byteMergerSameAsMergeForTwo() {
        assertArrayEquals(
                new byte[]{1, 2, 3},
                ByteUtil.byteMerger(new byte[]{1}, new byte[]{2, 3}));
    }

    // ===== 空值/单零判断（:616/620） =====

    @Test
    void isNullOrZeroArrayAndIsSingleZero() {
        assertTrue(ByteUtil.isNullOrZeroArray(null));
        assertTrue(ByteUtil.isNullOrZeroArray(new byte[]{}));
        assertFalse(ByteUtil.isNullOrZeroArray(new byte[]{1}));
        assertTrue(ByteUtil.isSingleZero(new byte[]{0}));
        assertFalse(ByteUtil.isSingleZero(new byte[]{1}));
        assertFalse(ByteUtil.isSingleZero(new byte[]{0, 0}));
    }

    // ===== difference（:625） =====

    @Test
    void differenceKeepsOnlyElementsNotInSecondSet() {
        Set<byte[]> a = new HashSet<>(Arrays.asList(new byte[]{1}, new byte[]{2}));
        Set<byte[]> b = new HashSet<>(Arrays.asList(new byte[]{2}, new byte[]{3}));
        Set<byte[]> out = ByteUtil.difference(a, b);
        assertEquals(1, out.size());
        assertArrayEquals(new byte[]{1}, out.iterator().next());
    }

    // ===== length（:644） =====
    // 语义：求和时跳过 null **元素**；varargs 本身传 null 数组则 NPE（源码无防护——记录现状）。
    @Test
    void lengthSumsSkippingNullElements() {
        assertEquals(3, ByteUtil.length(new byte[]{1, 2}, null, new byte[]{3}));
        assertEquals(0, ByteUtil.length());
        assertThrows(NullPointerException.class, () -> ByteUtil.length((byte[][]) null));
    }

    // ===== intsToBytes / bytesToInts 往返（:652-706） =====

    @Test
    void intsBytesRoundTripBothEndian() {
        int[] src = {0x01020304, -1};
        for (boolean bigEndian : new boolean[]{true, false}) {
            byte[] enc = ByteUtil.intsToBytes(src, bigEndian);
            assertEquals(8, enc.length);
            int[] back = ByteUtil.bytesToInts(enc, bigEndian);
            assertArrayEquals(src, back, "round-trip must preserve values, endian=" + bigEndian);
        }
    }

    // ===== bigEndianToShort / shortToBytes（:708/719） =====

    @Test
    void shortConversionsRoundTrip() {
        short v = (short) 0x7F01;
        assertArrayEquals(new byte[]{0x7F, 0x01}, ByteUtil.shortToBytes(v));
        assertEquals(v, ByteUtil.bigEndianToShort(ByteUtil.shortToBytes(v)));
        assertEquals(v, ByteUtil.bigEndianToShort(new byte[]{0, 0x7F, 0x01}, 1));
    }

    // ===== hexStringToBytes（:731） =====

    @Test
    void hexStringToBytesHandlesPrefixAndOddLength() {
        assertArrayEquals(new byte[]{0x0A, 0x0B}, ByteUtil.hexStringToBytes("0x0a0b"));
        assertArrayEquals(new byte[]{0x0A, 0x0B}, ByteUtil.hexStringToBytes("a0b"));
        assertArrayEquals(new byte[]{}, ByteUtil.hexStringToBytes(null));
    }

    // ===== hostToBytes / bytesToIp（:741/755） =====

    @Test
    void ipConversionRoundTrip() {
        byte[] ip = ByteUtil.hostToBytes("127.0.0.1");
        assertEquals(4, ip.length);
        assertEquals("127.0.0.1", ByteUtil.bytesToIp(ip));
        // 未知主机 → 0.0.0.0 兜底
        assertArrayEquals(new byte[4], ByteUtil.hostToBytes("no.such.host.invalid"));
    }

    // ===== numberOfLeadingZeros（:774） =====
    // 语义：把整个数组当大端无符号整数，统计最高有效位之前的零位数。
    // {0,0,0,1} = 32 位值 1 → 前导零 31（firstNonZero=3 字节*8 + byte 内 7）
    @Test
    void numberOfLeadingZerosBigEndianSemantics() {
        assertEquals(31, ByteUtil.numberOfLeadingZeros(new byte[]{0, 0, 0, 1}));
        assertEquals(0, ByteUtil.numberOfLeadingZeros(new byte[]{(byte) 0x80, 0}));
        assertEquals(7, ByteUtil.numberOfLeadingZeros(new byte[]{1}));
        assertEquals(16, ByteUtil.numberOfLeadingZeros(new byte[2]));
    }

    // ===== parseBytes / parseWord（:791/808/820） =====

    @Test
    void parseBytesRightPadsBeyondInput() {
        assertArrayEquals(new byte[]{1, 2}, ByteUtil.parseBytes(new byte[]{1, 2, 3}, 0, 2));
        assertArrayEquals(
                new byte[]{3, 0, 0},
                ByteUtil.parseBytes(new byte[]{1, 2, 3}, 2, 3));
        assertArrayEquals(new byte[]{}, ByteUtil.parseBytes(new byte[]{1}, 5, 3));
        assertArrayEquals(new byte[]{}, ByteUtil.parseBytes(new byte[]{1}, 0, 0));
    }

    @Test
    void parseWordIndexes32ByteSlots() {
        byte[] input = new byte[40];
        input[32] = 7;
        byte[] w1 = ByteUtil.parseWord(input, 1);
        assertEquals(32, w1.length);
        assertEquals(7, w1[0]);
        // offset 变体：从 offset 起算第 idx 个 32 字节槽
        byte[] w = ByteUtil.parseWord(input, 32, 0);
        assertEquals(7, w[0]);
    }
}
