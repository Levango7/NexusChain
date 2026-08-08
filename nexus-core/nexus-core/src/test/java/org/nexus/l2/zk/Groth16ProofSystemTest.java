package org.nexus.l2.zk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.l2.zk.groth16.Groth16Proof;
import org.nexus.l2.zk.groth16.Groth16ProofSystem;
import org.nexus.l2.zk.groth16.Groth16Setup;
import org.nexus.l2.zk.r1cs.R1csConstraint;
import org.nexus.l2.zk.r1cs.R1csConstraintSystem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Groth16 证明系统单元测试。
 *
 * <p>验证 {@link Groth16ProofSystem} 的完整流程：
 * setup → prove → verify，以及 R1CS 约束系统的正确性。</p>
 *
 * @since 1.5
 */
public class Groth16ProofSystemTest {

    private Groth16ProofSystem proofSystem;

    @BeforeEach
    public void setUp() {
        proofSystem = new Groth16ProofSystem();
    }

    /**
     * 测试简单乘法电路：x * y = z。
     *
     * <p>witness = [1, x=3, y=4, z=12]
     * 约束：x * y = z</p>
     */
    @Test
    public void testMultiplicationCircuit() {
        // 构造 R1CS: x * y = z
        // witness: [1, x, y, z] → numPublic=1 (z), numPrivate=2 (x, y)
        // 实际上让所有都私密：numPublic=0, numPrivate=3 (x, y, z)
        List<R1csConstraint> constraints = new ArrayList<>();
        // x * y = z → w[1] * w[2] = w[3]
        constraints.add(R1csConstraint.multiplication(1, 2, 3));

        R1csConstraintSystem r1cs = new R1csConstraintSystem(0, 3, constraints);
        assertEquals(4, r1cs.getWitnessSize());

        // setup
        Groth16Setup setup = proofSystem.setup("mul-test", r1cs);
        assertNotNull(setup);
        assertEquals(1, setup.getConstraintCount());

        // prove: x=3, y=4, z=12
        long[] witness = {1, 3, 4, 12};
        assertTrue(r1cs.isSatisfied(witness), "witness should satisfy R1CS");

        Groth16Proof proof = proofSystem.prove("mul-test", r1cs, witness);
        assertNotNull(proof);
        assertNotNull(proof.getA());
        assertNotNull(proof.getB());
        assertNotNull(proof.getC());

        // verify
        boolean valid = proofSystem.verify("mul-test", proof, new long[0]);
        assertTrue(valid, "proof should verify");
    }

    /**
     * 测试 RollupStateTransitionCircuit 的 R1CS 构建和 Groth16 流程。
     */
    @Test
    public void testRollupStateTransitionCircuit() {
        RollupStateTransitionCircuit circuit = new RollupStateTransitionCircuit(10);
        assertTrue(circuit.hasR1cs(), "circuit should have R1CS");

        R1csConstraintSystem r1cs = circuit.buildR1cs();
        assertNotNull(r1cs);
        assertEquals(3, r1cs.getNumPublic());
        assertEquals(11, r1cs.getNumPrivate()); // 10 txEffects + 1 sumEffects
        assertEquals(15, r1cs.getWitnessSize()); // 1 + 3 + 11

        // 构造合法 witness: preState=100, postState=150, batchHash=999
        // txEffects = [10, 20, 20] (sum=50), postState - preState = 50
        long[] witness = circuit.buildWitness(100, 150, 999, new long[]{10, 20, 20});
        assertEquals(15, witness.length);
        assertTrue(r1cs.isSatisfied(witness), "witness should satisfy R1CS");

        // setup + prove + verify
        proofSystem.setup("rollup-test", r1cs);
        Groth16Proof proof = proofSystem.prove("rollup-test", r1cs, witness);
        assertNotNull(proof);

        long[] publicInputs = {100, 150, 999};
        boolean valid = proofSystem.verify("rollup-test", proof, publicInputs);
        assertTrue(valid, "rollup proof should verify");
    }

    /**
     * 测试非法 witness 被拒绝。
     */
    @Test
    public void testInvalidWitnessRejected() {
        RollupStateTransitionCircuit circuit = new RollupStateTransitionCircuit(5);
        R1csConstraintSystem r1cs = circuit.buildR1cs();

        // 构造非法 witness: preState=100, postState=200, 但 txEffects sum=50 (不匹配)
        long[] witness = circuit.buildWitness(100, 200, 999, new long[]{10, 20, 20});
        assertFalse(r1cs.isSatisfied(witness), "invalid witness should not satisfy R1CS");

        proofSystem.setup("invalid-test", r1cs);
        try {
            proofSystem.prove("invalid-test", r1cs, witness);
            fail("prove should reject invalid witness");
        } catch (IllegalArgumentException e) {
            // expected
            assertTrue(e.getMessage().contains("does not satisfy"));
        }
    }

    /**
     * 测试 R1csConstraint 的工厂方法。
     */
    @Test
    public void testR1csConstraintFactories() {
        // multiplication: w[1] * w[2] = w[3]
        R1csConstraint mul = R1csConstraint.multiplication(1, 2, 3);
        long[] w = {1, 3, 4, 12};
        assertTrue(mul.isSatisfied(w));
        long[] wBad = {1, 3, 4, 13};
        assertFalse(mul.isSatisfied(wBad));

        // constant: w[1] = 5
        R1csConstraint con = R1csConstraint.constant(1, 5);
        assertTrue(con.isSatisfied(new long[]{1, 5}));
        assertFalse(con.isSatisfied(new long[]{1, 6}));

        // linear: w[1] + w[2] - w[3] = 0
        Map<Integer, Long> lin = new HashMap<>();
        lin.put(1, 1L);
        lin.put(2, 1L);
        lin.put(3, -1L);
        R1csConstraint linear = R1csConstraint.linear(lin);
        assertTrue(linear.isSatisfied(new long[]{1, 3, 4, 7}));
        assertFalse(linear.isSatisfied(new long[]{1, 3, 4, 8}));
    }

    /**
     * 测试 DefaultZkProofSystem 的 witness 编码/解码。
     */
    @Test
    public void testWitnessEncoding() {
        long[] txEffects = {10, 20, 30, 40};
        byte[] encoded = DefaultZkProofSystem.encodeWitness(txEffects);
        assertNotNull(encoded);
        assertTrue(encoded.length > 8, "encoded witness should be non-trivial");

        // 编码应包含 magic "ZWIT"
        assertEquals('Z', encoded[0]);
        assertEquals('W', encoded[1]);
        assertEquals('I', encoded[2]);
        assertEquals('T', encoded[3]);
    }

    /**
     * 测试 RollupStateTransitionCircuit.hashToLong。
     */
    @Test
    public void testHashToLong() {
        long h1 = RollupStateTransitionCircuit.hashToLong("abc");
        long h2 = RollupStateTransitionCircuit.hashToLong("abc");
        long h3 = RollupStateTransitionCircuit.hashToLong("xyz");
        assertEquals(h1, h2, "same input should produce same hash");
        assertNotEquals(h1, h3, "different input should produce different hash");
        assertEquals(0, RollupStateTransitionCircuit.hashToLong(null), "null input should return 0");
        assertEquals(0, RollupStateTransitionCircuit.hashToLong(""), "empty input should return 0");
    }
}
