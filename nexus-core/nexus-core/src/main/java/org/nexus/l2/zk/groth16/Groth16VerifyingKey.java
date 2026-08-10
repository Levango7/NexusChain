package org.nexus.l2.zk.groth16;

import org.bouncycastle.math.ec.ECPoint;

/**
 * Groth16 verifying key（验证密钥）。
 *
 * <p>由 {@link Groth16ProofSystem#setup} 生成，供 verifier 验证证明。包含：</p>
 * <ul>
 *   <li>{@code alphaG, betaG, gammaG, deltaG}：α, β, γ, δ 对应的基点倍乘</li>
 *   <li>{@code publicInputPoints}：公共输入对应的椭圆曲线点</li>
 * </ul>
 *
 * <h3>安全说明（ZK-P0-02 修复）</h3>
 * <p>自 2.1.1 起，<b>verifying key 不再存储 toxic waste（α, β, γ, δ 标量）</b>。
 * 真实 Groth16 的 verifying key 仅包含椭圆曲线点形式的参数，验证通过双线性配对完成。
 * 本简化版保留椭圆曲线点形式的参数，不存储任何标量形式的 toxic waste。</p>
 *
 * @since 1.5
 */
public final class Groth16VerifyingKey {

    private final ECPoint alphaG;
    private final ECPoint betaG;
    private final ECPoint gammaG;
    private final ECPoint deltaG;
    private final ECPoint[] publicInputPoints;

    /**
     * 构造 verifying key（不含 toxic waste）。
     *
     * @param alphaG           α 对应的基点倍乘 [α]_1
     * @param betaG            β 对应的基点倍乘 [β]_1
     * @param gammaG           γ 对应的基点倍乘 [γ]_1
     * @param deltaG           δ 对应的基点倍乘 [δ]_1
     * @param publicInputPoints 公共输入对应的椭圆曲线点
     */
    public Groth16VerifyingKey(ECPoint alphaG, ECPoint betaG, ECPoint gammaG, ECPoint deltaG,
                               ECPoint[] publicInputPoints) {
        this.alphaG = alphaG;
        this.betaG = betaG;
        this.gammaG = gammaG;
        this.deltaG = deltaG;
        this.publicInputPoints = publicInputPoints;
    }

    public ECPoint getAlphaG() { return alphaG; }
    public ECPoint getBetaG() { return betaG; }
    public ECPoint getGammaG() { return gammaG; }
    public ECPoint getDeltaG() { return deltaG; }
    public ECPoint[] getPublicInputPoints() { return publicInputPoints; }

    /**
     * 将 verifying key 编码为字节数组（不含 toxic waste）。
     *
     * @return 编码字节
     */
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
        return "Groth16VerifyingKey{publicInputs=" + (publicInputPoints == null ? 0 : publicInputPoints.length)
                + ", toxicWaste=NOT_STORED}";
    }
}
