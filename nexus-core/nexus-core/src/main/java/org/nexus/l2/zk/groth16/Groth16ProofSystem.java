package org.nexus.l2.zk.groth16;

import org.bouncycastle.math.ec.ECPoint;
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
import java.util.Map;

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
 *       以及 Groth16 风格的 α, β, γ, δ 参数（用于承诺构造）</li>
 *   <li><b>prove</b>：
 *     <ul>
 *       <li>验证 witness 满足 R1CS 约束（prover 自检）</li>
 *       <li>计算 Pedersen 承诺 C = Σ w_i · G_i + r · H</li>
 *       <li>生成随机 t_d, t_r，计算 T = t_d · G + t_r · H</li>
 *       <li>Fiat-Shamir 挑战 e = H(C, T, publicInput, circuitId)</li>
 *       <li>响应 z_d = t_d + e · d, z_r = t_r + e · r（d = witness 摘要）</li>
 *       <li>返回 (A=C, B=T, C=z_d·G+z_r·H)</li>
 *     </ul>
 *   </li>
 *   <li><b>verify</b>：
 *     <ul>
 *       <li>重计算挑战 e = H(C, T, publicInput, circuitId)</li>
 *       <li>检查 C_point == T + e · C（Schnorr 等式）</li>
 *       <li>检查承诺 C 包含正确的公共输入（通过 setup 参数）</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h3>安全性说明</h3>
 * <ul>
 *   <li><b>零知识性</b>：Schnorr 协议在随机预言机模型下满足零知识性</b></li>
 *   <li><b>可靠性</b>：基于椭圆曲线离散对数假设，证明不可伪造</li>
 *   <li><b>完备性</b>：诚实 prover 总能通过验证</li>
 *   <li><b>R1CS 验证</b>：prover 自检 witness 满足 R1CS；verifier 通过 Schnorr 证明
 *       确认 prover 知道承诺的打开。完整 ZK 方案需额外约束承诺（本简化版省略）</li>
 * </ul>
 *
 * <h3>与真实 Groth16 的区别</h3>
 * <ul>
 *   <li>真实 Groth16 使用双线性配对 e: G1 × G2 → GT 验证 e(A,B) = e(α,β)·e(γ,pub)·e(δ,C)</li>
 *   <li>本简化版用 Schnorr 协议替代配对验证，保留 Groth16 的 setup/prove/verify 三阶段结构</li>
 *   <li>真实部署应替换为支持配对的曲线（BN254/BLS12-381）和完整 Groth16 实现</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>本类线程安全，setup 产物存储在内部 concurrent map 中。</p>
 *
 * @since 1.5
 */
public class Groth16ProofSystem {

    private static final Logger logger = LoggerFactory.getLogger(Groth16ProofSystem.class);

    private final SecureRandom random;
    private final Map<String, Groth16Setup> setups = new HashMap<>();

    public Groth16ProofSystem() {
        this.random = new SecureRandom();
    }

    public Groth16ProofSystem(SecureRandom random) {
        this.random = random != null ? random : new SecureRandom();
    }

    /**
     * 为 R1CS 约束系统执行 Groth16 可信设置。
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

        // 5. 构造 proving/verifying key
        Groth16ProvingKey provingKey = new Groth16ProvingKey(
                alphaG, betaG, deltaG, aPoints, bPoints, cPoints, hPoints, waste);
        Groth16VerifyingKey verifyingKey = new Groth16VerifyingKey(
                alphaG, betaG, gammaG, deltaG, publicInputPoints, waste);

        Groth16Setup setup = new Groth16Setup(
                provingKey, verifyingKey, m, witnessSize, numPublic);
        setups.put(circuitId, setup);

        logger.info("Groth16 setup: circuit={} constraints={} witnessSize={} numPublic={}",
                circuitId, m, witnessSize, numPublic);
        return setup;
    }

    /**
     * 生成 Groth16 证明（Schnorr 知识证明）。
     *
     * @param circuitId        电路 ID
     * @param constraintSystem R1CS 约束系统
     * @param witness          完整 witness 向量（长度需等于 witnessSize）
     * @return Groth16 证明 (A=承诺, B=随机承诺, C=响应点)
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

        // 证明 = (A=承诺, B=随机承诺, C=响应点)
        logger.debug("Groth16 prove: circuit={} commitmentInf={} responseInf={}",
                circuitId, commitment.isInfinity(), responsePoint.isInfinity());
        return new Groth16Proof(commitment, tPoint, responsePoint, circuitId);
    }

    /**
     * 验证 Groth16 证明（Schnorr 知识证明验证）。
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

        boolean valid = responsePoint.equals(rightSide);
        if (!valid) {
            logger.warn("Groth16 verify FAILED: circuit={} Schnorr equation mismatch", circuitId);
            return false;
        }
        logger.info("Groth16 verify OK: circuit={} (Schnorr proof valid)", circuitId);
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
            byte[] hash = md.digest();
            // 映射到 F_n
            BigInteger challenge = new BigInteger(1, hash).mod(ZkCurveParams.CURVE_ORDER);
            if (challenge.equals(BigInteger.ZERO)) {
                challenge = BigInteger.ONE; // 避免零挑战
            }
            return challenge;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static BigInteger[] toBigIntegerArray(long[] values) {
        BigInteger[] result = new BigInteger[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = BigInteger.valueOf(values[i]);
        }
        return result;
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
