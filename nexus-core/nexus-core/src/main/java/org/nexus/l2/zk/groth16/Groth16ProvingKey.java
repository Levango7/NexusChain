package org.nexus.l2.zk.groth16;

import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Groth16 proving key（证明密钥）。
 *
 * <p>由 {@link Groth16ProofSystem#setup} 生成，供 prover 构造证明。包含：</p>
 * <ul>
 *   <li>{@code alphaG, betaG, deltaG}：α, β, δ 对应的基点倍乘</li>
 *   <li>{@code betaG2, deltaG2}：β, δ 在 G2 上的点（简化版用 G1 替代）</li>
 *   <li>{@code aPoints, bPoints, cPoints}：每条约束的 A_i, B_i, C_i 点</li>
 *   <li>{@code hPoints}：每条约束的 (β·A_i + α·B_i + C_i)/δ 点（用于 prove 计算 C）</li>
 *   <li>{@code toxicWaste}：α, β, γ, δ 标量（仅简化验证用；真实 Groth16 需销毁）</li>
 * </ul>
 *
 * <h3>安全提示</h3>
 * <p>{@code toxicWaste} 字段为本简化版特有，用于在无配对环境下执行验证。
 * 真实 Groth16 实现中此字段必须销毁，验证通过双线性配对完成。</p>
 *
 * @since 1.5
 */
public final class Groth16ProvingKey {

    private final ECPoint alphaG;
    private final ECPoint betaG;
    private final ECPoint deltaG;
    private final ECPoint[] aPoints;
    private final ECPoint[] bPoints;
    private final ECPoint[] cPoints;
    private final ECPoint[] hPoints;
    private final ToxicWaste toxicWaste;

    public Groth16ProvingKey(ECPoint alphaG, ECPoint betaG, ECPoint deltaG,
                             ECPoint[] aPoints, ECPoint[] bPoints, ECPoint[] cPoints,
                             ECPoint[] hPoints, ToxicWaste toxicWaste) {
        this.alphaG = alphaG;
        this.betaG = betaG;
        this.deltaG = deltaG;
        this.aPoints = aPoints;
        this.bPoints = bPoints;
        this.cPoints = cPoints;
        this.hPoints = hPoints;
        this.toxicWaste = toxicWaste;
    }

    public ECPoint getAlphaG() { return alphaG; }
    public ECPoint getBetaG() { return betaG; }
    public ECPoint getDeltaG() { return deltaG; }
    public ECPoint[] getAPoints() { return aPoints; }
    public ECPoint[] getBPoints() { return bPoints; }
    public ECPoint[] getCPoints() { return cPoints; }
    public ECPoint[] getHPoints() { return hPoints; }
    public ToxicWaste getToxicWaste() { return toxicWaste; }

    /**
     * Toxic waste：setup 仪式的随机参数。
     *
     * <p>真实 Groth16 中必须销毁；本简化版保留用于无配对验证。</p>
     */
    public static final class ToxicWaste {
        public final BigInteger alpha;
        public final BigInteger beta;
        public final BigInteger gamma;
        public final BigInteger delta;

        public ToxicWaste(BigInteger alpha, BigInteger beta, BigInteger gamma, BigInteger delta) {
            this.alpha = alpha;
            this.beta = beta;
            this.gamma = gamma;
            this.delta = delta;
        }

        public byte[] encode() {
            return (alpha.toString(16) + ":" + beta.toString(16) + ":"
                    + gamma.toString(16) + ":" + delta.toString(16))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return "ToxicWaste{alpha=*, beta=*, gamma=*, delta=*}";
        }
    }

    public byte[] encode() {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try {
            writePoint(bos, alphaG);
            writePoint(bos, betaG);
            writePoint(bos, deltaG);
            writePointArray(bos, aPoints);
            writePointArray(bos, bPoints);
            writePointArray(bos, cPoints);
            writePointArray(bos, hPoints);
            byte[] tw = toxicWaste.encode();
            bos.write(tw.length & 0xFF);
            bos.write((tw.length >> 8) & 0xFF);
            bos.write(tw);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    private static void writePoint(java.io.OutputStream os, ECPoint p) throws java.io.IOException {
        byte[] enc = ZkCurveParams.encodePoint(p);
        os.write(enc.length & 0xFF);
        os.write((enc.length >> 8) & 0xFF);
        os.write(enc);
    }

    private static void writePointArray(java.io.OutputStream os, ECPoint[] pts) throws java.io.IOException {
        if (pts == null) {
            os.write(0);
            os.write(0);
            return;
        }
        int len = pts.length;
        os.write(len & 0xFF);
        os.write((len >> 8) & 0xFF);
        for (ECPoint p : pts) {
            writePoint(os, p);
        }
    }

    @Override
    public String toString() {
        return "Groth16ProvingKey{constraints=" + (aPoints == null ? 0 : aPoints.length) + '}';
    }
}