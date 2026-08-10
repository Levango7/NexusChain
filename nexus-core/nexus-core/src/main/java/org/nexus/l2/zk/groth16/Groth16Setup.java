package org.nexus.l2.zk.groth16;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Groth16 可信设置产物：proving key + verifying key。
 *
 * <p>由 {@link Groth16ProofSystem#setup} 生成，绑定特定电路。
 * proving key 供 prover 使用，verifying key 供 verifier 使用。</p>
 *
 * <h3>安全说明（ZK-P0-02 修复）</h3>
 * <p>自 2.1.1 起，setup 完成后会立即销毁 toxic waste（α, β, γ, δ 标量）。
 * toxic waste 仅在 setup 执行期间临时存在，setup 返回前由
 * {@link #destroyToxicWaste()} 显式清零。proving key 和 verifying key
 * 均不存储 toxic waste，仅存储派生的椭圆曲线点。</p>
 *
 * <p>toxic waste 泄露会破坏 Groth16 可靠性：knowing (α, β, γ, δ) 可伪造
 * 任意 statement 的证明。因此必须确保 setup 后这些标量不可恢复。</p>
 *
 * @since 1.5
 */
public final class Groth16Setup {

    private static final Logger logger = LoggerFactory.getLogger(Groth16Setup.class);

    private final Groth16ProvingKey provingKey;
    private final Groth16VerifyingKey verifyingKey;
    private final int constraintCount;
    private final int witnessSize;
    private final int numPublic;

    /** 临时 toxic waste 引用，setup 完成后由 destroyToxicWaste() 置 null */
    private volatile Groth16ProvingKey.ToxicWaste toxicWasteRef;

    public Groth16Setup(Groth16ProvingKey provingKey, Groth16VerifyingKey verifyingKey,
                        int constraintCount, int witnessSize, int numPublic) {
        this.provingKey = provingKey;
        this.verifyingKey = verifyingKey;
        this.constraintCount = constraintCount;
        this.witnessSize = witnessSize;
        this.numPublic = numPublic;
    }

    public Groth16ProvingKey getProvingKey() { return provingKey; }
    public Groth16VerifyingKey getVerifyingKey() { return verifyingKey; }
    public int getConstraintCount() { return constraintCount; }
    public int getWitnessSize() { return witnessSize; }
    public int getNumPublic() { return numPublic; }

    /**
     * 附加临时 toxic waste 引用（仅供 setup 期间使用）。
     *
     * <p>setup 完成后应立即调用 {@link #destroyToxicWaste()} 销毁。</p>
     *
     * @param waste toxic waste 引用
     */
    void attachToxicWaste(Groth16ProvingKey.ToxicWaste waste) {
        this.toxicWasteRef = waste;
    }

    /**
     * 销毁 toxic waste（ZK-P0-02 修复）。
     *
     * <p>将临时持有的 toxic waste（α, β, γ, δ 标量）显式清零并释放引用。
     * 调用后 toxic waste 不可恢复，proving key 和 verifying key 仅保留
     * 派生的椭圆曲线点参数。</p>
     *
     * <p>此方法幂等，多次调用安全。setup 完成后必须调用此方法。</p>
     */
    public void destroyToxicWaste() {
        if (toxicWasteRef != null) {
            toxicWasteRef.destroy();
            toxicWasteRef = null;
            logger.info("Toxic waste destroyed after setup (constraints={}, witnessSize={})",
                    constraintCount, witnessSize);
        }
    }

    /**
     * 检查 toxic waste 是否已被销毁。
     *
     * @return 已销毁（引用为 null 或 toxic waste 已 destroy）返回 true
     */
    public boolean isToxicWasteDestroyed() {
        return toxicWasteRef == null || toxicWasteRef.isDestroyed();
    }

    @Override
    public String toString() {
        return "Groth16Setup{constraints=" + constraintCount
                + ", witnessSize=" + witnessSize
                + ", numPublic=" + numPublic
                + ", toxicWasteDestroyed=" + isToxicWasteDestroyed() + '}';
    }
}
