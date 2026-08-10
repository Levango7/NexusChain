package org.nexus.l2.zk.groth16;

import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;

/**
 * Groth16 proving key（证明密钥）。
 *
 * <p>由 {@link Groth16ProofSystem#setup} 生成，供 prover 构造证明。包含：</p>
 * <ul>
 *   <li>{@code alphaG, betaG, deltaG}：α, β, δ 对应的基点倍乘</li>
 *   <li>{@code aPoints, bPoints, cPoints}：每条约束的 A_i, B_i, C_i 点</li>
 *   <li>{@code hPoints}：每条约束的 (β·A_i + α·B_i + C_i)/δ 点（用于 prove 计算 C）</li>
 * </ul>
 *
 * <h3>安全说明（ZK-P0-02 修复）</h3>
 * <p>自 2.1.1 起，<b>proving key 不再存储 toxic waste（α, β, γ, δ 标量）</b>。
 * 真实 Groth16 实现中 toxic waste 必须在 setup 仪式后销毁，仅保留派生的椭圆曲线点。
 * 保留 toxic waste 会使持有 proving key 的 prover 能伪造任意证明，破坏可靠性。
 * 本类仅存储 prover 所需的派生参数（椭圆曲线点），不存储任何标量形式的 toxic waste。</p>
 *
 * <p>{@link ToxicWaste} 类保留用于 setup 过程中的临时传递，setup 完成后由
 * {@link Groth16Setup#destroyToxicWaste()} 显式销毁。</p>
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

    /**
     * 构造 proving key（不含 toxic waste）。
     *
     * @param alphaG   α 对应的基点倍乘 [α]_1
     * @param betaG    β 对应的基点倍乘 [β]_1
     * @param deltaG   δ 对应的基点倍乘 [δ]_1
     * @param aPoints  每条约束的 A_i 点
     * @param bPoints  每条约束的 B_i 点
     * @param cPoints  每条约束的 C_i 点
     * @param hPoints  每条约束的 H_i = (β·A_i + α·B_i + C_i)/δ 点
     */
    public Groth16ProvingKey(ECPoint alphaG, ECPoint betaG, ECPoint deltaG,
                             ECPoint[] aPoints, ECPoint[] bPoints, ECPoint[] cPoints,
                             ECPoint[] hPoints) {
        this.alphaG = alphaG;
        this.betaG = betaG;
        this.deltaG = deltaG;
        this.aPoints = aPoints;
        this.bPoints = bPoints;
        this.cPoints = cPoints;
        this.hPoints = hPoints;
    }

    public ECPoint getAlphaG() { return alphaG; }
    public ECPoint getBetaG() { return betaG; }
    public ECPoint getDeltaG() { return deltaG; }
    public ECPoint[] getAPoints() { return aPoints; }
    public ECPoint[] getBPoints() { return bPoints; }
    public ECPoint[] getCPoints() { return cPoints; }
    public ECPoint[] getHPoints() { return hPoints; }

    /**
     * Toxic waste：setup 仪式的随机参数（α, β, γ, δ 标量）。
     *
     * <p>此类仅在 {@link Groth16ProofSystem#setup} 执行期间用于临时传递参数，
     * setup 完成后必须由 {@link Groth16Setup#destroyToxicWaste()} 销毁。
     * <b>proving key 和 verifying key 均不存储此对象。</b></p>
     *
     * <h3>安全提示（ZK-P0-02）</h3>
     * <p>toxic waste 泄露会破坏 Groth16 的可靠性： knowing (α, β, γ, δ) 可伪造
     * 任意 statement 的证明。因此 setup 仪式结束后必须立即销毁。</p>
     */
    public static final class ToxicWaste {
        /** α 标量（setup 后必须销毁） */
        public volatile BigInteger alpha;
        /** β 标量（setup 后必须销毁） */
        public volatile BigInteger beta;
        /** γ 标量（setup 后必须销毁） */
        public volatile BigInteger gamma;
        /** δ 标量（setup 后必须销毁） */
        public volatile BigInteger delta;

        public ToxicWaste(BigInteger alpha, BigInteger beta, BigInteger gamma, BigInteger delta) {
            this.alpha = alpha;
            this.beta = beta;
            this.gamma = gamma;
            this.delta = delta;
        }

        /**
         * 销毁 toxic waste：将所有标量置 null。
         *
         * <p>调用后此对象不再持有任何 toxic waste 信息。
         * 多次调用安全（幂等）。</p>
         */
        public void destroy() {
            this.alpha = null;
            this.beta = null;
            this.gamma = null;
            this.delta = null;
        }

        /**
         * 检查 toxic waste 是否已被销毁。
         *
         * @return 已销毁（所有标量为 null）返回 true
         */
        public boolean isDestroyed() {
            return alpha == null && beta == null && gamma == null && delta == null;
        }

        public byte[] encode() {
            if (isDestroyed()) {
                return new byte[]{0};
            }
            return (alpha.toString(16) + ":" + beta.toString(16) + ":"
                    + gamma.toString(16) + ":" + delta.toString(16))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return isDestroyed()
                    ? "ToxicWaste{DESTROYED}"
                    : "ToxicWaste{alpha=*, beta=*, gamma=*, delta=*}";
        }
    }

    /**
     * 将 proving key 编码为字节数组（不含 toxic waste）。
     *
     * @return 编码字节
     */
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
        return "Groth16ProvingKey{constraints=" + (aPoints == null ? 0 : aPoints.length)
                + ", toxicWaste=NOT_STORED}";
    }
}
