package org.nexus.l2.zk.r1cs;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link R1csConstraintSystem} 单元测试。
 */
class R1csConstraintSystemTest {

    @Test
    void gettersReturnValues() {
        R1csConstraint c = R1csConstraint.multiplication(1, 2, 3);
        R1csConstraintSystem sys = new R1csConstraintSystem(2, 1, Collections.singletonList(c));
        assertEquals(2, sys.getNumPublic());
        assertEquals(1, sys.getNumPrivate());
        assertEquals(1, sys.getConstraintCount());
        assertEquals(1, sys.getConstraints().size());
    }

    @Test
    void witnessSizeIsOnePlusPublicPlusPrivate() {
        R1csConstraintSystem sys = new R1csConstraintSystem(3, 4, Collections.emptyList());
        assertEquals(1 + 3 + 4, sys.getWitnessSize());
    }

    @Test
    void negativeNumPublicThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new R1csConstraintSystem(-1, 0, Collections.emptyList()));
    }

    @Test
    void negativeNumPrivateThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new R1csConstraintSystem(0, -1, Collections.emptyList()));
    }

    @Test
    void nullConstraintsBecomesEmpty() {
        R1csConstraintSystem sys = new R1csConstraintSystem(0, 0, null);
        assertEquals(0, sys.getConstraintCount());
        assertTrue(sys.getConstraints().isEmpty());
    }

    @Test
    void constraintsListIsUnmodifiable() {
        R1csConstraint c = R1csConstraint.multiplication(1, 2, 3);
        R1csConstraintSystem sys = new R1csConstraintSystem(0, 3, Collections.singletonList(c));
        assertThrows(UnsupportedOperationException.class, () ->
                sys.getConstraints().add(c));
    }

    @Test
    void isSatisfiedNullReturnsFalse() {
        R1csConstraintSystem sys = new R1csConstraintSystem(0, 0, Collections.emptyList());
        assertFalse(sys.isSatisfied(null));
    }

    @Test
    void isSatisfiedWrongLengthReturnsFalse() {
        R1csConstraintSystem sys = new R1csConstraintSystem(2, 0, Collections.emptyList());
        // witnessSize = 3, 传入长度 2
        assertFalse(sys.isSatisfied(new long[]{1, 0}));
    }

    @Test
    void isSatisfiedFirstElementMustBeOne() {
        R1csConstraintSystem sys = new R1csConstraintSystem(0, 0, Collections.emptyList());
        assertFalse(sys.isSatisfied(new long[]{0}));
        assertTrue(sys.isSatisfied(new long[]{1}));
    }

    @Test
    void isSatisfiedAllConstraints() {
        // x * y = z, z = 12 (constant)
        R1csConstraint mul = R1csConstraint.multiplication(1, 2, 3);
        R1csConstraint con = R1csConstraint.constant(3, 12);
        // w = [1, x=3, y=4, z=12]
        R1csConstraintSystem sys = new R1csConstraintSystem(3, 0, Arrays.asList(mul, con));
        assertTrue(sys.isSatisfied(new long[]{1, 3, 4, 12}));
        assertFalse(sys.isSatisfied(new long[]{1, 3, 4, 13}));
    }

    @Test
    void buildWitnessAssemblesCorrectly() {
        R1csConstraintSystem sys = new R1csConstraintSystem(2, 2, Collections.emptyList());
        long[] pub = {10, 20};
        long[] priv = {30, 40};
        long[] w = sys.buildWitness(pub, priv);
        // [1, 10, 20, 30, 40]
        assertEquals(5, w.length);
        assertEquals(1L, w[0]);
        assertEquals(10L, w[1]);
        assertEquals(20L, w[2]);
        assertEquals(30L, w[3]);
        assertEquals(40L, w[4]);
    }

    @Test
    void buildWitnessPublicLengthMismatchThrows() {
        R1csConstraintSystem sys = new R1csConstraintSystem(2, 0, Collections.emptyList());
        assertThrows(IllegalArgumentException.class, () ->
                sys.buildWitness(new long[]{1}, new long[0]));
    }

    @Test
    void buildWitnessPrivateLengthMismatchThrows() {
        R1csConstraintSystem sys = new R1csConstraintSystem(0, 2, Collections.emptyList());
        assertThrows(IllegalArgumentException.class, () ->
                sys.buildWitness(new long[0], new long[]{1}));
    }

    @Test
    void buildWitnessNullThrows() {
        R1csConstraintSystem sys = new R1csConstraintSystem(0, 0, Collections.emptyList());
        assertThrows(IllegalArgumentException.class, () ->
                sys.buildWitness(null, null));
    }

    @Test
    void toStringContainsKeyInfo() {
        R1csConstraintSystem sys = new R1csConstraintSystem(2, 3,
                Collections.singletonList(R1csConstraint.multiplication(1, 2, 3)));
        String s = sys.toString();
        assertTrue(s.contains("R1csConstraintSystem"));
        assertTrue(s.contains("numPublic=2"));
        assertTrue(s.contains("numPrivate=3"));
        assertTrue(s.contains("constraints=1"));
    }

    @Test
    void emptySystemSatisfiedByOne() {
        R1csConstraintSystem sys = new R1csConstraintSystem(0, 0, Collections.emptyList());
        assertTrue(sys.isSatisfied(new long[]{1L}));
    }

    @Test
    void fullExampleMultiplicationCircuit() {
        // 电路: x * y = z
        // 公共输入: x, y; 私密: z
        // w = [1, x, y, z]
        R1csConstraint mul = R1csConstraint.multiplication(1, 2, 3);
        R1csConstraintSystem sys = new R1csConstraintSystem(2, 1, Collections.singletonList(mul));

        // x=6, y=7, z=42
        long[] w = sys.buildWitness(new long[]{6, 7}, new long[]{42});
        assertTrue(sys.isSatisfied(w));

        // x=6, y=7, z=99 不满足
        long[] bad = sys.buildWitness(new long[]{6, 7}, new long[]{99});
        assertFalse(sys.isSatisfied(bad));
    }
}