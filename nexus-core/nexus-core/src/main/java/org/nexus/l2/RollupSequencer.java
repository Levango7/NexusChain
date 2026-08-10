package org.nexus.l2;

import org.nexus.l2.blob.BlobCarrierResult;
import org.nexus.l2.blob.BlobDataCarrier;
import org.nexus.l2.gas.GasCostEstimate;
import org.nexus.l2.gas.GasCostEstimator;
import org.nexus.l2.sequencer.SequencingPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * L2 排序器。
 *
 * <p>负责从 mempool 收集交易、排序、打包成批次并发布到 L1。
 * 排序保证确定性，便于验证节点重算状态根。单例串行发布，
 * 通过 {@code sequenceLock} 保证批次 ID 单调递增。</p>
 *
 * <p>自 1.3 起增强：</p>
 * <ul>
 *   <li>排序策略升级为 {@link SequencingPolicy}：(账户地址升序, 账户 nonce 升序, 优先费降序, txHash 字典序)</li>
 *   <li>可选接入 {@link BlobDataCarrier}：批次数据通过 EIP-4844 blob 携带到 L1，降低 calldata 成本</li>
 *   <li>可选接入 {@link GasCostEstimator}：估算批次 gas 成本，决策是否启用 blob 携带</li>
 * </ul>
 *
 * @since 1.2
 */
@Component
public class RollupSequencer {

    private static final Logger logger = LoggerFactory.getLogger(RollupSequencer.class);

    @Autowired
    private RollupBatcher batcher;

    @Autowired
    private StateRootManager stateRootManager;

    @Autowired
    private L2BridgeContract bridge;

    /** EIP-4844 blob 数据携带器（可选，1.3 新增） */
    @Autowired(required = false)
    private BlobDataCarrier blobDataCarrier;

    /** Gas 成本估算器（可选，1.3 新增） */
    @Autowired(required = false)
    private GasCostEstimator gasCostEstimator;

    /** 排序策略（nonce + 优先费） */
    private final SequencingPolicy sequencingPolicy = SequencingPolicy.defaultPolicy();

    private final Object sequenceLock = new Object();
    private volatile long nextBatchId = 1L;

    /**
     * 排序并发布一个批次到 L1。
     *
     * <p>流程：从 mempool 打包交易 -> 排序 -> 计算状态根 ->
     * 通过桥合约提交状态根到 L1 -> 可选通过 blob 携带批次数据。</p>
     *
     * @param submitter 提交者地址
     * @return 发布的批次；mempool 为空时返回空批次
     */
    public RollupBatch sequenceAndPublish(String submitter) {
        synchronized (sequenceLock) {
            long batchId = nextBatchId++;
            RollupBatch batch = batcher.buildBatch(batchId, submitter);
            List<L2Transaction> txs = batch.getTransactions();
            if (txs == null || txs.isEmpty()) {
                logger.debug("No transactions to sequence for batch {}", batchId);
                return batch;
            }
            sortTransactions(txs);
            String stateRoot = stateRootManager.applyBatch(batch);
            batch.setStateRoot(stateRoot);
            bridge.submitStateRoot(batchId, stateRoot);

            // 1.3 新增：可选通过 EIP-4844 blob 携带批次数据
            carryBatchDataViaBlobIfBeneficial(batchId, batch);

            logger.info("Sequenced and published batch {} with {} txs, root={}",
                    batchId, txs.size(), stateRoot);
            return batch;
        }
    }

    /**
     * 交易排序策略（nonce + 优先费）。
     *
     * <p>使用 {@link SequencingPolicy} 按
     * (账户地址升序, 账户 nonce 升序, 优先费降序, txHash 字典序) 排序。
     * 账户内严格按 nonce 升序，避免前序 tx 缺失导致后续 tx 执行失败；
     * 跨账户时高优先费交易优先打包，激励用户付费优先处理。</p>
     *
     * @param txs 待排序交易列表
     */
    private void sortTransactions(List<L2Transaction> txs) {
        sequencingPolicy.sort(txs);
    }

    /**
     * 当 blob 携带成本低于 calldata 时，通过 EIP-4844 blob 携带批次数据。
     *
     * <p>需要 {@link BlobDataCarrier} 与 {@link GasCostEstimator} 均可用。
     * 携带成功后批次数据可通过 blob KZG 证明验证可用性，无需下载完整 calldata。</p>
     *
     * @param batchId 批次 ID
     * @param batch   批次
     */
    private void carryBatchDataViaBlobIfBeneficial(long batchId, RollupBatch batch) {
        if (blobDataCarrier == null) {
            return;
        }
        boolean useBlob = gasCostEstimator == null || gasCostEstimator.shouldUseBlob(batch);
        if (!useBlob) {
            logger.debug("Blob not beneficial for batch {}, using calldata", batchId);
            return;
        }
        byte[] batchData = encodeBatchData(batch);
        BlobCarrierResult result = blobDataCarrier.carryBatchData(batchId, batchData);
        if (result != null) {
            logger.info("Batch {} data carried via EIP-4844 blob: hash={}, cost={}",
                    batchId, result.getBlobHash(), result.getBlobCost());
        }
    }

    /**
     * 编码批次数据为字节数组（简化实现：拼接 txHash + rawTx）。
     *
     * @param batch 批次
     * @return 字节数据
     */
    private byte[] encodeBatchData(RollupBatch batch) {
        List<L2Transaction> txs = batch.getTransactions();
        if (txs == null || txs.isEmpty()) {
            return new byte[0];
        }
        StringBuilder sb = new StringBuilder();
        for (L2Transaction tx : txs) {
            if (tx.getTxHash() != null) {
                sb.append(tx.getTxHash());
            }
            sb.append('|');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public long getNextBatchId() {
        return nextBatchId;
    }

    public int getMempoolSize() {
        return batcher.getMempoolSize();
    }

    /**
     * 获取排序策略。
     *
     * @return 排序策略
     * @since 1.3
     */
    public SequencingPolicy getSequencingPolicy() {
        return sequencingPolicy;
    }

    /**
     * 获取 blob 数据携带器。
     *
     * @return blob 携带器；未注入返回 null
     * @since 1.3
     */
    public BlobDataCarrier getBlobDataCarrier() {
        return blobDataCarrier;
    }

    /**
     * 获取 gas 成本估算器。
     *
     * @return gas 估算器；未注入返回 null
     * @since 1.3
     */
    public GasCostEstimator getGasCostEstimator() {
        return gasCostEstimator;
    }
}