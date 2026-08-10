package org.nexus.l2;

import java.util.List;

/**
 * Rollup 批次状态枚举。
 *
 * @since 1.2
 */
public enum RollupBatchStatus {
    /** 已提交到 L1，等待挑战窗口 */
    SUBMITTED,
    /** 挑战窗口结束，最终确认 */
    VERIFIED,
    /** 被成功挑战并回滚 */
    CHALLENGED
}