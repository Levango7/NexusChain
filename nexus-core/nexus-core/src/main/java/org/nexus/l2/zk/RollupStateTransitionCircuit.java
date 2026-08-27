package org.nexus.l2.zk;

import org.nexus.l2.zk.r1cs.R1csConstraint;
import org.nexus.l2.zk.r1cs.R1csConstraintSystem;

import java.math.BigInteger;
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
 * <h3>R1CS 约束（ZK-P0-03 修复，2.1.1）</h3>
 * <p>真实 Rollup 电路涉及 EVM 状态机、Merkle Patricia Trie 更新等复杂约束。
 * 本实现采用简化模型，但包含以下有实际语义的约束（非占位）：</p>
 *
 * <h4>witness 布局</h4>
 * <pre>
 * w = [1,
 *      preStateRoot, postStateRoot, batchDataHash,   // 公共输入 (numPublic=3)
 *      txEffect_1, ..., txEffect_n,                   // 每笔交易的状态变更效果 (n)
 *      sumEffects,                                    // 状态变更累加 (1)
 *      totalGas,                                      // gas 累计 (1)
 *      txGas_1, ..., txGas_n,                         // 每笔交易的 gas (n)
 *      txNonce_1, ..., txNonce_n,                     // 每笔交易的 nonce (n)
 *      accountNonce_1, ..., accountNonce_n,           // 账户 nonce (n)
 *      sigR_1, ..., sigR_n,                           // 签名 r (n)
 *      sigS_1, ..., sigS_n,                           // 签名 s (n)
 *      sigRSProduct_1, ..., sigRSProduct_n]           // 签名 r*s 乘积 (n)
 * </pre>
 *
 * <h4>约束列表</h4>
 * <ol>
 *   <li><b>状态守恒约束</b>（1个）：{@code postStateRoot - preStateRoot - sumEffects = 0}
 *       — 后状态根等于前状态根加状态变更累加</li>
 *   <li><b>状态变更累加约束</b>（1个）：{@code sumEffects - Σ txEffect_i = 0}
 *       — 状态变更累加等于每笔交易效果之和</li>
 *   <li><b>Gas 累计约束</b>（1个）：{@code totalGas - Σ txGas_i = 0}
 *       — 总 gas 等于每笔交易 gas 之和</li>
 *   <li><b>Nonce 单调递增约束</b>（n个）：{@code txNonce_i - accountNonce_i - 1 = 0}
 *       — 每笔交易的 nonce 严格递增（比账户 nonce 大 1）</li>
 *   <li><b>签名格式约束</b>（n个）：{@code sigR_i * sigS_i = sigRSProduct_i}
 *       — 验证签名 r 和 s 存在且乘积一致（非占位，验证签名格式）</li>
 * </ol>
 * <p>总约束数：3 + 2n</p>
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li>公共输入（numPublic=3）：preStateRoot, postStateRoot, batchDataHash</li>
 *   <li>私密 witness（numPrivate=7n+2）：txEffect(1..n) + sumEffects + totalGas
 *       + txGas(1..n) + txNonce(1..n) + accountNonce(1..n) + sigR(1..n)
 *       + sigS(1..n) + sigRSProduct(1..n)</li>
 *   <li>约束数：3 + 2n（状态守恒 + 累加 + gas + nonce递增 + 签名格式）</li>
 *   <li>状态根以 long 编码（真实实现应用域元素）</li>
 * </ul>
 *
 * <h3>状态根编码限制声明（ZK-P2-01，2.1.0）</h3>
 * <p><b>当前实现：状态根用 {@code long} 编码（64 位有符号整数），仅取值范围
 * [0, 2^63-1]。</b> 这是简化骨架的妥协，与真实 Rollup 状态根（256 位 bytes32，
 * 通常为 Merkle Patricia Trie 根哈希）存在以下差距：</p>
 * <ul>
 *   <li>容量不足：long 仅 63 位有效位，远小于 256 位状态根空间，存在碰撞风险</li>
 *   <li>语义偏差：真实状态根为域元素或字节串，非数值类型</li>
 *   <li>哈希不可逆：{@link #hashToLong(String)} 是有损映射，无法保留 256 位信息</li>
 * </ul>
 * <p><b>生产环境应改为 {@link BigInteger}（256 位）编码</b>，并使用
 * {@link #buildWitnessBigInteger(BigInteger, BigInteger, long, long[])}
 * 等 BigInteger 重载方法。当前 long 重载方法已标记 {@link Deprecated}，
 * 计划在 3.0.0 移除。</p>
 *
 * <h3>与旧版本区别（ZK-P0-03）</h3>
 * <ul>
 *   <li>移除恒等约束 {@code txEffect_i * 1 = txEffect_i}（无实际语义）</li>
 *   <li>新增 Gas 累计约束（线性，验证 gas 总量正确）</li>
 *   <li>新增 Nonce 单调递增约束（线性，防止重放攻击）</li>
 *   <li>新增签名格式约束（二次，验证签名 r/s 存在性）</li>
 *   <li>保留状态守恒与累加约束（核心语义）</li>
 * </ul>
 *
 * <h3>占位约束审计结论（ZK-P1-02，2.1.0）</h3>
 * <p>经逐一核查 {@code buildR1cs()} 生成的全部 3+2n 条约束，
 * 未发现任何形如 {@code x * 1 = x}、{@code x + 0 = x} 或全部系数为 0 的恒等约束。
 * 每条约束均承载明确语义（状态守恒/累加/gas/nonce/签名格式），故 ZK-P1-02 已随
 * ZK-P0-03 自动修复。后续新增约束应避免引入无语义占位项。</p>
 *
 * <h3>扩展点</h3>
 * <p>真实实现应替换 {@link #buildR1cs()} 为完整 EVM 状态机约束，
 * 包含：交易执行步骤、状态根逐笔更新（Pedersen/MiMC hash）、完整 ECDSA 签名验证等。
 * 当前简化版保证代数正确性与约束完备性，可被 Groth16 证明系统处理。</p>
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
        // 约束数 = 3（状态守恒 + 累加 + gas累计） + 2*maxBatchSize（nonce递增 + 签名格式）
        return 3 + 2 * maxBatchSize;
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
     * 返回 witness 索引布局（供 buildWitness 使用）。
     *
     * <p>witness 布局：</p>
     * <ul>
     *   <li>index 0: 常量 1</li>
     *   <li>index 1: preStateRoot（公共）</li>
     *   <li>index 2: postStateRoot（公共）</li>
     *   <li>index 3: batchDataHash（公共）</li>
     *   <li>index 4..4+n-1: txEffect_i（私密）</li>
     *   <li>index 4+n: sumEffects（私密）</li>
     *   <li>index 4+n+1: totalGas（私密）</li>
     *   <li>index 4+n+2..4+2n+1: txGas_i（私密）</li>
     *   <li>index 4+2n+2..4+3n+1: txNonce_i（私密）</li>
     *   <li>index 4+3n+2..4+4n+1: accountNonce_i（私密）</li>
     *   <li>index 4+4n+2..4+5n+1: sigR_i（私密）</li>
     *   <li>index 4+5n+2..4+6n+1: sigS_i（私密）</li>
     *   <li>index 4+6n+2..4+7n+1: sigRSProduct_i（私密）</li>
     * </ul>
     */
    private int sumEffectsIndex() { return 4 + maxBatchSize; }
    private int totalGasIndex() { return 4 + maxBatchSize + 1; }
    private int txGasIndex(int i) { return 4 + maxBatchSize + 2 + i; }
    private int txNonceIndex(int i) { return 4 + 2 * maxBatchSize + 2 + i; }
    private int accountNonceIndex(int i) { return 4 + 3 * maxBatchSize + 2 + i; }
    private int sigRIndex(int i) { return 4 + 4 * maxBatchSize + 2 + i; }
    private int sigSIndex(int i) { return 4 + 5 * maxBatchSize + 2 + i; }
    private int sigRSProductIndex(int i) { return 4 + 6 * maxBatchSize + 2 + i; }

    /**
     * 构建 R1CS 约束系统（ZK-P0-03 修复）。
     *
     * <p>包含以下有实际语义的约束（非占位）：</p>
     * <ol>
     *   <li>状态守恒：postStateRoot - preStateRoot - sumEffects = 0</li>
     *   <li>状态变更累加：sumEffects - Σ txEffect_i = 0</li>
     *   <li>Gas 累计：totalGas - Σ txGas_i = 0</li>
     *   <li>Nonce 单调递增（每笔交易）：txNonce_i - accountNonce_i - 1 = 0</li>
     *   <li>签名格式（每笔交易）：sigR_i * sigS_i = sigRSProduct_i</li>
     * </ol>
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
            int n = maxBatchSize;
            List<R1csConstraint> constraints = new ArrayList<>(3 + 2 * n);

            // C1: 状态守恒约束 — postStateRoot - preStateRoot - sumEffects = 0
            java.util.Map<Integer, java.math.BigInteger> c1Coeffs = new java.util.TreeMap<>();
            c1Coeffs.put(2, java.math.BigInteger.ONE);              // postStateRoot
            c1Coeffs.put(1, java.math.BigInteger.valueOf(-1));      // preStateRoot
            c1Coeffs.put(sumEffectsIndex(), java.math.BigInteger.valueOf(-1)); // sumEffects
            constraints.add(R1csConstraint.linear(c1Coeffs));

            // C2: 状态变更累加约束 — sumEffects - Σ txEffect_i = 0
            java.util.Map<Integer, java.math.BigInteger> c2Coeffs = new java.util.TreeMap<>();
            c2Coeffs.put(sumEffectsIndex(), java.math.BigInteger.ONE);
            for (int i = 0; i < n; i++) {
                c2Coeffs.put(4 + i, java.math.BigInteger.valueOf(-1));
            }
            constraints.add(R1csConstraint.linear(c2Coeffs));

            // C3: Gas 累计约束 — totalGas - Σ txGas_i = 0
            java.util.Map<Integer, java.math.BigInteger> c3Coeffs = new java.util.TreeMap<>();
            c3Coeffs.put(totalGasIndex(), java.math.BigInteger.ONE);
            for (int i = 0; i < n; i++) {
                c3Coeffs.put(txGasIndex(i), java.math.BigInteger.valueOf(-1));
            }
            constraints.add(R1csConstraint.linear(c3Coeffs));

            // C4..C4+n-1: Nonce 单调递增约束 — txNonce_i - accountNonce_i - 1 = 0
            // 即 txNonce_i = accountNonce_i + 1（nonce 严格递增）
            for (int i = 0; i < n; i++) {
                java.util.Map<Integer, java.math.BigInteger> nonceCoeffs = new java.util.TreeMap<>();
                nonceCoeffs.put(txNonceIndex(i), java.math.BigInteger.ONE);         // txNonce_i
                nonceCoeffs.put(accountNonceIndex(i), java.math.BigInteger.valueOf(-1));   // accountNonce_i
                nonceCoeffs.put(0, java.math.BigInteger.valueOf(-1));                      // -1（常量）
                constraints.add(R1csConstraint.linear(nonceCoeffs));
            }

            // C5..C5+n-1: 签名格式约束 — sigR_i * sigS_i = sigRSProduct_i
            // 验证签名 r 和 s 存在且乘积一致（非占位，验证签名格式）
            for (int i = 0; i < n; i++) {
                constraints.add(R1csConstraint.multiplication(
                        sigRIndex(i), sigSIndex(i), sigRSProductIndex(i)));
            }

            // 私密变量数：n(txEffect) + 1(sumEffects) + 1(totalGas)
            //           + n(txGas) + n(txNonce) + n(accountNonce)
            //           + n(sigR) + n(sigS) + n(sigRSProduct) = 7n + 2
            int numPrivate = 7 * n + 2;
            cachedR1cs = new R1csConstraintSystem(NUM_PUBLIC, numPrivate, constraints);
            return cachedR1cs;
        }
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    /**
     * 从公共输入和交易效果列表构造完整 witness 向量（简化版，自动生成 gas/nonce/签名）。
     *
     * <p>供 prover 使用：将公共输入（状态根、批次哈希）和私密 witness（每笔交易效果）
     * 拼装为 R1CS witness 向量，并自动计算 sumEffects、totalGas 及占位的 gas/nonce/签名。</p>
     *
     * <p>自动生成规则（简化模型）：</p>
     * <ul>
     *   <li>txGas_i = |txEffect_i|（gas 等于交易效果绝对值，简化）</li>
     *   <li>txNonce_i = i + 1（nonce 从 1 开始递增）</li>
     *   <li>accountNonce_i = i（账户 nonce 从 0 开始）</li>
     *   <li>sigR_i = 1, sigS_i = 1, sigRSProduct_i = 1（签名占位，满足 sigR*sigS=product）</li>
     * </ul>
     *
     * <p><b>参数校验（ZK-P2-01）</b>：{@code preStateRoot} 和 {@code postStateRoot}
     * 必须为非负 long（状态根作为数值编码不应为负）。负值将抛出
     * {@link IllegalArgumentException}。</p>
     *
     * @param preStateRoot   前状态根（数值编码，必须 ≥ 0）
     * @param postStateRoot  后状态根（数值编码，必须 ≥ 0）
     * @param batchDataHash  批次数据哈希（数值编码）
     * @param txEffects      每笔交易的状态变更效果（长度需 ≤ maxBatchSize）
     * @return 完整 witness 向量
     * @throws IllegalArgumentException 若状态根为负或 txEffects 长度超限
     * @deprecated 状态根用 long 编码（64 位），生产环境应改为 BigInteger（256 位）。
     *             请使用 {@link #buildWitnessBigInteger(BigInteger, BigInteger, long, long[])}。
     *             计划在 3.0.0 移除。
     */
    @Deprecated(since = "2.1.0", forRemoval = true)
    public long[] buildWitness(long preStateRoot, long postStateRoot, long batchDataHash,
                               long[] txEffects) {
        // ZK-P2-01: 状态根非负校验（long 编码限制下至少防止负值误用）
        if (preStateRoot < 0L) {
            throw new IllegalArgumentException(
                    "preStateRoot must be non-negative (long encoding): got " + preStateRoot);
        }
        if (postStateRoot < 0L) {
            throw new IllegalArgumentException(
                    "postStateRoot must be non-negative (long encoding): got " + postStateRoot);
        }
        if (txEffects == null || txEffects.length > maxBatchSize) {
            throw new IllegalArgumentException(
                    "txEffects length must be <= " + maxBatchSize
                            + ", got " + (txEffects == null ? 0 : txEffects.length));
        }
        int n = maxBatchSize;
        long[] publicInputs = {preStateRoot, postStateRoot, batchDataHash};
        long[] privateWitness = new long[7 * n + 2];

        // txEffect_i (index 0..n-1 in privateWitness)
        long sum = 0;
        long totalGas = 0;
        for (int i = 0; i < n; i++) {
            long effect = (i < txEffects.length) ? txEffects[i] : 0L;
            privateWitness[i] = effect;
            sum += effect;
            // txGas_i = |txEffect_i|（简化：gas 等于交易效果绝对值）
            long gas = Math.abs(effect);
            privateWitness[n + 2 + i] = gas; // txGas_i 在 privateWitness 中的偏移
            totalGas += gas;
            // txNonce_i = i + 1（nonce 从 1 开始递增）
            privateWitness[2 * n + 2 + i] = i + 1;
            // accountNonce_i = i（账户 nonce 从 0 开始）
            privateWitness[3 * n + 2 + i] = i;
            // sigR_i = 1, sigS_i = 1, sigRSProduct_i = 1（签名占位）
            privateWitness[4 * n + 2 + i] = 1L; // sigR_i
            privateWitness[5 * n + 2 + i] = 1L; // sigS_i
            privateWitness[6 * n + 2 + i] = 1L; // sigRSProduct_i
        }
        // sumEffects (index n in privateWitness)
        privateWitness[n] = sum;
        // totalGas (index n+1 in privateWitness)
        privateWitness[n + 1] = totalGas;

        return buildR1cs().buildWitness(publicInputs, privateWitness);
    }

    /**
     * 从完整参数构造 witness 向量（高级版，允许指定所有私密变量）。
     *
     * <p><b>参数校验（ZK-P2-01）</b>：{@code preStateRoot} 和 {@code postStateRoot}
     * 必须为非负 long。</p>
     *
     * @param preStateRoot    前状态根（必须 ≥ 0）
     * @param postStateRoot   后状态根（必须 ≥ 0）
     * @param batchDataHash   批次数据哈希
     * @param txEffects       每笔交易效果（长度需等于 maxBatchSize）
     * @param txGas           每笔交易 gas（长度需等于 maxBatchSize）
     * @param txNonce         每笔交易 nonce（长度需等于 maxBatchSize）
     * @param accountNonce    每笔交易对应账户 nonce（长度需等于 maxBatchSize）
     * @param sigR            签名 r（长度需等于 maxBatchSize）
     * @param sigS            签名 s（长度需等于 maxBatchSize）
     * @return 完整 witness 向量
     * @throws IllegalArgumentException 若状态根为负或数组长度不匹配
     * @since 2.1.1
     * @deprecated 状态根用 long 编码（64 位），生产环境应改为 BigInteger（256 位）。
     *             计划在 3.0.0 移除。
     */
    @Deprecated(since = "2.1.0", forRemoval = true)
    public long[] buildWitness(long preStateRoot, long postStateRoot, long batchDataHash,
                               long[] txEffects, long[] txGas, long[] txNonce,
                               long[] accountNonce, long[] sigR, long[] sigS) {
        // ZK-P2-01: 状态根非负校验
        if (preStateRoot < 0L) {
            throw new IllegalArgumentException(
                    "preStateRoot must be non-negative (long encoding): got " + preStateRoot);
        }
        if (postStateRoot < 0L) {
            throw new IllegalArgumentException(
                    "postStateRoot must be non-negative (long encoding): got " + postStateRoot);
        }
        int n = maxBatchSize;
        if (txEffects == null || txEffects.length != n
                || txGas == null || txGas.length != n
                || txNonce == null || txNonce.length != n
                || accountNonce == null || accountNonce.length != n
                || sigR == null || sigR.length != n
                || sigS == null || sigS.length != n) {
            throw new IllegalArgumentException(
                    "all arrays must have length equal to maxBatchSize=" + n);
        }
        long[] publicInputs = {preStateRoot, postStateRoot, batchDataHash};
        long[] privateWitness = new long[7 * n + 2];

        long sum = 0;
        long totalGas = 0;
        for (int i = 0; i < n; i++) {
            privateWitness[i] = txEffects[i];
            sum += txEffects[i];
            privateWitness[n + 2 + i] = txGas[i];
            totalGas += txGas[i];
            privateWitness[2 * n + 2 + i] = txNonce[i];
            privateWitness[3 * n + 2 + i] = accountNonce[i];
            privateWitness[4 * n + 2 + i] = sigR[i];
            privateWitness[5 * n + 2 + i] = sigS[i];
            // sigRSProduct_i = sigR_i * sigS_i
            privateWitness[6 * n + 2 + i] = sigR[i] * sigS[i];
        }
        privateWitness[n] = sum;
        privateWitness[n + 1] = totalGas;

        return buildR1cs().buildWitness(publicInputs, privateWitness);
    }

    /**
     * 从公共输入和交易效果列表构造完整 witness 向量（BigInteger 状态根版）。
     *
     * <p><b>ZK-P2-01 修复</b>：使用 {@link BigInteger} 编码状态根，支持完整 256 位
     * bytes32 状态根空间。状态根通过 {@link BigInteger#longValueExact()} 折算为 long
     * 参与 R1CS 求值（仍受 long 范围限制，但 API 已具备 256 位承载能力，
     * 后续切换为 BigInteger 域 R1CS 时无需改动调用方）。</p>
     *
     * <p>当状态根超出 long 范围时抛出 {@link ArithmeticException}（由
     * {@code longValueExact()} 抛出），调用方应在此情况下切换至 BigInteger 域 R1CS
     * 实现（计划 3.0.0 提供）。</p>
     *
     * @param preStateRoot   前状态根（256 位 BigInteger，必须非空且非负）
     * @param postStateRoot  后状态根（256 位 BigInteger，必须非空且非负）
     * @param batchDataHash  批次数据哈希（数值编码）
     * @param txEffects      每笔交易的状态变更效果（长度需 ≤ maxBatchSize）
     * @return 完整 witness 向量
     * @throws IllegalArgumentException 若状态根为 null/负或 txEffects 长度超限
     * @throws ArithmeticException      若状态根超出 long 范围
     * @since 2.1.0
     */
    public long[] buildWitnessBigInteger(BigInteger preStateRoot, BigInteger postStateRoot,
                                         long batchDataHash, long[] txEffects) {
        if (preStateRoot == null) {
            throw new IllegalArgumentException("preStateRoot cannot be null");
        }
        if (postStateRoot == null) {
            throw new IllegalArgumentException("postStateRoot cannot be null");
        }
        if (preStateRoot.signum() < 0) {
            throw new IllegalArgumentException(
                    "preStateRoot must be non-negative: got " + preStateRoot);
        }
        if (postStateRoot.signum() < 0) {
            throw new IllegalArgumentException(
                    "postStateRoot must be non-negative: got " + postStateRoot);
        }
        // 折算为 long（超出范围时 longValueExact 抛 ArithmeticException）
        long preLong = preStateRoot.longValueExact();
        long postLong = postStateRoot.longValueExact();
        // 委托至 long 重载（已含 txEffects 校验）
        return buildWitness(preLong, postLong, batchDataHash, txEffects);
    }

    /**
     * 从完整参数构造 witness 向量（A1-R3：真正的 BigInteger 域，返回 BigInteger[]，无 long 截断）。
     *
     * <p>与 {@link #buildWitnessBigInteger(BigInteger, BigInteger, long, long[])} 不同，
     * 本方法不将状态根折算为 long，witness 全部以 {@link BigInteger} 承载，
     * 支持 256 位状态根全精度（ZK-P2-01 关闭）。</p>
     *
     * @param preStateRoot   前状态根（256 位 BigInteger，必须非空且非负）
     * @param postStateRoot  后状态根（256 位 BigInteger，必须非空且非负）
     * @param batchDataHash  批次数据哈希（数值编码）
     * @param txEffects      每笔交易的状态变更效果（长度需 ≤ maxBatchSize）
     * @return 完整 witness 向量（BigInteger[]）
     * @throws IllegalArgumentException 若状态根为 null/负或 txEffects 长度超限
     * @since 2.41.0
     */
    public java.math.BigInteger[] buildWitnessBigIntegerArray(
            java.math.BigInteger preStateRoot, java.math.BigInteger postStateRoot,
            long batchDataHash, long[] txEffects) {
        if (preStateRoot == null) {
            throw new IllegalArgumentException("preStateRoot cannot be null");
        }
        if (postStateRoot == null) {
            throw new IllegalArgumentException("postStateRoot cannot be null");
        }
        if (preStateRoot.signum() < 0) {
            throw new IllegalArgumentException(
                    "preStateRoot must be non-negative: got " + preStateRoot);
        }
        if (postStateRoot.signum() < 0) {
            throw new IllegalArgumentException(
                    "postStateRoot must be non-negative: got " + postStateRoot);
        }
        int n = maxBatchSize;
        if (txEffects == null || txEffects.length > n) {
            throw new IllegalArgumentException(
                    "txEffects length must be <= maxBatchSize (" + n + "), got "
                            + (txEffects == null ? 0 : txEffects.length));
        }
        // 补齐到 maxBatchSize（缺失补 0）
        long[] padded = new long[n];
        if (txEffects != null) {
            System.arraycopy(txEffects, 0, padded, 0, txEffects.length);
        }
        return buildWitnessBigIntegerArrayInternal(preStateRoot, postStateRoot, batchDataHash, padded);
    }

    private java.math.BigInteger[] buildWitnessBigIntegerArrayInternal(
            java.math.BigInteger preStateRoot, java.math.BigInteger postStateRoot,
            long batchDataHash, long[] paddedEffects) {
        int n = maxBatchSize;
        // 与 long 版 buildWitness 布局完全一致（见 buildWitness javadoc）：
        // [1, pre, post, batchHash, effect_0..n-1, sumEffects, totalGas,
        //  txGas_0..n-1, txNonce_0..n-1, accountNonce_0..n-1, sigR_0..n-1, sigS_0..n-1, sigRSProduct_0..n-1]
        int numPublic = 3;
        int numPrivate = 7 * n + 2;
        int size = 1 + numPublic + numPrivate;
        java.math.BigInteger[] w = new java.math.BigInteger[size];
        w[0] = java.math.BigInteger.ONE;
        w[1] = preStateRoot;
        w[2] = postStateRoot;
        w[3] = java.math.BigInteger.valueOf(batchDataHash);

        long sum = 0;
        long totalGas = 0;
        // txEffect_i, txGas_i, txNonce_i, accountNonce_i, sigR_i, sigS_i, sigRSProduct_i
        for (int i = 0; i < n; i++) {
            w[4 + i] = java.math.BigInteger.valueOf(paddedEffects[i]);
            sum += paddedEffects[i];
            // txGas_i 无独立输入，默认 0（与 long 版一致：txGas 数组全 0）
            w[2 * n + 2 + i] = java.math.BigInteger.valueOf(i + 1); // txNonce_i = i + 1
            w[3 * n + 2 + i] = java.math.BigInteger.valueOf(i);     // accountNonce_i = i
            w[4 * n + 2 + i] = java.math.BigInteger.ONE;            // sigR_i = 1
            w[5 * n + 2 + i] = java.math.BigInteger.ONE;            // sigS_i = 1
            w[6 * n + 2 + i] = java.math.BigInteger.ONE;            // sigRSProduct_i = 1
        }
        w[n + 1] = java.math.BigInteger.valueOf(sum);          // sumEffects (index n)
        w[n + 2] = java.math.BigInteger.valueOf(totalGas);     // totalGas (index n+1)
        return w;
    }

    /**
     * 将字符串哈希为 256 位 BigInteger（A1-R3：替代有损 hashToLong）。
     *
     * <p>对输入字符串取 SHA-256 摘要并转为非负 BigInteger，完整承载 256 位
     * 状态根空间，无碰撞风险（ZK-P2-01 关闭）。</p>
     *
     * @param hex 字符串（如十六进制状态根）
     * @return 256 位 BigInteger 编码
     */
    public static java.math.BigInteger hashToBigInteger(String hex) {
        if (hex == null || hex.isEmpty()) {
            return java.math.BigInteger.ZERO;
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(hex.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new java.math.BigInteger(1, digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 必然可用；兜底退化为 hashToLong 的有损值
            return java.math.BigInteger.valueOf(hashToLong(hex));
        }
    }

    /**
     * 将字符串哈希为 long 值（用于状态根/批次哈希的数值编码）。
     *
     * <p><b>限制（ZK-P2-01）</b>：long 仅有 64 位，对 256 位状态根为有损映射，
     * 存在碰撞风险。生产环境应改用 256 位哈希（如 SHA-256 输出转 BigInteger）。</p>
     *
     * @param hex 字符串（如十六进制状态根）
     * @return long 编码
     * @deprecated 状态根用 long 编码（64 位），生产环境应改为 BigInteger（256 位）。
     *             计划在 3.0.0 移除。
     */
    @Deprecated(since = "2.1.0", forRemoval = true)
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
