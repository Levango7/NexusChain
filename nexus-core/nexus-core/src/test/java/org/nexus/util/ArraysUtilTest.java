package org.nexus.util;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Arrays 纯逻辑单测（A 项覆盖率提升）。
 * 断言基于源码语义逐条核对（Arrays.java:36-1116）：BouncyCastle 风格的
 * 257 混合 hashCode、null 契约（hashCode→0 / clone→null / concatenate→跳过）、
 * copyOfRange 右侧零填充、Iterator 行为。
 */
class ArraysUtilTest {

    // ===== areAllZeroes（:36） =====

    @Test
    void areAllZeroesRespectsOffsetAndLength() {
        byte[] buf = {0, 0, 1, 0};
        assertTrue(Arrays.areAllZeroes(buf, 0, 2));
        assertFalse(Arrays.areAllZeroes(buf, 0, 3));
        assertTrue(Arrays.areAllZeroes(buf, 2, 0), "len=0 恒为 true");
    }

    // ===== areEqual 全重载（:46-294） =====

    @Test
    void areEqualBooleanArrays() {
        assertTrue(Arrays.areEqual(new boolean[]{true, false}, new boolean[]{true, false}));
        assertFalse(Arrays.areEqual(new boolean[]{true}, new boolean[]{false}));
        assertFalse(Arrays.areEqual((boolean[]) null, new boolean[0]));
        assertTrue(Arrays.areEqual((boolean[]) null, null));
    }

    @Test
    void areEqualCharAndLongArrays() {
        assertTrue(Arrays.areEqual(new char[]{'a'}, new char[]{'a'}));
        assertTrue(Arrays.areEqual(new long[]{1L}, new long[]{1L}));
        assertFalse(Arrays.areEqual(new long[]{1L}, new long[]{2L}));
    }

    @Test
    void areEqualIntArrays() {
        assertTrue(Arrays.areEqual(new int[]{1, 2}, new int[]{1, 2}));
        assertFalse(Arrays.areEqual(new int[]{1, 2}, new int[]{1}));
        assertFalse(Arrays.areEqual(new int[]{1}, (int[]) null));
    }

    @Test
    void areEqualObjectArraysUsesEqualsWithNullElements() {
        assertTrue(Arrays.areEqual(new String[]{"a", null}, new String[]{"a", null}));
        assertFalse(Arrays.areEqual(new String[]{"a", null}, new String[]{"a", "b"}));
        assertFalse(Arrays.areEqual(new String[]{"a"}, new String[]{"b"}));
    }

    // ===== constantTimeAreEqual（:175） =====

    @Test
    void constantTimeAreEqualComparesContent() {
        byte[] a = {1, 2, 3};
        assertTrue(Arrays.constantTimeAreEqual(a, a.clone()));
        assertFalse(Arrays.constantTimeAreEqual(a, new byte[]{1, 2, 4}));
        assertFalse(Arrays.constantTimeAreEqual((byte[]) null, a));
        assertTrue(Arrays.constantTimeAreEqual((byte[]) null, null));
        // 长度不等路径：:191 递归自比较恒 false
        assertFalse(Arrays.constantTimeAreEqual(new byte[]{1}, new byte[]{1, 1}));
    }

    // ===== compareUnsigned（:296） =====

    @Test
    void compareUnsignedByteOrder() {
        assertEquals(0, Arrays.compareUnsigned(new byte[]{1, 2}, new byte[]{1, 2}));
        // 无符号语义：0xFF(255) > 0x01
        assertEquals(1, Arrays.compareUnsigned(new byte[]{(byte) 0xFF}, new byte[]{0x01}));
        assertEquals(-1, Arrays.compareUnsigned(new byte[]{0x01}, new byte[]{(byte) 0xFF}));
        // 前缀相同，短者小
        assertEquals(-1, Arrays.compareUnsigned(new byte[]{1}, new byte[]{1, 2}));
        assertEquals(1, Arrays.compareUnsigned(new byte[]{1, 2}, new byte[]{1}));
        // null 契约（:302/306）
        assertEquals(-1, Arrays.compareUnsigned((byte[]) null, new byte[]{1}));
        assertEquals(1, Arrays.compareUnsigned(new byte[]{1}, (byte[]) null));
        assertEquals(0, Arrays.compareUnsigned((byte[]) null, null));
    }

    // ===== contains（:334/346） =====

    @Test
    void containsShortAndInt() {
        assertTrue(Arrays.contains(new short[]{1, 2}, (short) 2));
        assertFalse(Arrays.contains(new short[]{1}, (short) 3));
        assertTrue(Arrays.contains(new int[]{5}, 5));
        assertFalse(Arrays.contains(new int[]{5}, 6));
    }

