package org.nexus.l2.zk.groth16;

/**
 * Groth16 可信设置产物：proving key + verifying key。
 *
 * <p>由 {@link Groth16ProofSystem#setup} 生成，绑定特定电路。
 * proving key 供 prover 使用，verifying key 供 verifier 使用。</p>
 *
 * @since 1.5
 */
public final class Groth16Setup {

    private final Groth16ProvingKey provingKey;
    private final Groth16VerifyingKey verifyingKey;
    private final int constraintCount;
    private final int witnessSize;
    private final int numPublic;

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

    @Override
    public String toString() {
        return "Groth16Setup{constraints=" + constraintCount
                + ", witnessSize=" + witnessSize
                + ", numPublic=" + numPublic + '}';
    }
}