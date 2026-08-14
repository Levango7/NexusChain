package org.nexus.core;

import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.db.StateDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 分叉重组管理器（PLAN-003：受控切换 + 最终化护栏）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li><b>分叉检测</b>：收到父块缺失的区块且高度严格高于本地 best，且其父链
 *       可回溯到本地已确认链 → 判定为更长分叉链</li>
 *   <li><b>受控切换</b>：回滚本地分叉段（{@link StateDB#rollbackTo}）→ 写入长链</li>
 *   <li><b>最终化护栏</b>：分叉点已最终化（NexFinality 2/3 权重）时<b>禁止切换</b>
 *       （不可逆结算语义，审核决策：完全禁止）</li>
 * </ul>
 */
@Component
public class ReorgManager {

    private static final Logger log = LoggerFactory.getLogger(ReorgManager.class);

    private final StateDB stateDB;
    private final FinalityGadget finalityGadget;

    public ReorgManager(@Autowired(required = false) StateDB stateDB,
                        @Autowired(required = false) FinalityGadget finalityGadget) {
        this.stateDB = stateDB;
        this.finalityGadget = finalityGadget;
    }

    /**
     * 尝试处理分叉（PLAN-003 入口）：父块缺失时由 PendingBlocksManager 调用。
     *
     * @param tip 分叉链顶端区块（父块本地缺失）
     * @return 已执行切换返回 true；不满足条件/被护栏拒绝返回 false
     */
    public boolean handlePotentialFork(Block tip) {
        if (stateDB == null || tip == null) {
            return false;
        }
        Block localBest = stateDB.getBestBlock();
        if (localBest == null) {
            return false;
        }

        // 1) 阈值：对端高度必须严格高于本地 best（审核决策：严格更高）
        if (tip.nHeight <= localBest.nHeight) {
            log.debug("Fork skip: tip height {} not greater than local best {}",
                    tip.nHeight, localBest.nHeight);
            return false;
        }

        // 2) 回溯分叉链：从 tip 沿 hashPrevBlock 收集，直到父块属于本地主链
        //    （父块存在且其高度 ≤ 本地 best —— 分叉链自身的块虽在缓存，
        //     但高度 > 本地 best，不会误判为本地主链交点）
        List<Block> forkChain = new ArrayList<>();
        Block cur = tip;
        Block forkPoint = null;
        while (cur != null) {
            Block parent = stateDB.getBlock(cur.hashPrevBlock);
            if (parent != null && parent.nHeight <= localBest.nHeight) {
                forkPoint = parent;
                break;
            }
            forkChain.add(0, cur);  // 前插保持高度升序
            cur = parent;
        }

        // 3) 可回溯性：必须能回溯到本地链（审核决策：可回溯才切换）
        if (forkPoint == null) {
            log.info("Fork skip: tip {} not connected to local chain (orphan)", tip.nHeight);
            return false;
        }
        long forkHeight = forkPoint.nHeight;

        // 4) 最终化护栏（审核决策：完全禁止已最终化链段切换）
        if (finalityGadget != null && finalityGadget.isFinalized(epochOf(forkHeight), forkPoint.getHash())) {
            log.warn("Fork REJECTED (finality guard): fork point height={} is finalized; switch forbidden",
                    forkHeight);
            return false;
        }

        log.info("Fork switch: local best={} → fork height={}, fork chain size={}, tip height={}",
                localBest.nHeight, forkHeight, forkChain.size(), tip.nHeight);

        // 5) 回滚本地分叉段（到分叉点高度；分叉点=best 时无需回滚，直接写新块）
        stateDB.rollbackTo(forkHeight);
        log.info("Fork switch: rolled back to height={}, writing {} blocks", forkHeight, forkChain.size());

        // 6) 写入分叉长链（状态重放由 writeBlock 内部完成）
        int written = 0;
        for (Block b : forkChain) {
            stateDB.writeBlock(b);
            written++;
        }
        log.info("Fork switch completed: wrote {} blocks, best now height {}",
                written,
                stateDB.getBestBlock() == null ? 0 : stateDB.getBestBlock().nHeight);
        return true;
    }

    /** 由检查点区块高度推导所属 epoch（与 FinalityCoordinator 语义一致：epochLength 配置）。 */
    private long epochOf(long height) {
        long epochLength = 32;  // 与 nexus.finality.epoch-length 默认一致；由调用方传入更精确
        return (height - 1) / epochLength + 1;
    }
}
