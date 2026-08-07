package org.nexus.l2.zk.groth16;

import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;

/**
 * Groth16 verifying key（验证密钥）。
 *
 * <p>由 {@link Groth16ProofSystem#setup} 生成，供 verifier 验证证明。包含：</p>
 * <ul>
 *   <li>{@code alphaG, betaG, gammaG, deltaG}：α, β, γ, δ 对应的基点倍乘</li>
 *   <li>{@code publicInputPoints}：公共输入对应的椭圆曲线点</li>
 *   <li>{@code toxicWaste}：α, β, γ, δ 标量（简化版用；真实 Groth16 不需要）</li>
 * </ul>
 *
 * <h3>安全提示</h3>
 * <p>真实 Groth16 的 verifying key 不含 toxic waste，验证通过双线性配对完成。
 * 本简化版保留 toxic waste 用于在无配对环境下执行验证。</p>
 *
 * @since 1.5
 */
public final class Groth16VerifyingKey {

    private final ECPoint alphaG;
    private final ECPoint betaG;
    private final ECPoint gammaG;
    private final ECPoint deltaG;
    private final ECPoint[] publicInputPoints;
    private final Groth16ProvingKey.ToxicWaste toxicWaste;

    public Groth16VerifyingKey(ECPoint alphaG, ECPoint betaG, ECPoint gammaG, ECPoint deltaG,
                               ECPoint[] publicInputPoints,
                               Groth16ProvingKey.ToxicWaste toxicWaste) {
        this.alphaG = alphaG;
        this.betaG = betaG;
        this.gammaG = gammaG;
        this.deltaG = deltaG;
        this.publicInputPoints = publicInputPoints;
        this.toxicWaste = toxicWaste;
    }

    public ECPoint getAlphaG() { return alphaG; }
    public ECPoint getBetaG() { return betaG; }
    public ECPoint getGammaG() { return gammaG; }
    public ECPoint getDeltaG() { return deltaG; }
    public ECPoint[] getPublicInputPoints() { return publicInputPoints; }
    public Groth16ProvingKey.ToxicWaste getToxicWaste() { return toxicWaste; }

    public byte[] encode() {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try {
            writePoint(bos, alphaG);
            writePoint(bos, betaG);
            writePoint(bos, gammaG);
            writePoint(bos, deltaG);
            if (publicInputPoints == null) {
                bos.write(0);
                bos.write(0);
            } else {
                int len = publicInputPoints.length;
                bos.write(len & 0xFF);
                bos.write((len >> 8) & 0xFF);
                for (ECPoint p : publicInputPoints) {
                    writePoint(bos, p);
                }
            }
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

    @Override
    public String toString() {
        return "Groth16VerifyingKey{publicInputs=" + (publicInputPoints == null ? 0 : publicInputPoints.length) + '}';
    }
}