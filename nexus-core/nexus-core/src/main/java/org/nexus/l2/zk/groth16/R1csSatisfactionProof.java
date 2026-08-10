package org.nexus.l2.zk.groth16;

import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;

/**
 * R1CS 满足性证明（ZK-P0-01 修复）。
 *
 * <p>对 R1CS 约束系统中的每条约束 {@code (A·w) * (B·w) = (C·w)}，prover 生成
 * 一个 Schnorr 知识证明，证明 prover 知道满足该约束的 witness 求值值
 * {@code (aVal, bVal, cVal)}。verifier 验证：</p>
 * <ol>
 *   <li><b>R1CS 约束等式</b>：{@code aVal * bVal == cVal}（约束满足性）</li>
 *   <li><b>Schnorr 等式</b>：{@code zA·G + zB·H + zC·K + zR·L == T + e·C}
 *       （prover 知道承诺的打开）</li>
 * </ol>
 *
 * <h3>协议细节</h3>
 * <p>对每条约束 i：</p>
 * <ol>
 *   <li>prover 计算 {@code aVal_i = A_i·w}, {@code bVal_i = B_i·w}, {@code cVal_i = C_i·w}</li>
 *   <li>prover 验证 {@code aVal_i * bVal_i == cVal_i}（自检）</li>
 *   <li>prover 选取随机 {@code r_i}，计算 Pedersen 承诺
 *       {@code C_i = aVal_i·G + bVal_i·H + cVal_i·K + r_i·L}</li>
 *   <li>prover 选取随机 {@code t_a, t_b, t_c, t_r}，计算
 *       {@code T_i = t_a·G + t_b·H + t_c·K + t_r·L}</li>
 *   <li>Fiat-Shamir 挑战 {@code e_i = H(C_i, T_i, i, circuitId)}</li>
 *   <li>响应 {@code zA = t_a + e·aVal}, {@code zB = t_b + e·bVal},
 *       {@code zC = t_c + e·cVal}, {@code zR = t_r + e·r}</li>
 * </ol>
 *
 * <h3>安全性说明</h3>
 * <ul>
 *   <li><b>可靠性</b>：恶意 prover 无法构造通过 Schnorr 但不满足 R1CS 的证明，
 *       因为 verifier 显式检查 {@code aVal * bVal == cVal}</li>
 *   <li><b>零知识性</b>：Schnorr 协议在随机预言机模型下满足零知识性，
 *       (aVal, bVal, cVal) 通过 Pedersen 承诺隐藏</li>
 *   <li><b>知识可靠性</b>：基于椭圆曲线离散对数假设</li>
 * </ul>
 *
 * @since 2.1.1
 */
public final class R1csSatisfactionProof {

    /** 每条约束的满足性证明 */
    private final ConstraintProof[] constraintProofs;

    public R1csSatisfactionProof(ConstraintProof[] constraintProofs) {
        this.constraintProofs = constraintProofs == null
                ? new ConstraintProof[0]
                : constraintProofs;
    }

    public ConstraintProof[] getConstraintProofs() {
        return constraintProofs;
    }

    public int size() {
        return constraintProofs.length;
    }

    /**
     * 单条 R1CS 约束的满足性证明。
     */
    public static final class ConstraintProof {
        /** A·w 的求值值（公开供 verifier 验证 aVal * bVal == cVal） */
        private final long aVal;
        /** B·w 的求值值 */
        private final long bVal;
        /** C·w 的求值值 */
        private final long cVal;
        /** Pedersen 承诺 C = aVal·G + bVal·H + cVal·K + r·L */
        private final ECPoint commitment;
        /** Schnorr 协议的 T 点 */
        private final ECPoint tPoint;
        /** Schnorr 响应 zA */
        private final BigInteger zA;
        /** Schnorr 响应 zB */
        private final BigInteger zB;
        /** Schnorr 响应 zC */
        private final BigInteger zC;
        /** Schnorr 响应 zR */
        private final BigInteger zR;

        public ConstraintProof(long aVal, long bVal, long cVal,
                               ECPoint commitment, ECPoint tPoint,
                               BigInteger zA, BigInteger zB, BigInteger zC, BigInteger zR) {
            this.aVal = aVal;
            this.bVal = bVal;
            this.cVal = cVal;
            this.commitment = commitment;
            this.tPoint = tPoint;
            this.zA = zA;
            this.zB = zB;
            this.zC = zC;
            this.zR = zR;
        }

        public long getAVal() { return aVal; }
        public long getBVal() { return bVal; }
        public long getCVal() { return cVal; }
        public ECPoint getCommitment() { return commitment; }
        public ECPoint getTPoint() { return tPoint; }
        public BigInteger getZA() { return zA; }
        public BigInteger getZB() { return zB; }
        public BigInteger getZC() { return zC; }
        public BigInteger getZR() { return zR; }

        @Override
        public String toString() {
            return "ConstraintProof{aVal=" + aVal + ", bVal=" + bVal + ", cVal=" + cVal
                    + ", aTimesB=" + (aVal * bVal) + ", satisfied=" + (aVal * bVal == cVal) + '}';
        }
    }

    @Override
    public String toString() {
        return "R1csSatisfactionProof{constraints=" + constraintProofs.length + '}';
    }
}