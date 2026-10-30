/*
 * Copyright (c) [2018]
 * This file is part of the java-nexuscore
 *
 * The java-nexuscore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The java-nexuscore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the java-nexuscore. If not, see <http://www.gnu.org/licenses/>.
 */

package org.nexus.core;

import org.nexus.core.account.AccountStoreUnavailableException;
import org.nexus.core.validate.CompositeBlockRule;
import org.nexus.core.validate.MerkleRule;
import org.nexus.core.validate.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.nexus.db.StateDB;
import org.nexus.merkletree.MerkleTreeManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PendingBlocksManager {

    @Autowired
    private NexusChainBlockChain bc;

    @Autowired
    private StateDB stateDB;

    @Autowired
    private CompositeBlockRule rule;

    @Autowired
    private MerkleRule merkleRule;

    /** PLAN-003：分叉重组管理器（父块缺失 → 尝试切换更长链）。 */
    @Autowired(required = false)
    private ReorgManager reorgManager;

    private Logger logger = LoggerFactory.getLogger(PendingBlocksManager.class);

    @Autowired
    private MerkleTreeManager merkleTreeManager;

    /**
     * 存储故障期间暂存的待采纳区块。
     *
     * <p>内存层：进程存活期间的快速兜底。PLAN-019 起，同一批区块还会写入
     * {@code pending_block_outbox} 表（持久化层），重启后由
     * {@link #restoreFromOutbox()} 捞回来继续补写。</p>
     */
    private final Map<String, Block> storageRetryBlocks = new ConcurrentHashMap<>();

    /**
     * 待补写区块的持久化 outbox（PLAN-019）。
     *
     * <p>可选注入：未装配（dev profile / 无库）时退化为纯内存重试队列 ——
     * 与 PLAN-017 第一切片的行为一致，不影响既有部署。</p>
     */
    @Autowired(required = false)
    private org.nexus.core.pending.PendingBlockOutbox blockOutbox;

    /** 已从持久化 outbox 恢复过一次（避免每个调度周期重复捞库）。 */
    private volatile boolean outboxRestored = false;

    /**
     * unknown-proposer 挂起的连续观测（PLAN-051）：同一区块高度被
     * "validator set not synchronized" 拒绝的次数。
     *
     * <p><b>为什么需要</b>：PLAN-050 测试床实证——一个节点可以因单条
     * unknown-proposer 块采纳不了 ⇒ `NewBlockEvent` 不再触发 ⇒ **投票永久
     * 停止**，而它的链高照涨、照旧服务补票、无 ERROR/WARN——"活着但弃权"
     * 的静默节点。旧代码只用 {@code logger.info} 记一次，运维无法从日志
     * 分辨"等验证人集的短暂挂起"与"已慢性弃权"。</p>
     *
     * <p>达到 {@link #ABSTAIN_DEFER_THRESHOLD} 次即由 {@link #processChain}
     * 升级为 ERROR 并打 {@code abstain-risk} 标记（与
     * {@code /consensus/finality} 的 {@code FinalityStalled} 告警互相印证）。</p>
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, Integer> unknownProposerDeferCount =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 同一高度的最后一次 unknown-proposer defer 时刷（PLAN-053）。
     *
     * <p>为什么要它：{@code unknownProposerDeferCount} 只增不减，
     * "启权风险”因此是本进程生命周期语义。PLAN-052 的口径限制已如实登记：
     * "当前正在启杀" 需要知道这个高度还在不在继续被拒。</p>
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> unknownProposerDeferLastNanos =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 连续 unknown-proposer 挂起升级为 ERROR 的阈值（同一高度）。 */
    private static final int ABSTAIN_DEFER_THRESHOLD = 5;

    /**
     * unknown-proposer defer 计数的只读快照（PLAN-052 指标化数据源）。
     *
     * <p>返回**副本**（调用方遍历不影响内部）；未装配/无 defer 时返回空表。
     * 语义与 {@link #unknownProposerDeferCount} 一致：按高度独立累计、
     * 只增不减（进程生命周期）。</p>
     *
     * @return 高度 → defer 次数 的不可变副本
     */
    public java.util.Map<Long, Integer> unknownProposerDeferSnapshot() {
        return java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(unknownProposerDeferCount));
    }

    /** defer 计数的升级阈值（与告警/指标同源，避免两处魔数漂移）。 */
    public static int abstainDeferThreshold() {
        return ABSTAIN_DEFER_THRESHOLD;
    }

    /**
     * 各高度最后一次 defer 的时间戳快照（{@link System#nanoTime()} 口径，PLAN-053）。
     *
     * <p>返回**副本**；未被 defer 过的高度不出现在表中。
     * 话义：有值 = "本进程启动以来该高度自少被 defer 过一次"。</p>
     */
    public java.util.Map<Long, Long> unknownProposerDeferLastNanosSnapshot() {
        return java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(unknownProposerDeferLastNanos));
    }

    /**
     * 单次从持久化 outbox 捞取的最大行数。
     *
     * <p>{@code loadPending} 是 {@code LIMIT} 无分页，所以"捞完"必须由
     * {@link #restoreFromOutbox()} 的循环驱动，不能依赖单次调用。</p>
     */
    private static final int RESTORE_BATCH_SIZE = 64;

    // 区块的写入全部走这里
    @Async
    public void addPendingBlocks(BlocksCache cache) {
        while (true) {
            List<Block> chain = cache.popLongestChain();
            if (chain == null || chain.size() == 0){
                break;
            }
            try {
                if (!processChain(chain)) {
                    break;
                }
            } catch (DataAccessException | AccountStoreUnavailableException e) {
                // 不能在 @Async 方法里把存储异常抛给默认异常处理器：
                // 那样链已经从 BlocksCache 弹出，且调用方拿不到任何重试信号。
                enqueueStorageRetry(chain, e);
                return;
            }
        }
    }

    private boolean processChain(List<Block> chain) {
        if (chainHasWritten(chain)) {
            return true;
        }
        logger.info("try to write blocks to local storage, size = " + chain.size());
        Block lastConfirmed = stateDB.getLastConfirmed();
        for (Block b : chain) {
            if (b.nHeight <= lastConfirmed.nHeight) {
                logger.info("the block has written");
                continue;
            }
            if (stateDB.hasBlockInCache(b.getHash())) {
                // PLAN-019：缓存命中不等于已落库 —— `stateDB.writeBlock` 会先把区块放进
                // 内存缓存再写库，写库失败时缓存里已经有了（PLAN-017 已记录的窗口）。
                // 因此这里要确认它**真的**在库里：落库了才清掉 outbox 行，否则保留
                // （等后续补写），不能因为"缓存里有"就把写入意图丢掉。
                reconcileOutboxEntry(b);
                logger.info("the block has written");
                continue;
            }
            Result res = rule.validateBlock(b);
            if (!res.isSuccess()) {
                if (isAccountStoreUnavailable(res)) {
                    throw new AccountStoreUnavailableException(res.getMessage());
                }
                // PLAN-003：父块缺失 → 可能是更长分叉链，交 ReorgManager 处理
                if (res.getMessage() != null
                        && (res.getMessage().contains("failed to find parent")
                            || res.getMessage().contains("cannot find parent"))
                        && reorgManager != null) {
                    boolean switched = reorgManager.handlePotentialFork(b);
                    if (switched) {
                        logger.info("PendingBlocks: fork switch executed for block height={}", b.nHeight);
                        continue;
                    }
                }
                if (res.getMessage() != null
                        && res.getMessage().startsWith("unknown proposer")) {
                    // PLAN-051：unknown-proposer 挂起的连续计数与升级（观测，不改 defer 语义）
                    int deferCount = unknownProposerDeferCount.merge(b.nHeight, 1, Integer::sum);
        unknownProposerDeferLastNanos.put(b.nHeight, System.nanoTime());
                    if (deferCount >= ABSTAIN_DEFER_THRESHOLD) {
                        logger.error("PendingBlocks: {}; DEFERRED {} times at height={} "
                                        + "(abstain-risk: this node may have stopped voting — "
                                        + "check /consensus/finality latestVotedEpoch and "
                                        + "/consensus/catch-up validators)",
                                res.getMessage(), deferCount, b.nHeight);
                    } else {
                        logger.info("PendingBlocks: {}; block will be retried after validator-set sync (height={}, deferCount={})",
                                res.getMessage(), b.nHeight, deferCount);
                    }
                    return false;
                }
                logger.error("validate the block fail error = " + res.getMessage());
                return false;
            }
            Result result = merkleRule.validateBlock(b);
            if (!result.isSuccess()) {
                if (isAccountStoreUnavailable(result)) {
                    throw new AccountStoreUnavailableException(result.getMessage());
                }
                // PLAN-012 A-2（信任签名+重放后校验，审核决策）：
                // 同步路径 merkle 状态校验失败不阻塞写链——明确日志后
                // 继续完整写链（stateDB.writeBlock），B 的 best 更新、
                // 高度认知恢复；状态一致性由 MerkleHandler 对账协议
                // （GET_TREE_NODES/TREE_NODES）异步对账。
                logger.warn("merkle state validate deferred (sync path, A-2): height={}, reason={} "
                        + "→ full chain write, MerkleHandler will reconcile",
                        b.nHeight, result.getMessage());
                b.weight = 1;
                stateDB.writeBlock(b);
                // 对账触发点（补）：写链后调用 writeBlockToCache 发布
                // MerkleMessageEvent → MerkleHandler.getRootTreeNodes →
                // GET_TREE_NODES 对账（此前该分支 continue 跳过了触发，
                // 导致状态树从未同步、hashMerkleState 全零）
                merkleTreeManager.writeBlockToCache(b);
                removeFromOutbox(b);
                continue;
            }
            b.weight = 1;
            stateDB.writeBlock(b);
            removeFromOutbox(b);
        }
        return true;
    }

    /**
     * 缓存命中但可能未落库时的 outbox 对账（PLAN-019）。
     *
     * <p>真落库了 → 清掉 outbox 行；没落库 → 保留行（等补写），并显式告警
     * （这是 PLAN-017 记录的"缓存先于落库"窗口，诚实暴露而不是悄悄丢）。</p>
     */
    private void reconcileOutboxEntry(Block block) {
        if (blockOutbox == null || block == null) {
            return;
        }
        try {
            if (stateDB.hasBlock(block.getHash())) {
                removeFromOutbox(block);
            } else {
                logger.warn("PendingBlocks: block is cached but not persisted (height={}); "
                        + "keeping its outbox entry for a later retry", block.nHeight);
            }
        } catch (RuntimeException e) {
            logger.warn("PendingBlocks: failed to reconcile outbox entry (height={}): {}",
                    block.nHeight, e.getMessage());
        }
    }

    private static boolean isAccountStoreUnavailable(Result result) {
        return result.getMessage() != null
                && result.getMessage().startsWith(MerkleRule.ACCOUNT_STORE_UNAVAILABLE_PREFIX);
    }

    private void enqueueStorageRetry(List<Block> chain, RuntimeException error) {
        for (Block b : chain) {
            if (b != null) {
                storageRetryBlocks.put(b.getHashHexString(), b);
                // PLAN-019：同时落持久化 outbox（数据库整体不可用时这一步会失败，
                // 内存队列兜底；数据库可用但链写失败时，写库意图就真的留住了）。
                persistToOutbox(b, error);
            }
        }
        logger.warn("PendingBlocks paused at heights {}-{}: storage unavailable: {}; "
                        + "queued {} block(s) for retry",
                chain.get(0).nHeight, chain.get(chain.size() - 1).nHeight,
                error.getMessage(), chain.size());
    }

    /** 把待补写区块记进持久化 outbox（失败只记日志，不影响主流程）。 */
    private void persistToOutbox(Block block, RuntimeException error) {
        if (blockOutbox == null) {
            return;
        }
        try {
            blockOutbox.enqueue(block);
            if (error != null) {
                blockOutbox.markFailure(block, error.getMessage());
            }
        } catch (RuntimeException e) {
            logger.warn("PendingBlocks: failed to persist block to outbox (height={}): {}",
                    block.nHeight, e.getMessage());
        }
    }

    /** 区块补写成功后清掉 outbox 里的对应行。 */
    private void removeFromOutbox(Block block) {
        if (blockOutbox == null || block == null) {
            return;
        }
        try {
            blockOutbox.remove(block);
        } catch (RuntimeException e) {
            logger.warn("PendingBlocks: failed to remove block from outbox (height={}): {}",
                    block.nHeight, e.getMessage());
        }
    }

    /**
     * 启动后从持久化 outbox 恢复待补写区块（PLAN-019）。
     *
     * <p>这些区块是"上一进程收到并校验过、但没写进链存储"的 —— 重启后把它们
     * 重新投进采纳路径即可，不必等 P2P 同步再拉一遍。</p>
     */
    @Scheduled(fixedDelay = 15_000L, initialDelay = 20_000L)
    public void restoreFromOutbox() {
        if (outboxRestored || blockOutbox == null) {
            return;
        }
        try {
            int total = 0;
            while (true) {
                // pendingCount() < 0 表示"读不到"（未装配 / 库不可用）——
                // JdbcPendingBlockOutbox.loadPending 在库异常时会吞掉异常并返回**空列表**，
                // 所以"空"不等于"没有待恢复区块"，必须先问一次数量。（缺陷 B）
                int before = blockOutbox.pendingCount();
                List<Block> pending = blockOutbox.loadPending(RESTORE_BATCH_SIZE);
                if (pending.isEmpty()) {
                    if (before < 0) {
                        // 存储不可用：不得当成"已恢复完"。不置 outboxRestored，
                        // 下一轮调度再试——否则 PG 未恢复时重启会永久丢掉整个 outbox。
                        logger.warn("PendingBlocks: cannot read pending block outbox "
                                + "(storage unavailable?); will retry next cycle");
                        return;
                    }
                    // 真的捞到空批 = 没有待恢复区块，此时才允许标记完成。
                    outboxRestored = true;
                    if (total > 0) {
                        logger.info("PendingBlocks: restored {} pending block(s) from persistent outbox",
                                total);
                    }
                    return;
                }
                // 走同一条采纳路径（内部会做校验与写链，并清理 outbox 行）。
                addPendingBlocks(new BlocksCache(pending));
                int after = blockOutbox.pendingCount();
                total += pending.size();
                if (before >= 0 && after >= before) {
                    // 本批没有任何 outbox 行被移除（存储仍不可用 / 块无法落库）。
                    // 必须停止，否则会对同一批无限重试；且不置 outboxRestored，
                    // 保留下一轮重试的机会。
                    logger.warn("PendingBlocks: outbox restore made no progress "
                            + "(still {} pending row(s)); will retry next cycle", after);
                    return;
                }
            }
        } catch (RuntimeException e) {
            // 恢复失败不阻断调度：下一轮再试（outboxRestored 只在成功路径置位）。
            logger.warn("PendingBlocks: failed to restore pending blocks from outbox: {}", e.getMessage());
        }
    }

    /** 待补写区块的观测快照（内存 + 持久化 outbox）。 */
    public Map<String, Object> outboxStatus() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("memoryPending", storageRetryBlocks.size());
        int dbPending = -1;
        long oldestCreatedAt = -1L;
        int maxAttempts = -1;
        if (blockOutbox != null) {
            try {
                dbPending = blockOutbox.pendingCount();
            } catch (RuntimeException e) {
                dbPending = -1;
            }
            try {
                oldestCreatedAt = blockOutbox.oldestCreatedAt();
            } catch (RuntimeException e) {
                oldestCreatedAt = -1L;
            }
            try {
                maxAttempts = blockOutbox.maxAttempts();
            } catch (RuntimeException e) {
                maxAttempts = -1;
            }
        }
        data.put("databasePending", dbPending);
        // PLAN-039（roadmap #29 ②）：滞留可见性——行数不清零 + 最老行年龄大 /
        // 重试次数高 ⇒ 有永远写不进去的区块；本类不自动清理（outbox 行是恢复
        // 数据），由运维据 age/attempts 决策。
        data.put("oldestPendingCreatedAt", oldestCreatedAt);
        data.put("maxAttempts", maxAttempts);
        data.put("enabled", blockOutbox != null);
        return data;
    }

    /**
     * PG 短时恢复后重试待采纳链。方法内直接调用 {@link #addPendingBlocks}，
     * 不经过 Spring 代理，因此重试在调度线程同步执行，避免 @Async 自调用失效。
     */
    @Scheduled(fixedDelay = 5_000L)
    public void retryStorageFailures() {
        if (storageRetryBlocks.isEmpty()) {
            return;
        }
        List<Block> retry = new ArrayList<>();
        for (String key : new ArrayList<>(storageRetryBlocks.keySet())) {
            Block b = storageRetryBlocks.remove(key);
            if (b != null) {
                retry.add(b);
            }
        }
        if (retry.isEmpty()) {
            return;
        }
        logger.info("PendingBlocks retrying {} queued block(s) after storage failure", retry.size());
        addPendingBlocks(new BlocksCache(retry));
    }

    private boolean chainHasWritten(List<Block> chain) {
        if (chain == null || chain.size() == 0) {
            return true;
        }
        byte[] hash = chain.get(chain.size() - 1).getHash();
        return stateDB.hasBlock(hash) || bc.hasBlock(hash);
    }
}