    // ===== fill 全重载（:358-474） =====

    @Test
    void fillOverwritesWholeArray() {
        byte[] b = new byte[3];
        Arrays.fill(b, (byte) 7);
        assertArrayEquals(new byte[]{7, 7, 7}, b);

        char[] c = new char[2];
        Arrays.fill(c, 'x');
        assertArrayEquals(new char[]{'x', 'x'}, c);

        long[] l = new long[2];
        Arrays.fill(l, 9L);
        assertArrayEquals(new long[]{9, 9}, l);

        short[] s = new short[2];
        Arrays.fill(s, (short) 4);
        assertArrayEquals(new short[]{4, 4}, s);

        int[] i = new int[2];
        Arrays.fill(i, 6);
        assertArrayEquals(new int[]{6, 6}, i);
    }

    @Test
    void fillRangeLeavesOutsideIntact() {
        byte[] b = {1, 1, 1, 1};
        Arrays.fill(b, 1, 3, (byte) 9); // [start, finish)
        assertArrayEquals(new byte[]{1, 9, 9, 1}, b);
    }

    @Test
    void fillFromOutIndexVariants() {
        byte[] b = {1, 2, 3};
        Arrays.fill(b, 1, (byte) 0);
        assertArrayEquals(new byte[]{1, 0, 0}, b);

        int[] ints = {1, 2, 3};
        Arrays.fill(ints, 2, 0);
        assertArrayEquals(new int[]{1, 2, 0}, ints);

        short[] shorts = {1, 2, 3};
        Arrays.fill(shorts, 0, (short) 0);
        assertArrayEquals(new short[]{0, 0, 0}, shorts);

        long[] longs = {1, 2};
        Arrays.fill(longs, 5, 0L); // out >= length：不改动（:425 守卫）
        assertArrayEquals(new long[]{1, 2}, longs);
    }

    // ===== hashCode 家族（:476-687）——257 混合、null→0 =====

    @Test
    void hashCodeNullReturnsZero() {
        assertEquals(0, Arrays.hashCode((byte[]) null));
        assertEquals(0, Arrays.hashCode((char[]) null));
        assertEquals(0, Arrays.hashCode((int[]) null));
        assertEquals(0, Arrays.hashCode((long[]) null));
        assertEquals(0, Arrays.hashCode((short[]) null));
        assertEquals(0, Arrays.hashCode((Object[]) null));
    }

    @Test
    void hashCodeIsDeterministicAndOrderSensitive() {
        byte[] x = {1, 2};
        assertEquals(Arrays.hashCode(x), Arrays.hashCode(x.clone()));
        assertNotEquals(Arrays.hashCode(new byte[]{1, 2}), Arrays.hashCode(new byte[]{2, 1}));
        // 区间重载与全量在 [0,len) 上等价
        assertEquals(Arrays.hashCode(x), Arrays.hashCode(x, 0, x.length));
        // 后缀区间独立计算
        assertNotEquals(Arrays.hashCode(x, 0, 1), Arrays.hashCode(x, 0, 2));
    }

    @Test
    void hashCodeNestedAndTypedVariants() {
        // int[][]：行级 hashCode 再 257 混合；空二维数组循环不执行 → 0（源码 :535 无初值偏移）
        int[][] m = {{1}, {2}};
        assertEquals(Arrays.hashCode(m), Arrays.hashCode(new int[][]{{1}, {2}}));
        assertEquals(0, Arrays.hashCode(new int[0][]));
        // int[] 区间、long[]、short[]、Object[]
        assertNotEquals(0, Arrays.hashCode(new int[]{5}, 0, 1));
        assertNotEquals(0, Arrays.hashCode(new long[]{5L}));
        assertNotEquals(0, Arrays.hashCode(new long[]{5L}, 0, 1));
        assertNotEquals(0, Arrays.hashCode(new short[]{5}));
        assertNotEquals(0, Arrays.hashCode(new short[][]{{5}}));
        assertNotEquals(0, Arrays.hashCode(new short[][][]{{{5}}}));
        assertNotEquals(0, Arrays.hashCode(new Object[]{"a"}));
    }

    // ===== clone 全重载（:689-827）——null→null，浅层值拷贝 =====

    @Test
    void cloneNullPassesThrough() {
        assertNull(Arrays.clone((byte[]) null));
        assertNull(Arrays.clone((char[]) null));
        assertNull(Arrays.clone((byte[][]) null));
        assertNull(Arrays.clone((byte[][][]) null));
        assertNull(Arrays.clone((int[]) null));
        assertNull(Arrays.clone((long[]) null));
        assertNull(Arrays.clone((long[]) null, new long[1]));
        assertNull(Arrays.clone((short[]) null));
        assertNull(Arrays.clone((BigInteger[]) null));
        assertNull(Arrays.clone((byte[]) null, (byte[]) null));
    }

