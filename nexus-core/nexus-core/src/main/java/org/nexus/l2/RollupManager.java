package org.nexus.l2;

import java.util.List;

/**
 * Rollup 管理接口。
 *
 * <p>定义 L2 批次提交、验证与挑战能力，
 * 是 Optimistic / ZK Rollup 的统一抽象。</p>
 *
 * @since 1.2
 */
public interface RollupManager {

    /**
     * 将一批 L2 交易聚合提交到 L1。
     *
     * @param transactions L2 交易列表
     * @return 提交后的批次 ID
     */
    long submitBatch(List<L2Transaction> transactions);

    /**
     * 验证已提交批次，确认其最终性。
     *
     * @param batchId 批次 ID
     * @return 验证通过返回 true
     */
    boolean verifyBatch(long batchId);

    /**
     * 对已提交批次发起挑战。
     *
     * @param batchId 批次 ID
     * @param proof   挑战证明（欺诈证明 / 无效状态转换证明）
     * @return 挑战成功返回 true
     */
    boolean challengeBatch(long batchId, Object proof);
}