package org.nexus.l2.zk.r1cs;

import java.math.BigInteger;
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
 * <h3>BigInteger 化（A1-R3，ZK-P2-01）</h3>
 * <p>自 v2.41 起系数与 witness 均使用 {@link BigInteger} 承载，解除
 * 64 位 {@code long} 编码对 256 位状态根的精度限制（ZK-P2-01）。
 * 原有 {@code long} 重载保留为 {@code @Deprecated} 兼容桥（内部转 BigInteger）。</p>
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
    private final Map<Integer, BigInteger> a;
    /** B 向量稀疏系数 */
    private final Map<Integer, BigInteger> b;
    /** C 向量稀疏系数 */
    private final Map<Integer, BigInteger> c;

    public R1csConstraint(Map<Integer, BigInteger> a, Map<Integer, BigInteger> b, Map<Integer, BigInteger> c) {
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
                Collections.singletonMap(varA, BigInteger.ONE),
                Collections.singletonMap(varB, BigInteger.ONE),
                Collections.singletonMap(varC, BigInteger.ONE));
    }

    /**
     * 构造常量约束 {@code var = constant}。
     *
     * @param var      witness 索引
     * @param constant 常量值（long，兼容重载）
     * @return 常量约束
     * @deprecated 使用 {@link #constant(int, BigInteger)}
     */
    @Deprecated
    public static R1csConstraint constant(int var, long constant) {
        return constant(var, BigInteger.valueOf(constant));
    }

    /**
     * 构造常量约束 {@code var = constant}。
     *
     * @param var      witness 索引
     * @param constant 常量值（BigInteger）
     * @return 常量约束
     */
    public static R1csConstraint constant(int var, BigInteger constant) {
        // (1) * (var) = (constant * 1)  →  var = constant
        return new R1csConstraint(
                Collections.singletonMap(0, BigInteger.ONE),
                Collections.singletonMap(var, BigInteger.ONE),
                Collections.singletonMap(0, constant));
    }

    /**
     * 构造线性约束 {@code sum(coef_i * var_i) = 0}（即 A·w = 0，B = 1）。
     *
     * @param linearCoeffs 线性系数（witnessIndex → coefficient，long 兼容重载）
     * @return 线性约束
     * @deprecated 使用 {@link #linear(Map)}（BigInteger 版本）
     */
    @Deprecated
    public static R1csConstraint linearLegacy(Map<Integer, Long> linearCoeffs) {
        Map<Integer, BigInteger> big = new TreeMap<>();
        for (Map.Entry<Integer, Long> e : linearCoeffs.entrySet()) {
            big.put(e.getKey(), BigInteger.valueOf(e.getValue()));
        }
        return linear(big);
    }

    /**
     * 构造线性约束 {@code sum(coef_i * var_i) = 0}（即 A·w = 0，B = 1）。
     *
     * @param linearCoeffs 线性系数（witnessIndex → coefficient，BigInteger）
     * @return 线性约束
     */
    public static R1csConstraint linear(Map<Integer, BigInteger> linearCoeffs) {
        return new R1csConstraint(
                linearCoeffs,
                Collections.singletonMap(0, BigInteger.ONE),
                Collections.emptyMap());
    }

    public Map<Integer, BigInteger> getA() {
        return a;
    }

    public Map<Integer, BigInteger> getB() {
        return b;
    }

    public Map<Integer, BigInteger> getC() {
        return c;
    }

    /**
     * 计算稀疏向量与 witness 的点积（BigInteger，无精度损失）。
     *
     * @param coeffs  稀疏系数
     * @param witness witness 向量
     * @return 点积结果
     */
    private static BigInteger dot(Map<Integer, BigInteger> coeffs, BigInteger[] witness) {
        BigInteger sum = BigInteger.ZERO;
        for (Map.Entry<Integer, BigInteger> e : coeffs.entrySet()) {
            int idx = e.getKey();
            if (idx >= 0 && idx < witness.length) {
                sum = sum.add(e.getValue().multiply(witness[idx]));
            }
        }
        return sum;
    }

    /**
     * 计算稀疏向量与 witness 的点积（long 兼容重载）。
     *
     * @param coeffs  稀疏系数（long）
     * @param witness witness 向量（long）
     * @return 点积结果
     * @deprecated 使用 BigInteger 重载
     */
    @Deprecated
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
     * 检查约束是否对给定 witness 成立（BigInteger）。
     *
     * @param witness witness 向量
     * @return 约束成立返回 true
     */
    public boolean isSatisfied(BigInteger[] witness) {
        BigInteger av = dot(a, witness);
        BigInteger bv = dot(b, witness);
        BigInteger cv = dot(c, witness);
        return av.multiply(bv).equals(cv);
    }

    /**
     * 检查约束是否对给定 witness 成立（long 兼容重载）。
     *
     * @param witness witness 向量
     * @return 约束成立返回 true
     * @deprecated 使用 {@link #isSatisfied(BigInteger[])}
     */
    @Deprecated
    public boolean isSatisfied(long[] witness) {
        long av = dot(legacyCoeffs(a), witness);
        long bv = dot(legacyCoeffs(b), witness);
        long cv = dot(legacyCoeffs(c), witness);
        return av * bv == cv;
    }

    private static Map<Integer, Long> legacyCoeffs(Map<Integer, BigInteger> src) {
        Map<Integer, Long> out = new TreeMap<>();
        for (Map.Entry<Integer, BigInteger> e : src.entrySet()) {
            out.put(e.getKey(), e.getValue().longValue());
        }
        return out;
    }

    private static Map<Integer, BigInteger> freeze(Map<Integer, BigInteger> src) {
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