    @Test
    void cloneProducesDeepIndependentCopy() {
        byte[] src = {1, 2};
        byte[] out = Arrays.clone(src);
        assertNotSame(src, out);
        assertArrayEquals(src, out);
        out[0] = 99;
        assertEquals(1, src[0], "byte[] clone 必须独立");

        char[] c = {'a'};
        assertArrayEquals(c, Arrays.clone(c));

        int[] ints = {1};
        assertArrayEquals(ints, Arrays.clone(ints));

        long[] longs = {1L};
        assertArrayEquals(longs, Arrays.clone(longs));

        short[] shorts = {1};
        assertArrayEquals(shorts, Arrays.clone(shorts));

        BigInteger[] bigs = {BigInteger.ONE};
        assertArrayEquals(bigs, Arrays.clone(bigs));
    }

    @Test
    void cloneTwoDimDeepCopiesRows() {
        byte[][] src = {{1}, {2}};
        byte[][] out = Arrays.clone(src);
        assertNotSame(src[0], out[0]);
        out[0][0] = 9;
        assertEquals(1, src[0][0]);

        byte[][][] src3 = {{{1}}};
        byte[][][] out3 = Arrays.clone(src3);
        assertNotSame(src3[0], out3[0]);
        assertNotSame(src3[0][0], out3[0][0]);
    }

    @Test
    void cloneWithExistingBufferReusesWhenLengthMatches() {
        byte[] src = {1, 2};
        byte[] existing = new byte[2];
        byte[] out = Arrays.clone(src, existing);
        assertSame(existing, out, "等长 existing 应被复用");
        assertArrayEquals(src, existing);
        // 长度不等 → 新分配
        byte[] wrong = new byte[3];
        byte[] out2 = Arrays.clone(src, wrong);
        assertNotSame(wrong, out2);
        assertArrayEquals(src, out2);
        // long 变体同契约
        long[] lsrc = {5L};
        long[] lex = new long[1];
        assertSame(lex, Arrays.clone(lsrc, lex));
    }

    // ===== copyOf（:829-907）——截断或右侧补零 =====

    @Test
    void copyOfTruncatesOrPads() {
        byte[] src = {1, 2, 3};
        assertArrayEquals(new byte[]{1, 2}, Arrays.copyOf(src, 2));
        assertArrayEquals(new byte[]{1, 2, 3, 0, 0}, Arrays.copyOf(src, 5));
        assertArrayEquals(new char[]{'a', 0}, Arrays.copyOf(new char[]{'a'}, 2));
        assertArrayEquals(new int[]{1, 0}, Arrays.copyOf(new int[]{1}, 2));
        assertArrayEquals(new long[]{1, 0}, Arrays.copyOf(new long[]{1}, 2));
        assertArrayEquals(
                new BigInteger[]{BigInteger.ONE, null},
                Arrays.copyOf(new BigInteger[]{BigInteger.ONE}, 2));
    }

    // ===== copyOfRange（:920-1002） =====

    @Test
    void copyOfRangePadsWithZeroesBeyondEnd() {
        byte[] src = {1, 2, 3};
        assertArrayEquals(new byte[]{2, 3}, Arrays.copyOfRange(src, 1, 3));
        assertArrayEquals(new byte[]{3, 0, 0}, Arrays.copyOfRange(src, 2, 5));
        assertArrayEquals(new int[]{3, 0}, Arrays.copyOfRange(new int[]{1, 2, 3}, 2, 4));
        assertArrayEquals(new long[]{2, 3}, Arrays.copyOfRange(new long[]{1, 2, 3}, 1, 3));
        assertArrayEquals(
                new BigInteger[]{BigInteger.valueOf(2)},
                Arrays.copyOfRange(new BigInteger[]{BigInteger.ONE, BigInteger.valueOf(2)}, 1, 2));
        // from > to → IllegalArgumentException（:999）
        assertThrows(IllegalArgumentException.class, () -> Arrays.copyOfRange(src, 2, 1));
    }

    // ===== append（:1004-1058） =====

    @Test
    void appendNullArrayReturnsSingleElement() {
        assertArrayEquals(new byte[]{5}, Arrays.append((byte[]) null, (byte) 5));
        assertArrayEquals(new short[]{5}, Arrays.append((short[]) null, (short) 5));
        assertArrayEquals(new int[]{5}, Arrays.append((int[]) null, 5));
        assertArrayEquals(new String[]{"x"}, Arrays.append((String[]) null, "x"));
    }

