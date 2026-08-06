package org.nexus.l2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1 合约客户端。
 *
 * <p>封装 L1 桥合约交互：状态根提交、验证、批次 finalize。
 * 默认使用内存模拟（fallback），便于测试；可扩展为 Web3j 真实 L1 调用。</p>
 *
 * @since 1.2
 */
@Component
public class L1ContractClient {

    private static final Logger logger = LoggerFactory.getLogger(L1ContractClient.class);

    /** L1 上已提交的状态根：batchId -> root */
    private final Map<Long, String> l1StateRoots = new ConcurrentHashMap<>();

    /** L1 上已 finalize 的批次 */
    private final Map<Long, Boolean> l1FinalizedBatches = new ConcurrentHashMap<>();

    /** 是否启用真实 L1 调用（false=内存 fallback） */
    private volatile boolean l1Enabled = false;

    public L1ContractClient() {
    }

    public L1ContractClient(boolean l1Enabled) {
        this.l1Enabled = l1Enabled;
    }

    /**
     * 提交状态根到 L1 合约。
     *
     * @param batchId 批次 ID
     * @param root    状态根
     * @return 提交成功返回 true
     */
    public boolean submitStateRootToL1(long batchId, String root) {
        if (root == null) {
            return false;
        }
        if (l1Enabled) {
            // TODO: 接入 Web3j 真实 L1 合约调用
            logger.info("Submitting state root to L1 contract for batch {} (real mode)", batchId);
        }
        l1StateRoots.put(batchId, root);
        logger.info("State root submitted to L1 for batch {} root={}", batchId, root);
        return true;
    }

    /**
     * 验证 L1 上某批次的状态根。
     *
     * @param batchId 批次 ID
     * @return L1 上存在该状态根返回 true
     */
    public boolean verifyStateRootOnL1(long batchId) {
        return l1StateRoots.containsKey(batchId);
    }

    /**
     * 获取 L1 上某批次的状态根。
     */
    public String getStateRootOnL1(long batchId) {
        return l1StateRoots.get(batchId);
    }

    /**
     * 在 L1 上 finalize 批次（挑战期结束、状态根确认后调用）。
     *
     * @param batchId 批次 ID
     * @return finalize 成功返回 true
     */
    public boolean finalizeBatchOnL1(long batchId) {
        if (!l1StateRoots.containsKey(batchId)) {
            logger.warn("Cannot finalize batch {} on L1: state root not submitted", batchId);
            return false;
        }
        if (l1Enabled) {
            // TODO: 接入 Web3j 真实 L1 合约调用
            logger.info("Finalizing batch {} on L1 contract (real mode)", batchId);
        }
        l1FinalizedBatches.put(batchId, true);
        logger.info("Batch {} finalized on L1", batchId);
        return true;
    }

    /**
     * 检查批次是否已在 L1 finalize。
     */
    public boolean isFinalizedOnL1(long batchId) {
        return l1FinalizedBatches.getOrDefault(batchId, false);
    }

    public void setL1Enabled(boolean l1Enabled) {
        this.l1Enabled = l1Enabled;
    }

    public boolean isL1Enabled() {
        return l1Enabled;
    }
}