package org.nexus.l2;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Optimistic Rollup 骨架实现。
 *
 * <p>基于欺诈证明的乐观 Rollup，假设提交者诚实，
 * 在挑战窗口内可被挑战回滚。当前为骨架实现。</p>
 *
 * @since 1.2
 */
@Component
public class OptimisticRollup implements RollupManager {

    @Override
    public long submitBatch(List<L2Transaction> transactions) {
        // TODO: 聚合交易、计算状态根、提交批次到 L1 合约
        throw new UnsupportedOperationException("OptimisticRollup.submitBatch: not yet implemented");
    }

    @Override
    public boolean verifyBatch(long batchId) {
        // TODO: 等待挑战窗口结束，标记批次为 VERIFIED
        return false;
    }

    @Override
    public boolean challengeBatch(long batchId, Object proof) {
        // TODO: 校验欺诈证明，回滚批次状态
        return false;
    }
}