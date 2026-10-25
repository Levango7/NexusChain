package org.nexus.core.pending;

import org.nexus.core.Block;

import java.util.Collections;
import java.util.List;

/**
 * 待补写区块的持久化 outbox（PLAN-019）。
 *
 * <p>语义：区块**已经收到并通过校验**，但还没能写进链的存储（{@code header} /
 * {@code transaction} …）。这份"写入意图"必须记住 —— 否则进程重启后，
 * 这批区块只能靠 P2P 同步重新拉取（能恢复，但要走一遍网络，且对端也得还留着）。</p>
 *
 * <p>与最终性 outbox（{@code finality_vote_outbox}）的差别很重要，别混淆：</p>
 * <ul>
 *   <li>最终性票是**本节点签发的事实**，丢了就永远补不回来（别人没法替你签），所以必须持久化；</li>
 *   <li>区块是**别人也能提供的数据**，重启后 P2P 同步能重新拉回来 —— 持久化 outbox
 *       的收益是"不必等对端重发、少一次同步风暴"，不是"数据不可恢复"。</li>
 * </ul>
 *
 * <p>实现：{@link JdbcPendingBlockOutbox}（PostgreSQL / H2）。
 * 未装配时（无 JdbcTemplate / dev profile）退化为"纯内存重试队列"，
 * 与 PLAN-017 第一切片的行为一致。</p>
 */
public interface PendingBlockOutbox {

    /**
     * 记住一个待补写区块（幂等：同哈希重复入队只更新尝试次数与错误）。
     */
    default void enqueue(Block block) {
        // 默认不支持持久化 outbox
    }

    /**
     * 区块已写入链存储后移除（幂等）。
     */
    default void remove(Block block) {
        // 默认不支持
    }

    /**
     * 读取待补写区块（按高度升序，最多 limit 个）。
     */
    default List<Block> loadPending(int limit) {
        return Collections.emptyList();
    }

    /**
     * 记录一次补写失败（累计尝试次数与最近错误）。
     */
    default void markFailure(Block block, String error) {
        // 默认不支持
    }

    /**
     * 当前待补写区块数（观测用；读不到时返回 -1 表示未知）。
     */
    default int pendingCount() {
        return -1;
    }

    /**
     * 最老的待补写行创建时刻（观测用，PLAN-039 / roadmap #29 ②）。
     *
     * <p>与 {@link #pendingCount()} 组合回答"有没有**滞留**"：行数不清零 +
     * 最老行年龄很大 ⇒ 有永远写不进去的区块（如校验恒失败/数据损坏），
     * 运维据此决策（本类不自动清理——outbox 行是恢复数据）。</p>
     *
     * @return epoch millis；无行/读不到时返回 -1
     */
    default long oldestCreatedAt() {
        return -1L;
    }

    /**
     * 单行最大补写尝试次数（观测用；无行/读不到时返回 -1）。
     */
    default int maxAttempts() {
        return -1;
    }
}
