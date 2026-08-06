package org.nexus.l2;

import org.nexus.l2.zk.RollupStateTransitionCircuit;
import org.nexus.l2.zk.ZkCircuit;
import org.nexus.l2.zk.ZkProof;
import org.nexus.l2.zk.ZkProofSystem;
import org.nexus.l2.zk.ZkPublicInput;
import org.nexus.l2.zk.ZkVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ZK Rollup 实现（接入 ZkProofSystem 骨架）。
 *
 * <p>基于零知识证明的 Rollup，每个批次附带有效性证明，验证即最终性。
 * 自 1.5 起接入 {@link ZkProofSystem} 抽象：</p>
 * <ul>
 *   <li>{@code submitBatch}：聚合交易 → 计算状态根 → 生成 ZK proof → 提交到 L1</li>
 *   <li>{@code verifyBatch}：在 L1 上验证 ZK proof → 验证通过即最终确认</li>
 *   <li>{@code challengeBatch}：ZK Rollup 数学保证下不可挑战；保留接口占位</li>
 * </ul>
 *
 * <h3>与 OptimisticRollup 的区别</h3>
 * <table>
 *   <caption>表：ZK Rollup 与 Optimistic Rollup 对照表</caption>
 *   <tr><th>维度</th><th>OptimisticRollup</th><th>ZkRollup</th></tr>
 *   <tr><td>最终性</td><td>挑战窗口结束（数天）</td><td>ZK proof 验证即最终（分钟级）</td></tr>
 *   <tr><td>挑战</td><td>欺诈证明可挑战</td><td>数学保证不可挑战</td></tr>
 *   <tr><td>证明开销</td><td>低（仅状态根）</td><td>高（生成 ZK proof）</td></tr>
 *   <tr><td>验证开销</td><td>低（等待窗口）</td><td>低（常数时间验证 proof）</td></tr>
 * </table>
 *
 * <h3>骨架说明</h3>
 * <p>当前 {@link ZkProofSystem} 为骨架实现（{@link org.nexus.l2.zk.ZkProver}/{@link ZkVerifier}），
 * prove 返回占位证明，verify 校验非空。真实接入 halo2/Plonk 时仅需替换 ZkProofSystem 实现，
 * 本类无需改动。注释中标注 {@code TODO: zk} 处为真实 ZK 接入点。</p>
 *
 * @since 1.2
 */
@Component
public class ZkRollup implements RollupManager {

    private static final Logger logger = LoggerFactory.getLogger(ZkRollup.class);

    @Autowired
    private StateRootManager stateRootManager;

    @Autowired
    private L2BridgeContract bridge;

    /** ZK 证明系统（默认注入骨架实现 ZkProver） */
    @Autowired
    private ZkProofSystem zkProofSystem;

    /** ZK 证明验证器 */
    @Autowired
    private ZkVerifier zkVerifier;

    /** Rollup 状态转换电路 */
    private final ZkCircuit circuit = new RollupStateTransitionCircuit();

    /** 批次 ID 自增 */
    private final AtomicLong nextBatchId = new AtomicLong(1L);

    /** batchId -> 已提交批次（含 ZK proof） */
    private final Map<Long, ZkBatchContext> batches = new ConcurrentHashMap<>();

    /** 电路是否已 setup */
    private volatile boolean circuitSetupDone = false;

    /**
     * 提交批次：聚合交易 → 计算状态根 → 生成 ZK proof → 提交到 L1。
     *
     * @param transactions L2 交易列表
     * @return 批次 ID
     */
    @Override
    public long submitBatch(List<L2Transaction> transactions) {
        ensureCircuitSetup();
        long batchId = nextBatchId.getAndIncrement();
        String submitter = "zk-sequencer";

        // 1. 构建批次并计算状态根
        RollupBatch batch = buildBatch(batchId, transactions, submitter);
        String preStateRoot = stateRootManager.getCurrentStateRoot();
        String postStateRoot = stateRootManager.applyBatch(batch);
        batch.setStateRoot(postStateRoot);

        // 2. 构造公共输入
        String batchDataHash = computeBatchDataHash(batch);
        ZkPublicInput publicInput = new ZkPublicInput(
                preStateRoot, postStateRoot, batchDataHash, 0L, Collections.emptyList());

        // 3. 生成 ZK proof
        // TODO: zk 真实接入时 witness 应包含每笔 tx 的执行 trace
        byte[] witness = encodeWitness(batch);
        ZkProof proof = zkProofSystem.prove(circuit, witness, publicInput);

        // 4. 提交状态根与 proof 到 L1
        bridge.submitStateRoot(batchId, postStateRoot);
        // TODO: zk 真实接入时 proof 也应提交到 L1 合约（submitProof(batchId, proof)）

        // 5. 记录批次上下文
        batches.put(batchId, new ZkBatchContext(batch, proof, publicInput, preStateRoot));
        logger.info("ZkRollup submitBatch {} with {} txs, root={}, proofSize={}",
                batchId, transactions == null ? 0 : transactions.size(),
                postStateRoot, proof.size());
        return batchId;
    }

