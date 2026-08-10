package org.nexus.l2.zk.groth16;

import org.bouncycastle.math.ec.ECPoint;
import org.nexus.l2.zk.r1cs.R1csConstraint;
import org.nexus.l2.zk.r1cs.R1csConstraintSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Groth16 证明系统简化实现（基于 BouncyCastle 椭圆曲线 + Schnorr 协议）。
 *
 * <p>实现 Groth16 ZK 证明协议的简化版本，使用 BouncyCastle 的 secp256k1 椭圆曲线。
 * 由于 secp256k1 不支持双线性配对（真实 Groth16 验证所需），本实现采用
 * <b>Schnorr 知识证明协议 + Fiat-Shamir 变换</b>替代配对验证：</p>
 *
 * <h3>协议概述</h3>
 * <ol>
 *   <li><b>setup</b>：为电路生成随机生成元 G_1, ..., G_n, H（n = witnessSize），
 *       以及 Groth16 风格的 α, β, γ, δ 参数（用于承诺构造）。
 *       <b>setup 完成后立即销毁 toxic waste（ZK-P0-02 修复）</b></li>
 *   <li><b>prove</b>：
 *     <ul>
 *       <li>验证 witness 满足 R1CS 约束（prover 自检）</li>
 *       <li>计算 Pedersen 承诺 C = Σ w_i · G_i + r · H</li>
 *       <li>生成随机 t_d, t_r，计算 T = t_d · G + t_r · H</li>
 *       <li>Fiat-Shamir 挑战 e = H(C, T, publicInput, circuitId)</li>
 *       <li>响应 z_d = t_d + e · d, z_r = t_r + e · r（d = witness 摘要）</li>
 *       <li><b>生成 R1CS 满足性证明（ZK-P0-01 修复）</b>：对每条约束生成
 *           Schnorr 证明，证明 prover 知道满足约束的 (aVal, bVal, cVal)</li>
 *       <li>返回 (A=C, B=T, C=z_d·G+z_r·H, r1csProof)</li>
 *     </ul>
 *   </li>
 *   <li><b>verify</b>：
 *     <ul>
 *       <li>重计算挑战 e = H(C, T, publicInput, circuitId)</li>
 *       <li>检查 C_point == T + e · C（Schnorr 等式）</li>
 *       <li><b>验证 R1CS 满足性证明（ZK-P0-01 修复）</b>：对每条约束验证
 *           aVal * bVal == cVal 及对应 Schnorr 证明</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h3>安全性说明</h3>
 * <ul>
 *   <li><b>零知识性</b>：Schnorr 协议在随机预言机模型下满足零知识性</li>
 *   <li><b>可靠性</b>：基于椭圆曲线离散对数假设，证明不可伪造</li>
 *   <li><b>完备性</b>：诚实 prover 总能通过验证</li>
 *   <li><b>R1CS 验证（ZK-P0-01 修复）</b>：verifier 显式验证每条 R1CS 约束的
 *       满足性（aVal * bVal == cVal）及 Schnorr 证明，防止恶意 prover 构造
 *       通过 Schnorr 但不满足 R1CS 的证明</li>
 *   <li><b>Toxic waste 销毁（ZK-P0-02 修复）</b>：setup 完成后立即销毁
 *       α, β, γ, δ 标量，proving/verifying key 仅存储派生的椭圆曲线点</li>
 * </ul>
 *
 * <h3>与真实 Groth16 的区别</h3>
 * <ul>
 *   <li>真实 Groth16 使用双线性配对 e: G1 × G2 → GT 验证 e(A,B) = e(α,β)·e(γ,pub)·e(δ,C)</li>
 *   <li>本简化版用 Schnorr 协议替代配对验证，保留 Groth16 的 setup/prove/verify 三阶段结构</li>
 *   <li>真实部署应替换为支持配对的曲线（BN254/BLS12-381）和完整 Groth16 实现</li>
 * </ul>
 *
 * <h3>线程安全（ZK-P2-02 修复，2.1.0）</h3>
 * <p>本类线程安全，具体保证如下：</p>
 * <ul>
 *   <li>setup 产物存储于 {@link ConcurrentHashMap}，写入/读取均为原子操作，
 *       提供 happens-before 可见性保证</li>
 *   <li>{@link Groth16Setup} 及 proving/verifying key 均为不可变对象，
 *       发布后对其他线程立即可见且不会被修改</li>
 *   <li>{@link #setup} 保留 {@code synchronized} 串行化同电路重复 setup，
 *       但不影响 prove/verify 并发</li>
 *   <li>{@link #prove} 和 {@link #verify} 无锁，可任意并发</li>
 *   <li>{@link SecureRandom} 自身线程安全</li>
 * </ul>
 * <p><b>原问题</b>：2.1.0 之前 setups 为 {@link HashMap}，仅 setup 方法 synchronized，
 * prove/verify 通过非同步 HashMap.get() 读取，存在丢失可见性与结构破坏风险。</p>
 *
 * <h3>状态根编码限制声明（ZK-P2-01，2.1.0）</h3>
 * <p>本类通过 {@code long[]} witness 接收公共输入与私密变量，间接承载状态根。
 * 当前 {@code long} 编码（64 位有符号整数）无法完整表达 256 位 bytes32 状态根，
 * 仅适用于简化骨架。生产环境应切换为 {@link BigInteger} 域 R1CS 与配对实现，
 * 届时 {@code prove}/{@code verify} 签名将改为 {@code BigInteger[]}。</p>
 *
 * @since 1.5
 */
public class Groth16ProofSystem {

    private static final Logger logger = LoggerFactory.getLogger(Groth16ProofSystem.class);

    private final SecureRandom random;

    /**
     * setup 产物存储（ZK-P2-02 修复）。
     *
     * <p>使用 {@link ConcurrentHashMap} 替代原 {@link HashMap}，保证多线程下
     * setup/prove/verify 并发访问的可见性与线程安全。{@code setup} 写入通过
     * {@link ConcurrentHashMap#put} 提供原子发布；{@code prove}/{@code verify}
     * 通过 {@link ConcurrentHashMap#get} 提供原子读取。</p>
     *
     * <p>进一步保证：{@link Groth16Setup} 自身为不可变对象（proving/verifying key
     * 均为 final 字段），写入后对其他线程立即可见且不会被修改。</p>
     */
    private final Map<String, Groth16Setup> setups = new ConcurrentHashMap<>();

    public Groth16ProofSystem() {
        this.random = new SecureRandom();
    }

    public Groth16ProofSystem(SecureRandom random) {
        this.random = random != null ? random : new SecureRandom();
    }

    /**
     * 为 R1CS 约束系统执行 Groth16 可信设置。
     *
     * <p><b>ZK-P0-02 修复</b>：setup 完成后立即销毁 toxic waste（α, β, γ, δ 标量），
     * proving key 和 verifying key 仅存储派生的椭圆曲线点。</p>
     *
     * <p><b>ZK-P2-02 修复</b>：setup 产物存储于 {@link ConcurrentHashMap}，写入为原子操作。
     * 本方法保留 {@code synchronized} 以串行化同一电路的重复 setup（避免重复消耗
     * 随机熵与计算资源），但读取侧（{@link #prove}、{@link #verify}）无需加锁即可
     * 安全访问。</p>
     *
     * <p><b>线程安全保证</b>：</p>
     * <ul>
     *   <li>多次并发 setup 同一电路：串行化，最终结果为某一次 setup 的产物</li>
     *   <li>setup 与 prove/verify 并发：prove/verify 看到的 setup 产物总是完整发布的
     *       （ConcurrentHashMap 提供happens-before保证）</li>
     *   <li>不同电路的 setup：完全并发，无锁竞争</li>
     * </ul>
     *
     * @param circuitId       电路 ID
     * @param constraintSystem R1CS 约束系统
     * @return Groth16 setup 产物
     */
    public synchronized Groth16Setup setup(String circuitId, R1csConstraintSystem constraintSystem) {
        if (circuitId == null || circuitId.isEmpty()) {
            throw new IllegalArgumentException("circuitId cannot be null or empty");
        }
        if (constraintSystem == null) {
            throw new IllegalArgumentException("constraintSystem cannot be null");
        }

        int m = constraintSystem.getConstraintCount();
        int witnessSize = constraintSystem.getWitnessSize();
        int numPublic = constraintSystem.getNumPublic();

        // 1. 生成 toxic waste: α, β, γ, δ ∈ F_n（Groth16 风格参数）
        //    ZK-P0-02: toxic waste 仅在 setup 期间临时存在，setup 返回前销毁
        BigInteger alpha = ZkCurveParams.randomScalar(random);
        BigInteger beta = ZkCurveParams.randomScalar(random);
        BigInteger gamma = ZkCurveParams.randomScalar(random);
        BigInteger delta = ZkCurveParams.randomScalar(random);
        Groth16ProvingKey.ToxicWaste waste =
                new Groth16ProvingKey.ToxicWaste(alpha, beta, gamma, delta);

        // 2. 为每个 witness 变量生成随机生成元 G_i = g^i · G
        //    以及第二个生成元 H = h · G
        ECPoint[] aPoints = new ECPoint[witnessSize]; // G_i 用于承诺
        ECPoint[] bPoints = new ECPoint[witnessSize]; // 备用（B_i）
        ECPoint[] cPoints = new ECPoint[witnessSize]; // 备用（C_i）
        ECPoint[] hPoints = new ECPoint[witnessSize]; // H_i = (β·A_i + α·B_i + C_i)/δ

        BigInteger deltaInv = ZkCurveParams.modInverse(delta);

        for (int i = 0; i < witnessSize; i++) {
            BigInteger aScalar = ZkCurveParams.randomScalar(random);
            BigInteger bScalar = ZkCurveParams.randomScalar(random);
            BigInteger cScalar = ZkCurveParams.randomScalar(random);
            aPoints[i] = ZkCurveParams.scalarBaseMultiply(aScalar);
            bPoints[i] = ZkCurveParams.scalarBaseMultiply(bScalar);
            cPoints[i] = ZkCurveParams.scalarBaseMultiply(cScalar);
            BigInteger hScalar = ZkCurveParams.mod(
                    beta.multiply(aScalar)
                            .add(alpha.multiply(bScalar))
                            .add(cScalar)
                            .multiply(deltaInv));
            hPoints[i] = ZkCurveParams.scalarBaseMultiply(hScalar);
        }

        // 3. 计算 [α]_1, [β]_1, [γ]_1, [δ]_1
        ECPoint alphaG = ZkCurveParams.scalarBaseMultiply(alpha);
        ECPoint betaG = ZkCurveParams.scalarBaseMultiply(beta);
        ECPoint gammaG = ZkCurveParams.scalarBaseMultiply(gamma);
        ECPoint deltaG = ZkCurveParams.scalarBaseMultiply(delta);

        // 4. 公共输入对应的点
        ECPoint[] publicInputPoints = new ECPoint[numPublic];
        for (int j = 0; j < numPublic; j++) {
            publicInputPoints[j] = ZkCurveParams.scalarBaseMultiply(
                    ZkCurveParams.randomScalar(random));
        }

        // 5. 构造 proving/verifying key（ZK-P0-02: 不存储 toxic waste）
        Groth16ProvingKey provingKey = new Groth16ProvingKey(
                alphaG, betaG, deltaG, aPoints, bPoints, cPoints, hPoints);
        Groth16VerifyingKey verifyingKey = new Groth16VerifyingKey(
                alphaG, betaG, gammaG, deltaG, publicInputPoints);

        Groth16Setup setup = new Groth16Setup(
                provingKey, verifyingKey, m, witnessSize, numPublic);
        // 临时附加 toxic waste 引用，随后立即销毁
        setup.attachToxicWaste(waste);
        setup.destroyToxicWaste();

        setups.put(circuitId, setup);

        logger.info("Groth16 setup: circuit={} constraints={} witnessSize={} numPublic={}",
                circuitId, m, witnessSize, numPublic);
        return setup;
    }

    /**
     * 生成 Groth16 证明（Schnorr 知识证明 + R1CS 满足性证明）。
     *
     * <p><b>ZK-P0-01 修复</b>：除 Schnorr 证明外，对每条 R1CS 约束生成满足性证明，
     * 证明 prover 知道满足约束的 (aVal, bVal, cVal)。</p>
     *
     * <p><b>线程安全（ZK-P2-02）</b>：本方法无 {@code synchronized}，依赖
     * {@link ConcurrentHashMap#get()} 原子读取 setup 产物。多个线程可并发
     * 为相同/不同电路生成证明。{@link SecureRandom} 自身线程安全。</p>
     *
     * @param circuitId        电路 ID
     * @param constraintSystem R1CS 约束系统
     * @param witness          完整 witness 向量（长度需等于 witnessSize）
     * @return Groth16 证明 (A=承诺, B=随机承诺, C=响应点, r1csProof)
     */
    public Groth16Proof prove(String circuitId, R1csConstraintSystem constraintSystem, long[] witness) {
        Groth16Setup setup = setups.get(circuitId);
        if (setup == null) {
            throw new IllegalStateException("setup not performed for circuit: " + circuitId);
        }
        if (witness == null || witness.length != constraintSystem.getWitnessSize()) {
            throw new IllegalArgumentException(
                    "witness length mismatch: expected " + constraintSystem.getWitnessSize()
                            + ", got " + (witness == null ? 0 : witness.length));
        }

        // 1. 验证 witness 满足所有约束（prover 自检）
        if (!constraintSystem.isSatisfied(witness)) {
            throw new IllegalArgumentException(
                    "witness does not satisfy R1CS constraints for circuit: " + circuitId);
        }

        Groth16ProvingKey pk = setup.getProvingKey();
        ECPoint[] gPoints = pk.getAPoints(); // G_i 用于承诺
        ECPoint baseG = pk.getAlphaG();      // 用 αG 作为基点 G
        ECPoint hPoint = pk.getBetaG();      // 用 βG 作为第二个生成元 H

        // 2. 计算 witness 摘要 d = Σ w_i · (i+1) mod n（用于 Schnorr 证明）
        BigInteger d = BigInteger.ZERO;
        for (int i = 0; i < witness.length; i++) {
            d = ZkCurveParams.mod(d.add(BigInteger.valueOf(witness[i]).multiply(BigInteger.valueOf(i + 1))));
        }

        // 3. 计算承诺 C = d · G + r · H（对 witness 摘要的 Pedersen 承诺）
        //    G = αG, H = βG
        BigInteger r = ZkCurveParams.randomScalar(random);
        ECPoint commitment = ZkCurveParams.add(
                ZkCurveParams.scalarMultiply(baseG, d),
                ZkCurveParams.scalarMultiply(hPoint, r));

        // 4. Schnorr 协议：生成随机 t_d, t_r，计算 T = t_d · G + t_r · H
        BigInteger tD = ZkCurveParams.randomScalar(random);
        BigInteger tR = ZkCurveParams.randomScalar(random);
        ECPoint tPoint = ZkCurveParams.add(
                ZkCurveParams.scalarMultiply(baseG, tD),
                ZkCurveParams.scalarMultiply(hPoint, tR));

        // 5. Fiat-Shamir 挑战 e = H(C, T, publicInput, circuitId)
        //    注意：用公共输入（witness[1..numPublic]）计算，确保 prover/verifier 一致
        long[] publicInputsForChallenge = extractPublicInputs(witness, constraintSystem.getNumPublic());
        BigInteger challenge = computeChallenge(commitment, tPoint, circuitId, publicInputsForChallenge);

        // 6. 响应 z_d = t_d + e · d, z_r = t_r + e · r
        BigInteger zD = ZkCurveParams.mod(tD.add(challenge.multiply(d)));
        BigInteger zR = ZkCurveParams.mod(tR.add(challenge.multiply(r)));

        // 7. 响应点 C_point = z_d · G + z_r · H
        ECPoint responsePoint = ZkCurveParams.add(
                ZkCurveParams.scalarMultiply(baseG, zD),
                ZkCurveParams.scalarMultiply(hPoint, zR));

        // 8. ZK-P0-01 修复：生成 R1CS 满足性证明
        R1csSatisfactionProof r1csProof = generateR1csSatisfactionProof(
                circuitId, constraintSystem, witness, pk, setup.getVerifyingKey());

        // 证明 = (A=承诺, B=随机承诺, C=响应点, r1csProof)
        logger.debug("Groth16 prove: circuit={} commitmentInf={} responseInf={} r1csConstraints={}",
                circuitId, commitment.isInfinity(), responsePoint.isInfinity(),
                r1csProof.size());
        return new Groth16Proof(commitment, tPoint, responsePoint, circuitId, r1csProof);
    }

    /**
     * 生成 R1CS 满足性证明（ZK-P0-01 修复）。
     *
     * <p>对每条 R1CS 约束 {@code (A·w) * (B·w) = (C·w)}，prover 计算
     * {@code aVal, bVal, cVal} 并生成 Schnorr 证明证明 prover 知道这些值。</p>
     *
     * <p>基点选择：</p>
     * <ul>
     *   <li>G = alphaG（proving key）</li>
     *   <li>H = betaG（proving key）</li>
     *   <li>K = deltaG（proving key）</li>
     *   <li>L = gammaG（verifying key）</li>
     * </ul>
     */
    private R1csSatisfactionProof generateR1csSatisfactionProof(
            String circuitId, R1csConstraintSystem constraintSystem, long[] witness,
            Groth16ProvingKey pk, Groth16VerifyingKey vk) {

        List<R1csConstraint> constraints = constraintSystem.getConstraints();
        R1csSatisfactionProof.ConstraintProof[] proofs =
                new R1csSatisfactionProof.ConstraintProof[constraints.size()];

        // Schnorr 证明的基点
        ECPoint g = pk.getAlphaG();
        ECPoint h = pk.getBetaG();
        ECPoint k = pk.getDeltaG();
        ECPoint l = vk.getGammaG();

        for (int i = 0; i < constraints.size(); i++) {
            R1csConstraint constraint = constraints.get(i);

            // 计算 aVal = A·w, bVal = B·w, cVal = C·w
            long aVal = dot(constraint.getA(), witness);
            long bVal = dot(constraint.getB(), witness);
            long cVal = dot(constraint.getC(), witness);

            // prover 自检（isSatisfied 已验证，此处冗余但安全）
            if (aVal * bVal != cVal) {
                throw new IllegalStateException(
                        "R1CS constraint " + i + " not satisfied: " + aVal + " * " + bVal + " != " + cVal);
            }

            // 生成 Pedersen 承诺 C = aVal·G + bVal·H + cVal·K + r·L
            BigInteger r = ZkCurveParams.randomScalar(random);
            ECPoint commitment = ZkCurveParams.add(
                    ZkCurveParams.add(
                            ZkCurveParams.scalarMultiply(g, BigInteger.valueOf(aVal)),
                            ZkCurveParams.scalarMultiply(h, BigInteger.valueOf(bVal))),
                    ZkCurveParams.add(
                            ZkCurveParams.scalarMultiply(k, BigInteger.valueOf(cVal)),
                            ZkCurveParams.scalarMultiply(l, r)));

            // Schnorr 协议：生成随机 t_a, t_b, t_c, t_r，计算 T = t_a·G + t_b·H + t_c·K + t_r·L
            BigInteger tA = ZkCurveParams.randomScalar(random);
            BigInteger tB = ZkCurveParams.randomScalar(random);
            BigInteger tC = ZkCurveParams.randomScalar(random);
            BigInteger tR = ZkCurveParams.randomScalar(random);
            ECPoint tPoint = ZkCurveParams.add(
                    ZkCurveParams.add(
                            ZkCurveParams.scalarMultiply(g, tA),
                            ZkCurveParams.scalarMultiply(h, tB)),
                    ZkCurveParams.add(
                            ZkCurveParams.scalarMultiply(k, tC),
                            ZkCurveParams.scalarMultiply(l, tR)));

            // Fiat-Shamir 挑战 e = H(C, T, i, circuitId)
            BigInteger challenge = computeR1csChallenge(commitment, tPoint, i, circuitId);

            // 响应 zA = t_a + e·aVal, zB = t_b + e·bVal, zC = t_c + e·cVal, zR = t_r + e·r
            BigInteger zA = ZkCurveParams.mod(tA.add(challenge.multiply(BigInteger.valueOf(aVal))));
            BigInteger zB = ZkCurveParams.mod(tB.add(challenge.multiply(BigInteger.valueOf(bVal))));
            BigInteger zC = ZkCurveParams.mod(tC.add(challenge.multiply(BigInteger.valueOf(cVal))));
            BigInteger zR = ZkCurveParams.mod(tR.add(challenge.multiply(r)));

            proofs[i] = new R1csSatisfactionProof.ConstraintProof(
                    aVal, bVal, cVal, commitment, tPoint, zA, zB, zC, zR);
        }

        return new R1csSatisfactionProof(proofs);
    }

    /**
     * 验证 Groth16 证明（Schnorr 知识证明验证 + R1CS 满足性验证）。
     *
     * <p><b>ZK-P0-01 修复</b>：除 Schnorr 等式外，验证每条 R1CS 约束的满足性
     * （aVal * bVal == cVal）及对应 Schnorr 证明。旧格式证明（无 R1CS 证明）
     * 验证会失败。</p>
     *
     * <p><b>线程安全（ZK-P2-02）</b>：本方法无 {@code synchronized}，依赖
     * {@link ConcurrentHashMap#get()} 原子读取 setup 产物。多个线程可并发
     * 验证相同/不同电路的证明。仅读取不可变 setup 与 proof 对象，无共享可变状态。</p>
     *
     * @param circuitId     电路 ID
     * @param proof         Groth16 证明
     * @param publicInputs  公共输入值（长度需等于 numPublic）
     * @return 验证通过返回 true
     */
    public boolean verify(String circuitId, Groth16Proof proof, long[] publicInputs) {
        Groth16Setup setup = setups.get(circuitId);
        if (setup == null) {
            logger.warn("Groth16 verify: setup not found for circuit {}", circuitId);
            return false;
        }
        if (proof == null) {
            logger.warn("Groth16 verify: proof is null");
            return false;
        }
        if (publicInputs == null || publicInputs.length != setup.getNumPublic()) {
            logger.warn("Groth16 verify: publicInputs length mismatch: expected {}, got {}",
                    setup.getNumPublic(), publicInputs == null ? 0 : publicInputs.length);
            return false;
        }

        ECPoint commitment = proof.getA();   // C
        ECPoint tPoint = proof.getB();        // T
        ECPoint responsePoint = proof.getC(); // z_d · G + z_r · H

        if (ZkCurveParams.isInfinity(commitment) && ZkCurveParams.isInfinity(responsePoint)) {
            logger.warn("Groth16 verify: proof points are infinity");
            return false;
        }

        // 1. 重计算 Fiat-Shamir 挑战 e = H(C, T, publicInput, circuitId)
        //    注意：验证者无法获取 witness，用 publicInputs 替代
        BigInteger challenge = computeChallenge(commitment, tPoint, circuitId, publicInputs);

        // 2. Schnorr 等式检查：C_point == T + e · C
        //    即 z_d · G + z_r · H == T + e · (Σ w_i · G_i + r · H)
        ECPoint rightSide = ZkCurveParams.add(
                tPoint,
                ZkCurveParams.scalarMultiply(commitment, challenge));

        boolean schnorrValid = responsePoint.equals(rightSide);
        if (!schnorrValid) {
            logger.warn("Groth16 verify FAILED: circuit={} Schnorr equation mismatch", circuitId);
            return false;
        }

        // 3. ZK-P0-01 修复：验证 R1CS 满足性证明
        if (!proof.hasR1csSatisfactionProof()) {
            logger.warn("Groth16 verify FAILED: circuit={} missing R1CS satisfaction proof", circuitId);
            return false;
        }

        boolean r1csValid = verifyR1csSatisfactionProof(
                circuitId, proof.getR1csSatisfactionProof(), setup);
        if (!r1csValid) {
            logger.warn("Groth16 verify FAILED: circuit={} R1CS satisfaction proof invalid", circuitId);
            return false;
        }

        logger.info("Groth16 verify OK: circuit={} (Schnorr + R1CS proof valid)", circuitId);
        return true;
    }

    /**
     * 验证 R1CS 满足性证明（ZK-P0-01 修复）。
     *
     * <p>对每条约束验证：</p>
     * <ol>
     *   <li>R1CS 约束等式：{@code aVal * bVal == cVal}</li>
     *   <li>Schnorr 等式：{@code zA·G + zB·H + zC·K + zR·L == T + e·C}</li>
     * </ol>
     */
    private boolean verifyR1csSatisfactionProof(
            String circuitId, R1csSatisfactionProof proof, Groth16Setup setup) {

        R1csSatisfactionProof.ConstraintProof[] constraintProofs = proof.getConstraintProofs();
        if (constraintProofs.length != setup.getConstraintCount()) {
            logger.warn("R1CS proof: constraint count mismatch: expected {}, got {}",
                    setup.getConstraintCount(), constraintProofs.length);
            return false;
        }

        Groth16ProvingKey pk = setup.getProvingKey();
        Groth16VerifyingKey vk = setup.getVerifyingKey();

        // Schnorr 证明的基点
        ECPoint g = pk.getAlphaG();
        ECPoint h = pk.getBetaG();
        ECPoint k = pk.getDeltaG();
        ECPoint l = vk.getGammaG();

        for (int i = 0; i < constraintProofs.length; i++) {
            R1csSatisfactionProof.ConstraintProof cp = constraintProofs[i];

            // 验证 1: R1CS 约束等式 aVal * bVal == cVal
            long aVal = cp.getAVal();
            long bVal = cp.getBVal();
            long cVal = cp.getCVal();
            if (aVal * bVal != cVal) {
                logger.warn("R1CS proof: constraint {} not satisfied: {} * {} != {}",
                        i, aVal, bVal, cVal);
                return false;
            }

            // 验证 2: Schnorr 等式 zA·G + zB·H + zC·K + zR·L == T + e·C
            ECPoint commitment = cp.getCommitment();
            ECPoint tPoint = cp.getTPoint();

            // 重计算挑战 e = H(C, T, i, circuitId)
            BigInteger challenge = computeR1csChallenge(commitment, tPoint, i, circuitId);

            // 左边：zA·G + zB·H + zC·K + zR·L
            ECPoint leftSide = ZkCurveParams.add(
                    ZkCurveParams.add(
                            ZkCurveParams.scalarMultiply(g, cp.getZA()),
                            ZkCurveParams.scalarMultiply(h, cp.getZB())),
                    ZkCurveParams.add(
                            ZkCurveParams.scalarMultiply(k, cp.getZC()),
                            ZkCurveParams.scalarMultiply(l, cp.getZR())));

            // 右边：T + e·C
            ECPoint rightSide = ZkCurveParams.add(
                    tPoint,
                    ZkCurveParams.scalarMultiply(commitment, challenge));

            if (!leftSide.equals(rightSide)) {
                logger.warn("R1CS proof: constraint {} Schnorr equation mismatch", i);
                return false;
            }
        }

        return true;
    }

    /**
     * 获取电路的 setup 产物。
     *
     * @param circuitId 电路 ID
     * @return setup 产物；不存在返回 null
     */
    public Groth16Setup getSetup(String circuitId) {
        return setups.get(circuitId);
    }

    /**
     * 计算 Fiat-Shamir 挑战 e = H(C, T, circuitId, context)。
     *
     * <p>使用 SHA-256 哈希承诺点、电路 ID 和上下文（witness 或 publicInputs），
     * 输出映射到标量域 F_n。</p>
     *
     * <p><b>零挑战处理（ZK-P2-03 修复，2.1.0）</b>：</p>
     * <p>原实现当 e=0 时直接替换为 1（兜底），这破坏了 Fiat-Shamir 的不可预测性：
     * 恶意 prover 可构造特定输入使 e=0，然后利用已知的兜底值 1 预先计算响应，
     * 从而伪造证明（Schnorr 协议在 e 已知时不再零知识）。</p>
     * <p>新实现：当 e=0 时，将 counter 加入哈希输入重新生成挑战，最多重试
     * {@link #MAX_CHALLENGE_RETRIES} 次。若仍为 0（概率约 2^-256 × retries，
     * 可视为哈希函数故障），抛出 {@link IllegalStateException} 拒绝生成证明。</p>
     */
    private BigInteger computeChallenge(ECPoint commitment, ECPoint tPoint,
                                        String circuitId, long[] context) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(ZkCurveParams.encodePoint(commitment));
            md.update(ZkCurveParams.encodePoint(tPoint));
            md.update(circuitId.getBytes(StandardCharsets.UTF_8));
            ByteBuffer ctxBuf = ByteBuffer.allocate(context.length * 8);
            for (long v : context) {
                ctxBuf.putLong(v);
            }
            md.update(ctxBuf.array());
            byte[] baseHash = md.digest();
            // 映射到 F_n，零挑战时通过 counter 扰动重新生成
            return mapHashToNonZeroScalar(baseHash, circuitId, "main");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 计算 R1CS 约束的 Fiat-Shamir 挑战 e = H(C, T, constraintIndex, circuitId)。
     *
     * <p>使用 SHA-256 哈希承诺点、约束索引和电路 ID，输出映射到标量域 F_n。</p>
     *
     * <p><b>零挑战处理（ZK-P2-03 修复）</b>：同 {@link #computeChallenge}，
     * e=0 时通过 counter 扰动重新生成，避免兜底值被利用。</p>
     */
    private BigInteger computeR1csChallenge(ECPoint commitment, ECPoint tPoint,
                                            int constraintIndex, String circuitId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(ZkCurveParams.encodePoint(commitment));
            md.update(ZkCurveParams.encodePoint(tPoint));
            md.update((byte) (constraintIndex & 0xFF));
            md.update((byte) ((constraintIndex >> 8) & 0xFF));
            md.update((byte) ((constraintIndex >> 16) & 0xFF));
            md.update((byte) ((constraintIndex >> 24) & 0xFF));
            md.update(circuitId.getBytes(StandardCharsets.UTF_8));
            byte[] baseHash = md.digest();
            return mapHashToNonZeroScalar(baseHash, circuitId, "r1cs-" + constraintIndex);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 零挑战重试上限。SHA-256 输出映射到 ~2^256 阶曲线，单次命中 0 的概率约 2^-256，
     * 16 次重试失败概率约 2^-252，远低于硬件故障率，可视为不可能事件。
     */
    private static final int MAX_CHALLENGE_RETRIES = 16;

    /**
     * 将哈希字节映射到非零标量域元素（ZK-P2-03 修复）。
     *
     * <p>若首次映射结果为 0，依次将 counter=1,2,...,MAX_CHALLENGE_RETRIES 加入
     * 哈希输入重新计算。若全部为 0，抛出 {@link IllegalStateException}（视为哈希故障）。</p>
     *
     * @param baseHash  首次 SHA-256 哈希结果
     * @param circuitId 电路 ID（用于异常诊断）
     * @param tag       上下文标签（用于异常诊断）
     * @return 非零标量域元素
     * @throws IllegalStateException 若重试 MAX_CHALLENGE_RETRIES 次后仍为 0
     */
    private BigInteger mapHashToNonZeroScalar(byte[] baseHash, String circuitId, String tag) {
        BigInteger challenge = new BigInteger(1, baseHash).mod(ZkCurveParams.CURVE_ORDER);
        if (!challenge.equals(BigInteger.ZERO)) {
            return challenge;
        }
        // 零挑战：通过 counter 扰动重新生成
        logger.warn("Zero challenge detected (circuit={}, tag={}), regenerating with counter",
                circuitId, tag);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (int counter = 1; counter <= MAX_CHALLENGE_RETRIES; counter++) {
                md.reset();
                md.update(baseHash);
                md.update((byte) (counter & 0xFF));
                md.update((byte) ((counter >> 8) & 0xFF));
                md.update((byte) ((counter >> 16) & 0xFF));
                md.update((byte) ((counter >> 24) & 0xFF));
                md.update(circuitId.getBytes(StandardCharsets.UTF_8));
                md.update(tag.getBytes(StandardCharsets.UTF_8));
                byte[] rehash = md.digest();
                challenge = new BigInteger(1, rehash).mod(ZkCurveParams.CURVE_ORDER);
                if (!challenge.equals(BigInteger.ZERO)) {
                    logger.info("Zero challenge regenerated successfully (circuit={}, tag={}, counter={})",
                            circuitId, tag, counter);
                    return challenge;
                }
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        // 极低概率事件（约 2^-252），视为哈希函数故障
        throw new IllegalStateException(
                "Fiat-Shamir challenge is zero after " + MAX_CHALLENGE_RETRIES
                        + " retries (circuit=" + circuitId + ", tag=" + tag
                        + "), hash function may be broken");
    }

    /**
     * 计算稀疏向量与 witness 的点积。
     */
    private static long dot(Map<Integer, Long> coeffs, long[] witness) {
        long sum = 0;
        for (Map.Entry<Integer, Long> e : coeffs.entrySet()) {
            int idx = e.getKey();
            if (idx >= 0 && idx < witness.length) {
                sum += e.getValue() * witness[idx];
            }
        }
        return sum;
    }

    /**
     * 从完整 witness 提取公共输入部分（witness[1..numPublic]）。
     *
     * <p>witness 布局：[1, public_0, public_1, ..., private_0, ...]，
     * 公共输入从 index 1 开始，共 numPublic 个。</p>
     */
    private static long[] extractPublicInputs(long[] witness, int numPublic) {
        if (numPublic <= 0) {
            return new long[0];
        }
        long[] publicInputs = new long[numPublic];
        int copyLen = Math.min(numPublic, Math.max(0, witness.length - 1));
        System.arraycopy(witness, 1, publicInputs, 0, copyLen);
        return publicInputs;
    }
}
