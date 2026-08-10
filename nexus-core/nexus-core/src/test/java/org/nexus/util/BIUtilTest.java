package org.nexus.util;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BIUtil} 单元测试。
 */
class BIUtilTest {

    @Test
    void isZero() {
        assertTrue(BIUtil.isZero(BigInteger.ZERO));
        assertFalse(BIUtil.isZero(BigInteger.ONE));
        assertFalse(BIUtil.isZero(BigInteger.valueOf(-1)));
    }

    @Test
    void isEqual() {
        assertTrue(BIUtil.isEqual(BigInteger.TEN, BigInteger.TEN));
        assertFalse(BIUtil.isEqual(BigInteger.ONE, BigInteger.TEN));
    }

    @Test
    void isNotEqual() {
        assertTrue(BIUtil.isNotEqual(BigInteger.ONE, BigInteger.TEN));
        assertFalse(BIUtil.isNotEqual(BigInteger.TEN, BigInteger.TEN));
    }

    @Test
    void isLessThan() {
        assertTrue(BIUtil.isLessThan(BigInteger.ONE, BigInteger.TEN));
        assertFalse(BIUtil.isLessThan(BigInteger.TEN, BigInteger.ONE));
        assertFalse(BIUtil.isLessThan(BigInteger.TEN, BigInteger.TEN));
    }

    @Test
    void isMoreThan() {
        assertTrue(BIUtil.isMoreThan(BigInteger.TEN, BigInteger.ONE));
        assertFalse(BIUtil.isMoreThan(BigInteger.ONE, BigInteger.TEN));
        assertFalse(BIUtil.isMoreThan(BigInteger.TEN, BigInteger.TEN));
    }

    @Test
    void sum() {
        assertEquals(BigInteger.valueOf(15), BIUtil.sum(BigInteger.TEN, BigInteger.valueOf(5)));
        assertEquals(BigInteger.ZERO, BIUtil.sum(BigInteger.ONE, BigInteger.valueOf(-1)));
    }

    @Test
    void toBIFromBytes() {
        byte[] data = new byte[]{1, 2, 3};
        BigInteger bi = BIUtil.toBI(data);
        assertTrue(bi.signum() >= 0);
        assertEquals(new BigInteger(1, data), bi);
    }

    @Test
    void toBIFromLong() {
        assertEquals(BigInteger.valueOf(42L), BIUtil.toBI(42L));
        assertEquals(BigInteger.valueOf(-1L), BIUtil.toBI(-1L));
    }

    @Test
    void isPositive() {
        assertTrue(BIUtil.isPositive(BigInteger.ONE));
        assertFalse(BIUtil.isPositive(BigInteger.ZERO));
        assertFalse(BIUtil.isPositive(BigInteger.valueOf(-1)));
    }

    @Test
    void isCovers() {
        assertTrue(BIUtil.isCovers(BigInteger.TEN, BigInteger.ONE));
        assertFalse(BIUtil.isCovers(BigInteger.ONE, BigInteger.TEN));
    }

    @Test
    void isNotCovers() {
        assertTrue(BIUtil.isNotCovers(BigInteger.ONE, BigInteger.TEN));
        assertFalse(BIUtil.isNotCovers(BigInteger.TEN, BigInteger.ONE));
    }

    @Test
    void exitLong() {
        assertTrue(BIUtil.exitLong(BigInteger.valueOf(Long.MAX_VALUE)));
        assertFalse(BIUtil.exitLong(BigInteger.ZERO));
        assertFalse(BIUtil.exitLong(BigInteger.valueOf(-1)));
    }

    @Test
    void isIn20PercentRange() {
        // 100 + 100/5 = 120, 110 <= 120 → true
        assertTrue(BIUtil.isIn20PercentRange(BigInteger.valueOf(100), BigInteger.valueOf(110)));
        // 100 + 100/5 = 120, 130 > 120 → false
        assertFalse(BIUtil.isIn20PercentRange(BigInteger.valueOf(100), BigInteger.valueOf(130)));
    }

    @Test
    void max() {
        assertEquals(BigInteger.TEN, BIUtil.max(BigInteger.ONE, BigInteger.TEN));
        assertEquals(BigInteger.TEN, BIUtil.max(BigInteger.TEN, BigInteger.ONE));
        assertEquals(BigInteger.TEN, BIUtil.max(BigInteger.TEN, BigInteger.TEN));
    }

    @Test
    void addSafely() {
        assertEquals(15, BIUtil.addSafely(10, 5));
        assertEquals(Integer.MAX_VALUE, BIUtil.addSafely(Integer.MAX_VALUE, 1));
        assertEquals(-1, BIUtil.addSafely(Integer.MAX_VALUE, Integer.MIN_VALUE));
    }
}