    @Test
    void appendAddsAtEnd() {
        assertArrayEquals(new byte[]{1, 2}, Arrays.append(new byte[]{1}, (byte) 2));
        assertArrayEquals(new short[]{1, 2}, Arrays.append(new short[]{1}, (short) 2));
        assertArrayEquals(new int[]{1, 2}, Arrays.append(new int[]{1}, 2));
        assertArrayEquals(new String[]{"a", "b"}, Arrays.append(new String[]{"a"}, "b"));
    }

    // ===== concatenate（:1062-…） =====

    @Test
    void concatenateTwoHandlesNullSides() {
        assertArrayEquals(new byte[]{1, 2, 3}, Arrays.concatenate(new byte[]{1}, new byte[]{2, 3}));
        // b null → clone(a)；a null → clone(b)
        assertArrayEquals(new byte[]{1}, Arrays.concatenate(new byte[]{1}, (byte[]) null));
        assertArrayEquals(new byte[]{2}, Arrays.concatenate(null, new byte[]{2}));
        // 双 null → clone(null) = null
        assertNull(Arrays.concatenate((byte[]) null, null));
    }

    @Test
    void concatenateThreeAndFourSkipNulls() {
        assertArrayEquals(new byte[]{1, 2, 3}, Arrays.concatenate(new byte[]{1}, new byte[]{2}, new byte[]{3}));
        assertArrayEquals(new byte[]{1, 3}, Arrays.concatenate(new byte[]{1}, null, new byte[]{3}));
        assertArrayEquals(new byte[]{2, 3}, Arrays.concatenate(null, new byte[]{2}, new byte[]{3}));
        assertArrayEquals(new byte[]{1, 2}, Arrays.concatenate(new byte[]{1}, new byte[]{2}, (byte[]) null));
        assertArrayEquals(
                new byte[]{1, 2, 3, 4},
                Arrays.concatenate(new byte[]{1}, new byte[]{2}, new byte[]{3}, new byte[]{4}));
    }

    @Test
    void concatenateIntArrays() {
        assertArrayEquals(new int[]{1, 2}, Arrays.concatenate(new int[]{1}, new int[]{2}));
    }

    @Test
    void concatenateArrayOfArraysJoinsAll() {
        byte[][] parts = {{1}, {2, 3}, {}};
        assertArrayEquals(new byte[]{1, 2, 3}, Arrays.concatenate(parts));
    }

    // ===== prepend（:1149 前后） =====

    @Test
    void prependPutsElementFirst() {
        assertArrayEquals(new byte[]{9, 1}, Arrays.prepend(new byte[]{1}, (byte) 9));
        assertArrayEquals(new short[]{9, 1}, Arrays.prepend(new short[]{1}, (short) 9));
        assertArrayEquals(new int[]{9, 1}, Arrays.prepend(new int[]{1}, 9));
    }

    // ===== reverse（:…） =====

    @Test
    void reverseReturnsNewArrayNullSafe() {
        // 源码 :1227——返回新数组（非就地），输入不变
        byte[] b = {1, 2, 3};
        byte[] out = Arrays.reverse(b);
        assertNotSame(b, out);
        assertArrayEquals(new byte[]{3, 2, 1}, out);
        assertArrayEquals(new byte[]{1, 2, 3}, b, "输入数组必须保持不变");
        assertNull(Arrays.reverse((byte[]) null));
        assertArrayEquals(new int[]{2, 1}, Arrays.reverse(new int[]{1, 2}));
        assertNull(Arrays.reverse((int[]) null));
    }

    // ===== Iterator 内部类（:1266）——java.util.Iterator 接口 =====

    @Test
    void iteratorWalksArrayWithoutMutation() {
        Byte[] data = {1, 2};
        Arrays.Iterator<Byte> it = new Arrays.Iterator<>(data);
        assertTrue(it.hasNext());
        assertEquals((byte) 1, it.next());
        assertTrue(it.hasNext());
        assertEquals((byte) 2, it.next());
        assertFalse(it.hasNext());
        // 迭代不修改底层数组（与 clear 不同）
        assertArrayEquals(new Byte[]{1, 2}, data);
        assertThrows(java.util.NoSuchElementException.class, it::next);
        // remove 显式不支持（:1301 契约）
        Arrays.Iterator<Byte> it2 = new Arrays.Iterator<>(new Byte[]{1});
        assertThrows(UnsupportedOperationException.class, it2::remove);
    }

    // ===== clear（:…） =====

    @Test
    void clearZeroesArray() {
        byte[] b = {1, 2};
        Arrays.clear(b);
        assertArrayEquals(new byte[]{0, 0}, b);
    }
}
