package org.nexus.l2.zk.r1cs;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link R1csConstraint} 单元测试。
 */
class R1csConstraintTest {

    @Test
    void multiplicationCreatesSimpleConstraint() {
        // x * y = z，w = [1, x, y, z]
        R1csConstraint c = R1csConstraint.multiplication(1, 2, 3);
        assertEquals(1L, c.getA().get(1));
        assertEquals(1L, c.getB().get(2));
        assertEquals(1L, c.getC().get(3));
    }

    @Test
    void multiplicationIsSatisfiedWhenValid() {
        // x * y = z, x=3, y=4, z=12
        R1csConstraint c = R1csConstraint.multiplication(1, 2, 3);
        long[] w = {1, 3, 4, 12};
        assertTrue(c.isSatisfied(w));
    }

    @Test
    void multiplicationNotSatisfiedWhenInvalid() {
        R1csConstraint c = R1csConstraint.multiplication(1, 2, 3);
        long[] w = {1, 3, 4, 13}; // 3*4 != 13
        assertFalse(c.isSatisfied(w));
    }

    @Test
    void constantCreatesVarEqualsConstant() {
        // var = 5
        R1csConstraint c = R1csConstraint.constant(1, 5);
        // (1) * (var) = (5 * 1) → var = 5
        assertEquals(1L, c.getA().get(0));
        assertEquals(1L, c.getB().get(1));
        assertEquals(5L, c.getC().get(0));
    }

    @Test
    void constantIsSatisfiedWhenVarMatches() {
        R1csConstraint c = R1csConstraint.constant(1, 7);
        long[] w = {1, 7};
        assertTrue(c.isSatisfied(w));
    }

    @Test
    void constantNotSatisfiedWhenVarDiffers() {
        R1csConstraint c = R1csConstraint.constant(1, 7);
        long[] w = {1, 8};
        assertFalse(c.isSatisfied(w));
    }

    @Test
    void linearCreatesSumEqualsZero() {
        // 2*x + 3*y = 0
        Map<Integer, Long> coeffs = new HashMap<>();
        coeffs.put(1, 2L);
        coeffs.put(2, 3L);
        R1csConstraint c = R1csConstraint.linear(coeffs);
        // A = coeffs, B = {0:1}, C = {}
        assertEquals(2L, c.getA().get(1));
        assertEquals(3L, c.getA().get(2));
        assertEquals(1L, c.getB().get(0));
        assertTrue(c.getC().isEmpty());
    }

    @Test
    void linearIsSatisfiedWhenSumZero() {
        Map<Integer, Long> coeffs = new HashMap<>();
        coeffs.put(1, 2L);
        coeffs.put(2, 3L);
        R1csConstraint c = R1csConstraint.linear(coeffs);
        // 2*3 + 3*(-2) = 0
        long[] w = {1, 3, -2};
        assertTrue(c.isSatisfied(w));
    }

    @Test
    void linearNotSatisfiedWhenSumNonZero() {
        Map<Integer, Long> coeffs = new HashMap<>();
        coeffs.put(1, 2L);
        R1csConstraint c = R1csConstraint.linear(coeffs);
        long[] w = {1, 5}; // 2*5 = 10 != 0
        assertFalse(c.isSatisfied(w));
    }

    @Test
    void nullMapsBecomeEmpty() {
        R1csConstraint c = new R1csConstraint(null, null, null);
        assertTrue(c.getA().isEmpty());
        assertTrue(c.getB().isEmpty());
        assertTrue(c.getC().isEmpty());
        // 空约束: 0 * 0 = 0 → 满足
        assertTrue(c.isSatisfied(new long[]{1}));
    }

    @Test
    void mapsAreUnmodifiable() {
        R1csConstraint c = R1csConstraint.multiplication(1, 2, 3);
        assertThrows(UnsupportedOperationException.class, () ->
                c.getA().put(99, 1L));
    }

    @Test
    void toStringContainsABC() {
        R1csConstraint c = R1csConstraint.multiplication(1, 2, 3);
        String s = c.toString();
        assertTrue(s.contains("R1csConstraint"));
        assertTrue(s.contains("A="));
        assertTrue(s.contains("B="));
        assertTrue(s.contains("C="));
    }

    @Test
    void equalsAndHashCode() {
        R1csConstraint a = R1csConstraint.multiplication(1, 2, 3);
        R1csConstraint b = R1csConstraint.multiplication(1, 2, 3);
        R1csConstraint c = R1csConstraint.multiplication(1, 2, 4);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertEquals(a, a);
        assertNotEquals(a, null);
        assertNotEquals(a, "string");
    }

    @Test
    void dotIgnoresOutOfRangeIndices() {
        // 约束引用 index 5，但 witness 只有 2 个元素
        R1csConstraint c = R1csConstraint.multiplication(5, 6, 7);
        // 不应抛异常；越界 index 被忽略
        long[] w = {1, 0};
        assertTrue(c.isSatisfied(w)); // 0*0 = 0
    }
}