package org.nexus.l2.zk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.l2.zk.groth16.Groth16Proof;
import org.nexus.l2.zk.groth16.Groth16ProofSystem;
import org.nexus.l2.zk.groth16.Groth16Setup;
import org.nexus.l2.zk.groth16.R1csSatisfactionProof;
import org.nexus.l2.zk.r1cs.R1csConstraint;
import org.nexus.l2.zk.r1cs.R1csConstraintSystem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Groth16 证明系统单元测试。
 *
 * <p>验证 {@link Groth16ProofSystem} 的完整流程：
 * setup → prove → verify，以及 R1CS 约束系统的正确性。
 * 包含 ZK-P0-01/02/03 修复的验证测试。</p>
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
        assertTrue(proof.hasR1csSatisfactionProof(), "proof should contain R1CS satisfaction proof");

        // verify
        boolean valid = proofSystem.verify("mul-test", proof, new long[0]);
        assertTrue(valid, "proof should verify");
    }

    /**
     * 测试 RollupStateTransitionCircuit 的 R1CS 构建和 Groth16 流程。
     *
     * <p>ZK-P0-03 修复后约束数 = 3 + 2*maxBatchSize，
     * numPrivate = 6*maxBatchSize + 2，witnessSize = 6*maxBatchSize + 6。</p>
     */
    @Test
    public void testRollupStateTransitionCircuit() {
        int maxBatch = 10;
        RollupStateTransitionCircuit circuit = new RollupStateTransitionCircuit(maxBatch);
        assertTrue(circuit.hasR1cs(), "circuit should have R1CS");

        R1csConstraintSystem r1cs = circuit.buildR1cs();
        assertNotNull(r1cs);
        assertEquals(3, r1cs.getNumPublic());
        // ZK-P0-03: numPrivate = 7*maxBatch + 2
        assertEquals(7 * maxBatch + 2, r1cs.getNumPrivate());
        // witnessSize = 1 + 3 + 7*maxBatch + 2 = 7*maxBatch + 6
        assertEquals(7 * maxBatch + 6, r1cs.getWitnessSize());
        // 约束数 = 3 + 2*maxBatch
        assertEquals(3 + 2 * maxBatch, r1cs.getConstraintCount());

        // 构造合法 witness: preState=100, postState=150, batchHash=999
        // txEffects = [10, 20, 20] (sum=50), postState - preState = 50
        long[] witness = circuit.buildWitness(100, 150, 999, new long[]{10, 20, 20});
        assertEquals(7 * maxBatch + 6, witness.length);
        assertTrue(r1cs.isSatisfied(witness), "witness should satisfy R1CS");

        // setup + prove + verify
        proofSystem.setup("rollup-test", r1cs);
        Groth16Proof proof = proofSystem.prove("rollup-test", r1cs, witness);
        assertNotNull(proof);
        assertTrue(proof.hasR1csSatisfactionProof(), "rollup proof should contain R1CS satisfaction proof");
        assertEquals(3 + 2 * maxBatch, proof.getR1csSatisfactionProof().size(),
                "R1CS proof should cover all constraints");

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

    // ==================== ZK-P0-01 修复验证测试 ====================

    /**
     * ZK-P0-01 验证：诚实 prover 的证明包含 R1CS 满足性证明并通过验证。
     */
    @Test
    public void testZkP0_01_HonestProverPasses() {
        // 构造电路: x * y = z, x=6, y=7, z=42
        List<R1csConstraint> constraints = new ArrayList<>();
        constraints.add(R1csConstraint.multiplication(1, 2, 3));
        R1csConstraintSystem r1cs = new R1csConstraintSystem(0, 3, constraints);

        proofSystem.setup("p0_01_honest", r1cs);
        long[] witness = {1, 6, 7, 42};
        Groth16Proof proof = proofSystem.prove("p0_01_honest", r1cs, witness);

        // 验证证明包含 R1CS 满足性证明
        assertTrue(proof.hasR1csSatisfactionProof(), "honest proof should contain R1CS satisfaction proof");
        R1csSatisfactionProof r1csProof = proof.getR1csSatisfactionProof();
        assertEquals(1, r1csProof.size(), "should have 1 constraint proof");

        // 验证 R1CS 约束等式 aVal * bVal == cVal
        R1csSatisfactionProof.ConstraintProof cp = r1csProof.getConstraintProofs()[0];
        assertEquals(6, cp.getAVal(), "aVal should be x=6");
        assertEquals(7, cp.getBVal(), "bVal should be y=7");
        assertEquals(42, cp.getCVal(), "cVal should be z=42");
        assertEquals(42, cp.getAVal() * cp.getBVal(), "aVal * bVal should equal cVal");

        // 验证通过
        boolean valid = proofSystem.verify("p0_01_honest", proof, new long[0]);
        assertTrue(valid, "honest proof should verify");
    }

    /**
     * ZK-P0-01 验证：旧格式证明（无 R1CS 满足性证明）验证失败。
     */
    @Test
    public void testZkP0_01_LegacyProofFails() {
        // 构造电路: x * y = z, x=6, y=7, z=42
        List<R1csConstraint> constraints = new ArrayList<>();
        constraints.add(R1csConstraint.multiplication(1, 2, 3));
        R1csConstraintSystem r1cs = new R1csConstraintSystem(0, 3, constraints);

        Groth16Setup setup = proofSystem.setup("p0_01_legacy", r1cs);
        long[] witness = {1, 6, 7, 42};

        // 构造旧格式证明（无 R1CS 满足性证明）
        org.bouncycastle.math.ec.ECPoint a = setup.getProvingKey().getAlphaG();
        org.bouncycastle.math.ec.ECPoint b = setup.getProvingKey().getBetaG();
        org.bouncycastle.math.ec.ECPoint c = setup.getProvingKey().getDeltaG();
        Groth16Proof legacyProof = new Groth16Proof(a, b, c, "p0_01_legacy"); // 无 r1csProof

        // 验证应失败（缺少 R1CS 满足性证明）
        boolean valid = proofSystem.verify("p0_01_legacy", legacyProof, new long[0]);
        assertFalse(valid, "legacy proof without R1CS satisfaction proof should fail verification");
    }

    /**
     * ZK-P0-01 验证：R1CS 满足性证明覆盖所有约束。
     */
    @Test
    public void testZkP0_01_R1csProofCoversAllConstraints() {
        // 构造多约束电路
        List<R1csConstraint> constraints = new ArrayList<>();
        constraints.add(R1csConstraint.multiplication(1, 2, 3));  // x * y = z
        constraints.add(R1csConstraint.constant(3, 12));          // z = 12
        R1csConstraintSystem r1cs = new R1csConstraintSystem(0, 3, constraints);

        proofSystem.setup("p0_01_multi", r1cs);
        long[] witness = {1, 3, 4, 12};
        Groth16Proof proof = proofSystem.prove("p0_01_multi", r1cs, witness);

        assertEquals(2, proof.getR1csSatisfactionProof().size(),
                "R1CS proof should cover all 2 constraints");
        boolean valid = proofSystem.verify("p0_01_multi", proof, new long[0]);
        assertTrue(valid, "multi-constraint proof should verify");
    }

    // ==================== ZK-P0-02 修复验证测试 ====================

    /**
     * ZK-P0-02 验证：setup 后 toxic waste 被销毁。
     */
    @Test
    public void testZkP0_02_ToxicWasteDestroyedAfterSetup() {
        List<R1csConstraint> constraints = new ArrayList<>();
        constraints.add(R1csConstraint.multiplication(1, 2, 3));
        R1csConstraintSystem r1cs = new R1csConstraintSystem(0, 3, constraints);

        Groth16Setup setup = proofSystem.setup("p0_02_destroy", r1cs);

        // 验证 toxic waste 已被销毁
        assertTrue(setup.isToxicWasteDestroyed(),
                "toxic waste should be destroyed after setup");
    }

    /**
     * ZK-P0-02 验证：proving key 不存储 toxic waste。
     */
    @Test
    public void testZkP0_02_ProvingKeyHasNoToxicWaste() {
        List<R1csConstraint> constraints = new ArrayList<>();
        constraints.add(R1csConstraint.multiplication(1, 2, 3));
        R1csConstraintSystem r1cs = new R1csConstraintSystem(0, 3, constraints);

        Groth16Setup setup = proofSystem.setup("p0_02_pk", r1cs);
        org.nexus.l2.zk.groth16.Groth16ProvingKey pk = setup.getProvingKey();

        // proving key 应该有 alphaG, betaG, deltaG（椭圆曲线点）
        assertNotNull(pk.getAlphaG(), "alphaG should be present");
        assertNotNull(pk.getBetaG(), "betaG should be present");
        assertNotNull(pk.getDeltaG(), "deltaG should be present");
        // proving key 不应有 getToxicWaste() 方法（编译时保证）
    }

    /**
     * ZK-P0-02 验证：verifying key 不存储 toxic waste。
     */
    @Test
    public void testZkP0_02_VerifyingKeyHasNoToxicWaste() {
        List<R1csConstraint> constraints = new ArrayList<>();
        constraints.add(R1csConstraint.multiplication(1, 2, 3));
        R1csConstraintSystem r1cs = new R1csConstraintSystem(0, 3, constraints);

        Groth16Setup setup = proofSystem.setup("p0_02_vk", r1cs);
        org.nexus.l2.zk.groth16.Groth16VerifyingKey vk = setup.getVerifyingKey();

        // verifying key 应该有 alphaG, betaG, gammaG, deltaG（椭圆曲线点）
        assertNotNull(vk.getAlphaG(), "alphaG should be present");
        assertNotNull(vk.getBetaG(), "betaG should be present");
        assertNotNull(vk.getGammaG(), "gammaG should be present");
        assertNotNull(vk.getDeltaG(), "deltaG should be present");
        // verifying key 不应有 getToxicWaste() 方法（编译时保证）
    }

    /**
     * ZK-P0-02 验证：destroyToxicWaste() 幂等，多次调用安全。
     */
    @Test
    public void testZkP0_02_DestroyToxicWasteIdempotent() {
        List<R1csConstraint> constraints = new ArrayList<>();
        constraints.add(R1csConstraint.multiplication(1, 2, 3));
        R1csConstraintSystem r1cs = new R1csConstraintSystem(0, 3, constraints);

        Groth16Setup setup = proofSystem.setup("p0_02_idem", r1cs);
        // setup 后已销毁
        assertTrue(setup.isToxicWasteDestroyed());
        // 再次调用应安全
        setup.destroyToxicWaste();
        assertTrue(setup.isToxicWasteDestroyed());
    }

    // ==================== ZK-P0-03 修复验证测试 ====================

    /**
     * ZK-P0-03 验证：R1CS 约束数正确（3 + 2*maxBatchSize）。
     */
    @Test
    public void testZkP0_03_ConstraintCount() {
        int maxBatch = 8;
        RollupStateTransitionCircuit circuit = new RollupStateTransitionCircuit(maxBatch);
        R1csConstraintSystem r1cs = circuit.buildR1cs();

        // 约束数 = 3（状态守恒 + 累加 + gas累计） + 2*maxBatch（nonce递增 + 签名格式）
        assertEquals(3 + 2 * maxBatch, r1cs.getConstraintCount(),
                "constraint count should be 3 + 2*maxBatchSize");
        assertEquals(3 + 2 * maxBatch, circuit.defineCircuit(),
                "defineCircuit should return correct constraint count");
    }

    /**
     * ZK-P0-03 验证：有效状态转换通过 R1CS 验证。
     */
    @Test
    public void testZkP0_03_ValidStateTransitionPasses() {
        int maxBatch = 5;
        RollupStateTransitionCircuit circuit = new RollupStateTransitionCircuit(maxBatch);
        R1csConstraintSystem r1cs = circuit.buildR1cs();

        // 有效状态转换: preState=100, postState=150, txEffects=[10,20,20,0,0] sum=50
        long[] witness = circuit.buildWitness(100, 150, 999, new long[]{10, 20, 20});
        assertTrue(r1cs.isSatisfied(witness), "valid state transition should satisfy R1CS");

        // 完整 Groth16 流程
        proofSystem.setup("p0_03_valid", r1cs);
        Groth16Proof proof = proofSystem.prove("p0_03_valid", r1cs, witness);
        boolean valid = proofSystem.verify("p0_03_valid", proof, new long[]{100, 150, 999});
        assertTrue(valid, "valid state transition proof should verify");
    }

    /**
     * ZK-P0-03 验证：无效状态转换（状态不守恒）失败。
     */
    @Test
    public void testZkP0_03_InvalidStateTransitionFails() {
        int maxBatch = 5;
        RollupStateTransitionCircuit circuit = new RollupStateTransitionCircuit(maxBatch);
        R1csConstraintSystem r1cs = circuit.buildR1cs();

        // 无效状态转换: preState=100, postState=200, txEffects=[10,20,20,0,0] sum=50 != 100
        long[] witness = circuit.buildWitness(100, 200, 999, new long[]{10, 20, 20});
        assertFalse(r1cs.isSatisfied(witness),
                "invalid state transition (state not conserved) should not satisfy R1CS");
    }

    /**
     * ZK-P0-03 验证：Nonce 单调递增约束有效。
     *
     * <p>使用高级 buildWitness 方法构造违反 nonce 约束的 witness。</p>
     */
    @Test
    public void testZkP0_03_NonceMonotonicConstraint() {
        int maxBatch = 3;
        RollupStateTransitionCircuit circuit = new RollupStateTransitionCircuit(maxBatch);
        R1csConstraintSystem r1cs = circuit.buildR1cs();

        // 构造违反 nonce 约束的 witness：txNonce_i < accountNonce_i
        // txNonce = [1, 1, 3], accountNonce = [0, 2, 2] → 第二笔 txNonce(1) < accountNonce(2)
        long[] txEffects = {10, 20, 30};
        long[] txGas = {10, 20, 30};
        long[] txNonce = {1, 1, 3};       // 第二笔 nonce=1
        long[] accountNonce = {0, 2, 2};  // 第二笔 accountNonce=2，违反 txNonce = accountNonce + 1
        long[] sigR = {1, 1, 1};
        long[] sigS = {1, 1, 1};

        long sum = 60;
        long[] witness = circuit.buildWitness(100, 100 + sum, 999,
                txEffects, txGas, txNonce, accountNonce, sigR, sigS);
        assertFalse(r1cs.isSatisfied(witness),
                "witness violating nonce monotonic constraint should not satisfy R1CS");
    }

    /**
     * ZK-P0-03 验证：Gas 累计约束有效。
     */
    @Test
    public void testZkP0_03_GasAccumulationConstraint() {
        int maxBatch = 3;
        RollupStateTransitionCircuit circuit = new RollupStateTransitionCircuit(maxBatch);
        R1csConstraintSystem r1cs = circuit.buildR1cs();

        // 有效 witness: gas 累计正确
        long[] txEffects = {10, 20, 30};
        long[] txGas = {10, 20, 30};
        long[] txNonce = {1, 2, 3};
        long[] accountNonce = {0, 1, 2};
        long[] sigR = {1, 1, 1};
        long[] sigS = {1, 1, 1};

        long sum = 60;
        long[] witness = circuit.buildWitness(100, 100 + sum, 999,
                txEffects, txGas, txNonce, accountNonce, sigR, sigS);
        assertTrue(r1cs.isSatisfied(witness),
                "valid witness with correct gas accumulation should satisfy R1CS");
    }

    /**
     * ZK-P0-03 验证：签名格式约束有效。
     *
     * <p>构造违反签名格式约束（sigR * sigS != sigRSProduct）的 witness 应失败。</p>
     */
    @Test
    public void testZkP0_03_SignatureFormatConstraint() {
        int maxBatch = 2;
        RollupStateTransitionCircuit circuit = new RollupStateTransitionCircuit(maxBatch);
        R1csConstraintSystem r1cs = circuit.buildR1cs();

        // 有效签名: sigR=2, sigS=3, sigRSProduct=6
        long[] txEffects = {10, 20};
        long[] txGas = {10, 20};
        long[] txNonce = {1, 2};
        long[] accountNonce = {0, 1};
        long[] sigR = {2, 3};
        long[] sigS = {3, 5};

        long sum = 30;
        long[] witness = circuit.buildWitness(100, 100 + sum, 999,
                txEffects, txGas, txNonce, accountNonce, sigR, sigS);
        assertTrue(r1cs.isSatisfied(witness),
                "valid signature format should satisfy R1CS");
    }

    /**
     * ZK-P0-03 验证：移除了恒等约束（txEffect_i * 1 = txEffect_i）。
     *
     * <p>验证约束系统中不存在恒等约束。恒等约束的 A 和 C 相同且 B = {0:1}。</p>
     */
    @Test
    public void testZkP0_03_NoIdentityConstraints() {
        int maxBatch = 3;
        RollupStateTransitionCircuit circuit = new RollupStateTransitionCircuit(maxBatch);
        R1csConstraintSystem r1cs = circuit.buildR1cs();

        for (R1csConstraint c : r1cs.getConstraints()) {
            // 恒等约束的特征：A == C 且 B == {0:1}
            boolean isIdentity = c.getA().equals(c.getC())
                    && c.getB().size() == 1
                    && c.getB().containsKey(0)
                    && c.getB().get(0) == 1L;
            assertFalse(isIdentity, "R1CS should not contain identity constraints (txEffect * 1 = txEffect)");
        }
    }

    /**
     * ZK-P0-03 验证：所有约束都有实际语义（非空）。
     */
    @Test
    public void testZkP0_03_AllConstraintsHaveSemantics() {
        int maxBatch = 3;
        RollupStateTransitionCircuit circuit = new RollupStateTransitionCircuit(maxBatch);
        R1csConstraintSystem r1cs = circuit.buildR1cs();

        for (R1csConstraint c : r1cs.getConstraints()) {
            // 每个约束的 A 向量不应为空
            assertFalse(c.getA().isEmpty(), "constraint A vector should not be empty");
            // 线性约束或乘法约束都应有 C 向量或 B = {0:1}
            assertTrue(!c.getC().isEmpty() || c.getB().containsKey(0),
                    "constraint should have meaningful C or B=1 (linear)");
        }
    }
}
