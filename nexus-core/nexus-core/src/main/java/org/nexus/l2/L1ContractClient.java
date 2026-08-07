package org.nexus.l2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1 合约客户端——内存模拟实现（fallback）。
 *
 * <p>封装 L1 桥合约交互：状态根提交、验证、批次 finalize、挑战。
 * 默认使用内存模拟（fallback），便于测试与离线开发。</p>
 *
 * <p>当 {@code nexus.l2.l1-bridge.enabled=true} 时，Spring 会注入
 * {@link Web3jL1ContractClient} 替代本类，实现真实 L1 合约调用。
 * 本类通过 {@code @ConditionalOnProperty} 在 enabled=false 或未设置时生效
 * （matchIfMissing=true），作为 fallback 实现。</p>
 *
 * <p>审计报告 §3.4 / 任务 #83：从原内存模拟拆分为独立 fallback 实现，
 * 真实 Web3j 调用迁移至 {@link Web3jL1ContractClient}。</p>
 *
 * @since 1.2
 */
@Component
@ConditionalOnProperty(prefix = "nexus.l2.l1-bridge", name = "enabled", havingValue = "false", matchIfMissing = true)
public class L1ContractClient {

    private static final Logger logger = LoggerFactory.getLogger(L1ContractClient.class);

    /** L1 上已提交的状态根：batchId -> root */
    protected final Map<Long, String> l1StateRoots = new ConcurrentHashMap<>();

    /** L1 上已 finalize 的批次 */
    protected final Map<Long, Boolean> l1FinalizedBatches = new ConcurrentHashMap<>();

    /** L1 上已挑战的批次 */
    protected final Map<Long, Boolean> l1ChallengedBatches = new ConcurrentHashMap<>();

    /** L1 上已 finalize 提款的批次 */
    protected final Map<Long, Boolean> l1FinalizedWithdraws = new ConcurrentHashMap<>();

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
     * 在 L1 上标记批次为 VERIFIED（挑战期结束、状态根确认后调用）。
     *
     * <p>对应 L1 桥合约 {@code markBatchVerified(uint256 batchId)} 函数。</p>
     *
     * @param batchId 批次 ID
     * @return 标记成功返回 true
     */
    public boolean markBatchVerifiedOnL1(long batchId) {
        if (!l1StateRoots.containsKey(batchId)) {
            logger.warn("Cannot mark batch {} VERIFIED on L1: state root not submitted", batchId);
            return false;
        }
        l1FinalizedBatches.put(batchId, true);
        logger.info("Batch {} marked VERIFIED on L1", batchId);
        return true;
    }

    /**
     * 在 L1 上 finalize 批次（旧接口，委托至 {@link #markBatchVerifiedOnL1}）。
     *
     * @param batchId 批次 ID
     * @return finalize 成功返回 true
     * @deprecated 使用 {@link #markBatchVerifiedOnL1} 语义更明确
     */
    @Deprecated
    public boolean finalizeBatchOnL1(long batchId) {
        return markBatchVerifiedOnL1(batchId);
    }

    /**
     * 在 L1 上 finalize 批次所有提款。
     *
     * <p>对应 L1 桥合约 {@code finalizeWithdraws(uint256 batchId)} 函数。</p>
     *
     * @param batchId 批次 ID
     * @return finalize 成功返回 true
     */
    public boolean finalizeWithdrawsOnL1(long batchId) {
        if (!l1FinalizedBatches.getOrDefault(batchId, false)) {
            logger.warn("Cannot finalize withdraws for batch {} on L1: batch not VERIFIED", batchId);
            return false;
        }
        l1FinalizedWithdraws.put(batchId, true);
        logger.info("Withdraws finalized on L1 for batch {}", batchId);
        return true;
    }

    /**
     * 在 L1 上挑战批次（提交欺诈证明）。
     *
     * <p>对应 L1 桥合约 {@code challengeBatch(uint256 batchId, bytes proofData)} 函数。</p>
     *
     * @param batchId   批次 ID
     * @param proofData 欺诈证明数据（RLP 编码）
     * @return 挑战成功返回 true
     */
    public boolean challengeBatchOnL1(long batchId, byte[] proofData) {
        if (proofData == null || proofData.length == 0) {
            logger.warn("Cannot challenge batch {} on L1: empty proof data", batchId);
            return false;
        }
        l1ChallengedBatches.put(batchId, true);
        logger.info("Batch {} challenged on L1 (proofSize={})", batchId, proofData.length);
        return true;
    }

    /**
     * 检查批次是否已在 L1 finalize（VERIFIED）。
     */
    public boolean isFinalizedOnL1(long batchId) {
        return l1FinalizedBatches.getOrDefault(batchId, false);
    }

    /**
     * 检查批次是否已在 L1 被挑战。
     */
    public boolean isChallengedOnL1(long batchId) {
        return l1ChallengedBatches.getOrDefault(batchId, false);
    }

    /**
     * 检查批次提款是否已在 L1 finalize。
     */
    public boolean isWithdrawsFinalizedOnL1(long batchId) {
        return l1FinalizedWithdraws.getOrDefault(batchId, false);
    }
}
