package org.nexus.l2.zk.r1cs;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * R1CS（Rank-1 Constraint System）单条约束。
 *
 * <p>R1CS 是 ZK 证明系统（Groth16、Plonk 等）的标准电路表达形式。
 * 每条约束形式为 {@code (A·w) * (B·w) = (C·w)}，其中 {@code w} 是 witness 向量
 * （含公共输入），A/B/C 是稀疏系数向量。</p>
 *
 * <h3>语义</h3>
 * <p>给定 witness 向量 {@code w = [1, public_inputs..., private_witness...]}，
 * 约束成立当且仅当 {@code dot(A, w) * dot(B, w) = dot(C, w)}。</p>
 *
 * <h3>示例</h3>
 * <p>乘法约束 {@code x * y = z}（w = [1, x, y, z]）：</p>
 * <pre>
 * A = {1: 1}      // x
 * B = {2: 1}      // y
 * C = {3: 1}      // z
 * </pre>
 *
 * @since 1.5
 */
public final class R1csConstraint {

    /** A 向量稀疏系数（witnessIndex → coefficient） */
    private final Map<Integer, Long> a;
    /** B 向量稀疏系数 */
    private final Map<Integer, Long> b;
    /** C 向量稀疏系数 */
    private final Map<Integer, Long> c;

    public R1csConstraint(Map<Integer, Long> a, Map<Integer, Long> b, Map<Integer, Long> c) {
        this.a = freeze(a);
        this.b = freeze(b);
        this.c = freeze(c);
    }

    /**
     * 构造简单乘法约束 {@code varA * varB = varC}。
     *
     * @param varA witness 索引
     * @param varB witness 索引
     * @param varC witness 索引
     * @return 乘法约束
     */
    public static R1csConstraint multiplication(int varA, int varB, int varC) {
        return new R1csConstraint(
                Collections.singletonMap(varA, 1L),
                Collections.singletonMap(varB, 1L),
                Collections.singletonMap(varC, 1L));
    }

    /**
     * 构造常量约束 {@code var = constant}。
     *
     * @param var      witness 索引
     * @param constant 常量值
     * @return 常量约束
     */
    public static R1csConstraint constant(int var, long constant) {
        // (1) * (var) = (constant * 1)  →  var = constant
        return new R1csConstraint(
                Collections.singletonMap(0, 1L),
                Collections.singletonMap(var, 1L),
                Collections.singletonMap(0, constant));
    }

    /**
     * 构造线性约束 {@code sum(coef_i * var_i) = 0}（即 A·w = 0，B = 1）。
     *
     * @param linearCoeffs 线性系数（witnessIndex → coefficient）
     * @return 线性约束
     */
    public static R1csConstraint linear(Map<Integer, Long> linearCoeffs) {
        return new R1csConstraint(
                linearCoeffs,
                Collections.singletonMap(0, 1L),
                Collections.emptyMap());
    }

    public Map<Integer, Long> getA() {
        return a;
    }

    public Map<Integer, Long> getB() {
        return b;
    }

    public Map<Integer, Long> getC() {
        return c;
    }

    /**
     * 计算稀疏向量与 witness 的点积。
     *
     * @param coeffs  稀疏系数
     * @param witness witness 向量
     * @return 点积结果
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
     * 检查约束是否对给定 witness 成立。
     *
     * @param witness witness 向量
     * @return 约束成立返回 true
     */
    public boolean isSatisfied(long[] witness) {
        long av = dot(a, witness);
        long bv = dot(b, witness);
        long cv = dot(c, witness);
        return av * bv == cv;
    }

    private static Map<Integer, Long> freeze(Map<Integer, Long> src) {
        if (src == null || src.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new TreeMap<>(src));
    }

    @Override
    public String toString() {
        return "R1csConstraint{A=" + a + ", B=" + b + ", C=" + c + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof R1csConstraint)) return false;
        R1csConstraint that = (R1csConstraint) o;
        return a.equals(that.a) && b.equals(that.b) && c.equals(that.c);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{a, b, c});
    }
}