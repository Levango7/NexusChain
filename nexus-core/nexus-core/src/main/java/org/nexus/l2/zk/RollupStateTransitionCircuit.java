package org.nexus.l2.zk;

import org.nexus.l2.zk.r1cs.R1csConstraint;
import org.nexus.l2.zk.r1cs.R1csConstraintSystem;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Rollup 状态转换电路实现。
 *
 * <p>定义 ZK Rollup 的核心电路：证明一批 L2 交易的状态转换合法，
 * 即从 {@code preStateRoot} 应用交易后得到 {@code postStateRoot}。
 * witness 包含每笔交易的执行 trace，public input 包含前后状态根与批次数据哈希。</p>
 *
 * <h3>R1CS 约束（简化模型）</h3>
 * <p>真实 Rollup 电路涉及 EVM 状态机、Merkle Patricia Trie 更新等复杂约束。
 * 本实现采用简化模型，证明状态转换的代数关系：</p>
 * <pre>
 * witness w = [1, preStateRoot, postStateRoot, batchDataHash,
 *              txEffect_1, ..., txEffect_n, sumEffects]
 *
 * 约束：
 *   C1: sumEffects = Σ txEffect_i          (线性约束：状态变更累加)
 *   C2: postStateRoot - preStateRoot = sumEffects  (线性约束：状态守恒)
 *   C3..: 每笔交易的 binary/范围约束（简化为占位）
 * </pre>
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li>公共输入（numPublic=3）：preStateRoot, postStateRoot, batchDataHash</li>
 *   <li>私密 witness（numPrivate=maxBatchSize+1）：txEffect_1..n, sumEffects</li>
 *   <li>约束数：2 + maxBatchSize（线性累加 + 状态守恒 + 每笔交易占位约束）</li>
 *   <li>状态根以 long 编码（真实实现应用域元素）</li>
 * </ul>
 *
 * <h3>扩展点</h3>
 * <p>真实实现应替换 {@link #buildR1cs()} 为完整 EVM 状态机约束，
 * 包含：交易执行步骤、状态根逐笔更新、gas 累计、nonce 单调递增等。
 * 当前简化版保证代数正确性，可被 Groth16 证明系统处理。</p>
 *
 * @since 1.5
 */
public class RollupStateTransitionCircuit implements ZkCircuit {

    /** 电路 ID */
    private static final String CIRCUIT_ID = "rollup-state-transition-v1";

    /** 公共输入字段 schema */
    private static final List<String> PUBLIC_INPUT_SCHEMA =
            Collections.unmodifiableList(Arrays.asList(
                    "preStateRoot", "postStateRoot", "batchDataHash", "l1BlockNumber"));

    /** 公共输入数量（preStateRoot, postStateRoot, batchDataHash） */
    private static final int NUM_PUBLIC = 3;

    /** 批次最大交易数（影响电路规模） */
    private final int maxBatchSize;

    /** 缓存的 R1CS 约束系统 */
    private volatile R1csConstraintSystem cachedR1cs;

    /**
     * 构造电路。
     *
     * @param maxBatchSize 批次最大交易数
     */
    public RollupStateTransitionCircuit(int maxBatchSize) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        this.maxBatchSize = maxBatchSize;
    }

    public RollupStateTransitionCircuit() {
        this(1000);
    }

    @Override
    public String getCircuitId() {
        return CIRCUIT_ID;
    }

    @Override
    public int defineCircuit() {
        // 约束数 = 2（累加 + 状态守恒） + maxBatchSize（每笔交易占位约束）
        return 2 + maxBatchSize;
    }

    @Override
    public byte[] synthesize(byte[] witness) {
        // 真实实现：执行 witness assignment 并校验所有约束
        // 此处保留占位行为，供骨架 prover 使用；R1CS 路径走 buildR1cs()
        int len = witness == null ? 0 : witness.length;
        byte[] assignment = new byte[Math.max(32, len)];
        if (witness != null) {
            System.arraycopy(witness, 0, assignment, 0, Math.min(witness.length, assignment.length));
        }
        return assignment;
    }

    @Override
    public List<String> getPublicInputSchema() {
        return PUBLIC_INPUT_SCHEMA;
    }

    /**
     * 构建 R1CS 约束系统。
     *
     * <p>witness 布局：w = [1, preStateRoot, postStateRoot, batchDataHash,
     * txEffect_1, ..., txEffect_n, sumEffects]</p>
     * <ul>
     *   <li>index 0: 常量 1</li>
     *   <li>index 1: preStateRoot（公共）</li>
     *   <li>index 2: postStateRoot（公共）</li>
     *   <li>index 3: batchDataHash（公共）</li>
     *   <li>index 4..4+n-1: txEffect_i（私密）</li>
     *   <li>index 4+n: sumEffects（私密）</li>
     * </ul>
     *
     * @return R1CS 约束系统
     */
    @Override
    public R1csConstraintSystem buildR1cs() {
        if (cachedR1cs != null) {
            return cachedR1cs;
        }
        synchronized (this) {
            if (cachedR1cs != null) {
                return cachedR1cs;
            }
            List<R1csConstraint> constraints = new ArrayList<>(2 + maxBatchSize);

            int sumIndex = 4 + maxBatchSize; // sumEffects 的 witness 索引

            // C1: sumEffects = Σ txEffect_i
            // 表达为线性约束：sumEffects - Σ txEffect_i = 0
            // R1CS 形式：(sumEffects - Σ txEffect_i) * 1 = 0
            java.util.Map<Integer, Long> c1Coeffs = new java.util.TreeMap<>();
            c1Coeffs.put(sumIndex, 1L);
            for (int i = 0; i < maxBatchSize; i++) {
                c1Coeffs.put(4 + i, -1L);
            }
            constraints.add(R1csConstraint.linear(c1Coeffs));

            // C2: postStateRoot - preStateRoot - sumEffects = 0
            java.util.Map<Integer, Long> c2Coeffs = new java.util.TreeMap<>();
            c2Coeffs.put(2, 1L);   // postStateRoot
            c2Coeffs.put(1, -1L);  // preStateRoot
            c2Coeffs.put(sumIndex, -1L); // sumEffects
            constraints.add(R1csConstraint.linear(c2Coeffs));

            // C3..: 每笔 txEffect 的占位约束（txEffect_i * 1 = txEffect_i，恒成立）
            // 真实实现应替换为交易执行约束（签名验证、nonce、gas 等）
            for (int i = 0; i < maxBatchSize; i++) {
                int idx = 4 + i;
                // (txEffect_i) * (1) = (txEffect_i)
                constraints.add(new R1csConstraint(
                        Collections.singletonMap(idx, 1L),
                        Collections.singletonMap(0, 1L),
                        Collections.singletonMap(idx, 1L)));
            }

            int numPrivate = maxBatchSize + 1; // txEffect_1..n + sumEffects
            cachedR1cs = new R1csConstraintSystem(NUM_PUBLIC, numPrivate, constraints);
            return cachedR1cs;
        }
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    /**
     * 从公共输入和交易效果列表构造完整 witness 向量。
     *
     * <p>供 prover 使用：将公共输入（状态根、批次哈希）和私密 witness（每笔交易效果）
     * 拼装为 R1CS witness 向量，并自动计算 sumEffects。</p>
     *
     * @param preStateRoot   前状态根（数值编码）
     * @param postStateRoot  后状态根（数值编码）
     * @param batchDataHash  批次数据哈希（数值编码）
     * @param txEffects      每笔交易的状态变更效果（长度需 ≤ maxBatchSize）
     * @return 完整 witness 向量
     */
    public long[] buildWitness(long preStateRoot, long postStateRoot, long batchDataHash,
                               long[] txEffects) {
        if (txEffects == null || txEffects.length > maxBatchSize) {
            throw new IllegalArgumentException(
                    "txEffects length must be <= " + maxBatchSize
                            + ", got " + (txEffects == null ? 0 : txEffects.length));
        }
        long[] publicInputs = {preStateRoot, postStateRoot, batchDataHash};
        long[] privateWitness = new long[maxBatchSize + 1];
        long sum = 0;
        for (int i = 0; i < txEffects.length; i++) {
            privateWitness[i] = txEffects[i];
            sum += txEffects[i];
        }
        privateWitness[maxBatchSize] = sum; // sumEffects
        return buildR1cs().buildWitness(publicInputs, privateWitness);
    }

    /**
     * 将字符串哈希为 long 值（用于状态根/批次哈希的数值编码）。
     *
     * @param hex 字符串（如十六进制状态根）
     * @return long 编码
     */
    public static long hashToLong(String hex) {
        if (hex == null || hex.isEmpty()) {
            return 0L;
        }
        long h = 0;
        for (byte b : hex.getBytes(StandardCharsets.UTF_8)) {
            h = h * 31 + b;
        }
        return h;
    }
}