    /**
     * 验证批次：在 L1 上验证 ZK proof，验证通过即最终确认。
     *
     * @param batchId 批次 ID
     * @return 验证通过返回 true；批次不存在或 proof 验证失败返回 false
     */
    @Override
    public boolean verifyBatch(long batchId) {
        ZkBatchContext ctx = batches.get(batchId);
        if (ctx == null) {
            logger.warn("ZkRollup verifyBatch: batch {} not found", batchId);
            return false;
        }
        if (ctx.batch.getStatus() == RollupBatchStatus.VERIFIED) {
            logger.debug("ZkRollup verifyBatch: batch {} already VERIFIED", batchId);
            return true;
        }
        if (ctx.batch.getStatus() == RollupBatchStatus.CHALLENGED) {
            logger.info("ZkRollup verifyBatch: batch {} was CHALLENGED", batchId);
            return false;
        }
        // 验证 ZK proof
        boolean valid = zkVerifier.verify(ctx.proof, ctx.publicInput);
        if (!valid) {
            logger.error("ZkRollup verifyBatch: ZK proof INVALID for batch {}", batchId);
            return false;
        }
        // TODO: zk 真实接入时还应从 L1 合约读取 proof 并验证（verifyOnL1(batchId)）
        ctx.batch.setStatus(RollupBatchStatus.VERIFIED);
        // 推进交易状态为 CONFIRMED
        if (ctx.batch.getTransactions() != null) {
            for (L2Transaction tx : ctx.batch.getTransactions()) {
                if (tx.getStatus() == L2TransactionStatus.INCLUDED
                        || tx.getStatus() == L2TransactionStatus.PENDING) {
                    tx.setStatus(L2TransactionStatus.CONFIRMED);
                }
            }
        }
        logger.info("ZkRollup verifyBatch {} -> VERIFIED (ZK proof valid)", batchId);
        return true;
    }

    /**
     * 挑战批次：ZK Rollup 在数学保证下不可挑战，保留接口占位。
     *
     * @param batchId 批次 ID
     * @param proof   挑战证明
     * @return 始终返回 false（ZK Rollup 不可挑战）
     */
    @Override
    public boolean challengeBatch(long batchId, Object proof) {
        logger.warn("ZkRollup challengeBatch: ZK rollup is not challengeable by mathematical guarantee; batch {}", batchId);
        return false;
    }

    /**
     * 查询批次上下文（含 ZK proof）。
     *
     * @param batchId 批次 ID
     * @return 批次上下文；不存在返回 null
     */
    public ZkBatchContext getBatchContext(long batchId) {
        return batches.get(batchId);
    }

    /**
     * 查询批次的 ZK proof。
     *
     * @param batchId 批次 ID
     * @return ZK proof；批次不存在返回 null
     */
    public ZkProof getBatchProof(long batchId) {
        ZkBatchContext ctx = batches.get(batchId);
        return ctx == null ? null : ctx.proof;
    }

    public long getNextBatchId() {
        return nextBatchId.get();
    }

    public ZkCircuit getCircuit() {
        return circuit;
    }

    /**
     * 显式设置 ZK 证明系统（用于测试或运行时切换 ZK 后端）。
     *
     * @param zkProofSystem ZK 证明系统
     */
    public void setZkProofSystem(ZkProofSystem zkProofSystem) {
        this.zkProofSystem = zkProofSystem;
        this.circuitSetupDone = false;
    }

    public void setZkVerifier(ZkVerifier zkVerifier) {
        this.zkVerifier = zkVerifier;
    }

    private void ensureCircuitSetup() {
        if (!circuitSetupDone) {
            zkProofSystem.setup(circuit);
            circuitSetupDone = true;
        }
    }

    private RollupBatch buildBatch(long batchId, List<L2Transaction> transactions, String submitter) {
        RollupBatch batch = new RollupBatch();
        batch.setBatchId(batchId);
        List<L2Transaction> txs = transactions == null ? new ArrayList<>() : new ArrayList<>(transactions);
        for (L2Transaction tx : txs) {
            tx.setBatchId(batchId);
            if (tx.getStatus() == null) {
                tx.setStatus(L2TransactionStatus.INCLUDED);
            }
        }
        batch.setTransactions(txs);
        batch.setSubmitter(submitter);
        batch.setStatus(RollupBatchStatus.SUBMITTED);
        return batch;
    }

    private byte[] encodeWitness(RollupBatch batch) {
        // 骨架：将批次 tx 哈希列表编码为 witness 字节
        // TODO: zk 真实接入时 witness 应包含每笔 tx 的完整执行 trace
        StringBuilder sb = new StringBuilder();
        sb.append("witness|batch=").append(batch.getBatchId());
        if (batch.getTransactions() != null) {
            for (L2Transaction tx : batch.getTransactions()) {
                sb.append("|tx=").append(tx.getTxHash());
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String computeBatchDataHash(RollupBatch batch) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        md.update("BATCH".getBytes(StandardCharsets.UTF_8));
        if (batch.getTransactions() != null) {
            for (L2Transaction tx : batch.getTransactions()) {
                if (tx.getTxHash() != null) {
                    md.update(tx.getTxHash().getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        return bytesToHex(md.digest());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * ZK 批次上下文：批次 + ZK proof + 公共输入 + 前置状态根。
     */
    public static final class ZkBatchContext {
        /** 批次实体 */
        public final RollupBatch batch;
        /** ZK 证明 */
        public final ZkProof proof;
        /** 公共输入 */
        public final ZkPublicInput publicInput;
        /** 批次前状态根 */
        public final String preStateRoot;

        ZkBatchContext(RollupBatch batch, ZkProof proof, ZkPublicInput publicInput, String preStateRoot) {
            this.batch = batch;
            this.proof = proof;
            this.publicInput = publicInput;
            this.preStateRoot = preStateRoot;
        }
    }
}
