package org.nexus.l2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * L2 排序器。
 *
 * <p>负责从 mempool 收集交易、排序、打包成批次并发布到 L1。
 * 排序保证确定性，便于验证节点重算状态根。单例串行发布，
 * 通过 {@code sequenceLock} 保证批次 ID 单调递增。</p>
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

    private final Object sequenceLock = new Object();
    private volatile long nextBatchId = 1L;

    /**
     * 排序并发布一个批次到 L1。
     *
     * <p>流程：从 mempool 打包交易 -> 排序 -> 计算状态根 ->
     * 通过桥合约提交状态根到 L1。</p>
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
            logger.info("Sequenced and published batch {} with {} txs, root={}",
                    batchId, txs.size(), stateRoot);
            return batch;
        }
    }

    /**
     * 交易排序策略。
     *
     * <p>骨架实现：按交易哈希字典序排序，保证确定性。
     * 实际策略可按 gas price / nonce 综合排序以优化打包收益。</p>
     *
     * @param txs 待排序交易列表
     */
    private void sortTransactions(List<L2Transaction> txs) {
        txs.sort(Comparator.comparing(
                L2Transaction::getTxHash,
                Comparator.nullsLast(Comparator.naturalOrder())));
    }

    public long getNextBatchId() {
        return nextBatchId;
    }

    public int getMempoolSize() {
        return batcher.getMempoolSize();
    }
}