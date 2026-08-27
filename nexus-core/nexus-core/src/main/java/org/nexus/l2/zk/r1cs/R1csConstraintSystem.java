package org.nexus.l2.zk.r1cs;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * R1CS 约束系统：一组 R1CS 约束 + witness 变量布局。
 *
 * <p>R1CS 是 ZK 证明系统的标准电路表达。本类聚合一个电路的所有约束，
 * 并维护 witness 向量布局：</p>
 * <pre>
 * w = [1, public_input_0, public_input_1, ..., private_witness_0, private_witness_1, ...]
 *     ^   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *  index 0     [1, 1+numPublic)              [1+numPublic, 1+numPublic+numPrivate)
 * </pre>
 *
 * <h3>用途</h3>
 * <ul>
 *   <li>作为 {@link org.nexus.l2.zk.ZkCircuit#defineCircuit()} 的产物</li>
 *   <li>供 {@link org.nexus.l2.zk.groth16.Groth16ProofSystem} 进行 setup/prove</li>
 *   <li>供 verifier 验证 witness 是否满足所有约束</li>
 * </ul>
 *
 * @since 1.5
 */
public final class R1csConstraintSystem {

    /** 公共输入变量数量 */
    private final int numPublic;

    /** 私密 witness 变量数量 */
    private final int numPrivate;

    /** 所有约束（顺序固定） */
    private final List<R1csConstraint> constraints;

    public R1csConstraintSystem(int numPublic, int numPrivate, List<R1csConstraint> constraints) {
        if (numPublic < 0 || numPrivate < 0) {
            throw new IllegalArgumentException("numPublic/numPrivate must be non-negative");
        }
        this.numPublic = numPublic;
        this.numPrivate = numPrivate;
        this.constraints = constraints == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(constraints));
    }

    public int getNumPublic() {
        return numPublic;
    }

    public int getNumPrivate() {
        return numPrivate;
    }

    /**
     * 返回 witness 向量长度（含常量 1）。
     *
     * @return 1 + numPublic + numPrivate
     */
    public int getWitnessSize() {
        return 1 + numPublic + numPrivate;
    }

    public List<R1csConstraint> getConstraints() {
        return constraints;
    }

    public int getConstraintCount() {
        return constraints.size();
    }

    /**
     * 检查完整 witness 向量是否满足所有约束（BigInteger，A1-R3/ZK-P2-01）。
     *
     * @param witness 完整 witness 向量（长度需等于 {@link #getWitnessSize()}）
     * @return 全部约束成立返回 true
     */
    public boolean isSatisfied(BigInteger[] witness) {
        if (witness == null || witness.length != getWitnessSize()) {
            return false;
        }
        if (!BigInteger.ONE.equals(witness[0])) {
            return false;
        }
        for (R1csConstraint c : constraints) {
            if (!c.isSatisfied(witness)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查完整 witness 向量是否满足所有约束（long 兼容重载）。
     *
     * @param witness 完整 witness 向量（长度需等于 {@link #getWitnessSize()}）
     * @return 全部约束成立返回 true
     * @deprecated 使用 {@link #isSatisfied(BigInteger[])}
     */
    @Deprecated
    public boolean isSatisfied(long[] witness) {
        if (witness == null || witness.length != getWitnessSize()) {
            return false;
        }
        if (witness[0] != 1L) {
            return false;
        }
        for (R1csConstraint c : constraints) {
            if (!c.isSatisfied(witness)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从公共输入和私密 witness 拼装完整 witness 向量（BigInteger，A1-R3/ZK-P2-01）。
     *
     * @param publicInputs 公共输入值（长度需等于 numPublic）
     * @param privateWitness 私密 witness 值（长度需等于 numPrivate）
     * @return 完整 witness 向量
     */
    public BigInteger[] buildWitness(BigInteger[] publicInputs, BigInteger[] privateWitness) {
        if (publicInputs == null || publicInputs.length != numPublic) {
            throw new IllegalArgumentException(
                    "publicInputs length mismatch: expected " + numPublic
                            + ", got " + (publicInputs == null ? 0 : publicInputs.length));
        }
        if (privateWitness == null || privateWitness.length != numPrivate) {
            throw new IllegalArgumentException(
                    "privateWitness length mismatch: expected " + numPrivate
                            + ", got " + (privateWitness == null ? 0 : privateWitness.length));
        }
        BigInteger[] w = new BigInteger[getWitnessSize()];
        w[0] = BigInteger.ONE;
        System.arraycopy(publicInputs, 0, w, 1, numPublic);
        System.arraycopy(privateWitness, 0, w, 1 + numPublic, numPrivate);
        return w;
    }

    /**
     * 从公共输入和私密 witness 拼装完整 witness 向量（long 兼容重载）。
     *
     * @param publicInputs 公共输入值（长度需等于 numPublic）
     * @param privateWitness 私密 witness 值（长度需等于 numPrivate）
     * @return 完整 witness 向量
     * @deprecated 使用 {@link #buildWitness(BigInteger[], BigInteger[])}
     */
    @Deprecated
    public long[] buildWitness(long[] publicInputs, long[] privateWitness) {
        if (publicInputs == null || publicInputs.length != numPublic) {
            throw new IllegalArgumentException(
                    "publicInputs length mismatch: expected " + numPublic
                            + ", got " + (publicInputs == null ? 0 : publicInputs.length));
        }
        if (privateWitness == null || privateWitness.length != numPrivate) {
            throw new IllegalArgumentException(
                    "privateWitness length mismatch: expected " + numPrivate
                            + ", got " + (privateWitness == null ? 0 : privateWitness.length));
        }
        long[] w = new long[getWitnessSize()];
        w[0] = 1L;
        System.arraycopy(publicInputs, 0, w, 1, numPublic);
        System.arraycopy(privateWitness, 0, w, 1 + numPublic, numPrivate);
        return w;
    }

    @Override
    public String toString() {
        return "R1csConstraintSystem{numPublic=" + numPublic
                + ", numPrivate=" + numPrivate
                + ", constraints=" + getConstraintCount() + '}';
    }
}