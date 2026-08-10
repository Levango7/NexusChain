package org.nexus.l2;

import org.nexus.l2.gas.GasCostEstimate;
import org.nexus.l2.gas.GasCostEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * L2 批量交易打包器。
 *
 * <p>从 L2 mempool 收集交易，按批次大小上限打包成
 * {@link RollupBatch}，供排序器发布到 L1。</p>
 *
 * <p>自 1.3 起可选接入 {@link GasCostEstimator}，提供批次 gas 成本估算
 * 辅助决策（是否启用 blob 携带、批次大小优化）。</p>
 *
 * @since 1.2
 */
@Component
public class RollupBatcher {

    private static final Logger logger = LoggerFactory.getLogger(RollupBatcher.class);

    /** 默认单批次最大交易数 */
    private static final int DEFAULT_MAX_BATCH_SIZE = 1000;

    private final int maxBatchSize;
    private final ConcurrentLinkedQueue<L2Transaction> mempool = new ConcurrentLinkedQueue<>();

    /** Gas 成本估算器（可选，1.3 新增，供批次打包决策） */
    @Autowired(required = false)
    private GasCostEstimator gasCostEstimator;

    public RollupBatcher() {
        this(DEFAULT_MAX_BATCH_SIZE);
    }

    public RollupBatcher(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    /**
     * 接收一笔 L2 交易加入 mempool。
     *
     * @param tx L2 交易
     */
    public void submitTransaction(L2Transaction tx) {
        if (tx == null) {
            return;
        }
        tx.setStatus(L2TransactionStatus.PENDING);
        mempool.offer(tx);
        logger.debug("L2 tx {} added to mempool, size={}", tx.getTxHash(), mempool.size());
    }

    /**
     * 从 mempool 打包一个批次。
     *
     * <p>按 FIFO 顺序取出交易，至多 {@code maxBatchSize} 笔，
     * 标记为 INCLUDED 并组装成 {@link RollupBatch}。</p>
     *
     * @param batchId   批次 ID
     * @param submitter 提交者地址
     * @return 打包后的批次（可能为空批次）
     */
    public RollupBatch buildBatch(long batchId, String submitter) {
        List<L2Transaction> batch = new ArrayList<>();
        while (batch.size() < maxBatchSize) {
            L2Transaction tx = mempool.poll();
            if (tx == null) {
                break;
            }
            tx.setBatchId(batchId);
            tx.setStatus(L2TransactionStatus.INCLUDED);
            batch.add(tx);
        }
        RollupBatch rollupBatch = new RollupBatch();
        rollupBatch.setBatchId(batchId);
        rollupBatch.setTransactions(batch);
        rollupBatch.setSubmitter(submitter);
        rollupBatch.setStatus(RollupBatchStatus.SUBMITTED);
        logger.info("Built batch {} with {} txs, remaining mempool={}", batchId, batch.size(), mempool.size());
        return rollupBatch;
    }

    /**
     * 估算批次 gas 成本（1.3 新增，供打包决策）。
     *
     * @param batch   批次
     * @param useBlob 是否使用 blob 携带
     * @return gas 成本估算；估算器未注入返回 null
     */
    public GasCostEstimate estimateBatchGas(RollupBatch batch, boolean useBlob) {
        if (gasCostEstimator == null) {
            return null;
        }
        return gasCostEstimator.estimateBatchGas(batch, useBlob);
    }

    /**
     * 决策是否启用 blob 携带（1.3 新增）。
     *
     * @param batch 批次
     * @return 启用 blob 返回 true；估算器未注入返回 false
     */
    public boolean shouldUseBlob(RollupBatch batch) {
        if (gasCostEstimator == null) {
            return false;
        }
        return gasCostEstimator.shouldUseBlob(batch);
    }

    /**
     * 获取当前 mempool 中待打包交易数量。
     *
     * @return mempool 大小
     */
    public int getMempoolSize() {
        return mempool.size();
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
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