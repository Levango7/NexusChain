package org.nexus.core;

import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.core.account.AccountDB;
import org.nexus.core.event.StateRootMismatchEvent;
import org.nexus.core.incubator.IncubatorDB;
import org.nexus.db.StateDB;
import org.nexus.p2p.Peer;
import org.nexus.p2p.PeersStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 状态根不一致处置器（PLAN-014 Phase 2 / PLAN-012 A-3）。
 *
 * <p>Phase 1 为观察位；本类把不一致处置升级为可配置动作：</p>
 * <ul>
 *   <li>{@code nexus.core.state-root-mismatch.action=observe}（默认）：
 *       仅记录日志与计数，用于灰度观测；</li>
 *   <li>{@code =rollback}：回滚不一致区块及之后高度（内存链缓存 +
 *       账户/孵化状态行）+ 拉黑相关对端。</li>
 * </ul>
 *
 * <p>并发与顺序（关键设计）：</p>
 * <ul>
 *   <li>事件由 {@code StateReplayManager} 在 StateDB 写锁内发布，本监听器
 *       {@link Async} 异步消费——强制处置不会阻塞确认路径，也不会在写锁内
 *       重入 {@code writeBlock}（历史教训：在写锁内回滚会触发 pendingBlock
 *       60s 自旋、破坏写入握手）；</li>
 *   <li>回滚 + 状态行清理通过 {@link StateDB#executeUnderWriteLock} 在与
 *       出块/确认互斥的临界区内完成（锁可重入，内部 rollbackTo 安全）；</li>
 *   <li>最终化护栏（ADR-030：已最终化区块及其祖先永不回滚）优先于处置：
 *       若不一致区块已最终化，拒绝回滚并记录 ERROR（需人工介入）。</li>
 * </ul>
 */
@Component
public class StateRootMismatchHandler {

    private static final Logger log = LoggerFactory.getLogger(StateRootMismatchHandler.class);

    static final String ACTION_ROLLBACK = "rollback";

    @Value("${nexus.core.state-root-mismatch.action:observe}")
    private String action;

    /** 与 FinalityCoordinator 同源的 epoch 长度配置（修复原硬编码 32 的护栏漂移）。 */
    @Value("${nexus.finality.epoch-length:32}")
    private long epochLength;

    @Autowired(required = false)
    private StateDB stateDB;

    @Autowired(required = false)
    private AccountDB accountDB;

    @Autowired(required = false)
    private IncubatorDB incubatorDB;

    @Autowired(required = false)
    private BlockOriginTracker blockOriginTracker;

    @Autowired(required = false)
    private PeersStorage peersStorage;

    @Autowired(required = false)
    private FinalityGadget finalityGadget;

    private final AtomicLong mismatchEvents = new AtomicLong();

    private final AtomicLong rollbacksExecuted = new AtomicLong();

    private final AtomicLong finalityRejections = new AtomicLong();

    private final AtomicLong blacklistedPeers = new AtomicLong();

    @Async
    @EventListener
    public void onStateRootMismatch(StateRootMismatchEvent event) {
        Block block = event == null ? null : event.getBlock();
        if (block == null) {
            return;
        }
        mismatchEvents.incrementAndGet();

        if (!isRollbackMode()) {
            log.info("state-root mismatch: action=observe, height={}, block={}",
                    block.nHeight, block.getHashHexString());
            return;
        }

        if (stateDB == null) {
            log.error("state-root mismatch: action=rollback but StateDB unavailable; skipped");
            return;
        }

        long target = block.nHeight - 1;
        if (target < 1) {
            log.error("state-root mismatch at height={}: cannot roll back below genesis; "
                    + "manual intervention required", block.nHeight);
            return;
        }

        Block best = stateDB.getBestBlock();
        if (best == null || best.nHeight <= target) {
            log.info("state-root mismatch: best height={} <= target={}, nothing to roll back",
                    best == null ? 0 : best.nHeight, target);
            return;
        }

        // 最终化护栏（ADR-030）：已最终化区块及其祖先永不回滚
        if (finalityGadget != null && finalityGadget.isFinalized(epochOf(block.nHeight), block.getHash())) {
            finalityRejections.incrementAndGet();
            log.error("state-root mismatch ENFORCEMENT REJECTED (finality guard): height={}, block={} "
                            + "is finalized; rollback forbidden — manual intervention required",
                    block.nHeight, block.getHashHexString());
            return;
        }

        try {
            int[] counts = new int[3]; // [0] rolledBack, [1] deletedAccounts, [2] deletedIncubators
            stateDB.executeUnderWriteLock(() -> {
                counts[0] = stateDB.rollbackTo(target);
                if (accountDB != null) {
                    counts[1] = accountDB.deleteFromHeight(target + 1);
                }
                if (incubatorDB != null) {
                    counts[2] = incubatorDB.deleteFromHeight(target + 1);
                }
            });

            Set<Peer> peers = blockOriginTracker == null
                    ? Set.of() : blockOriginTracker.peersAboveHeight(target);
            for (Peer peer : peers) {
                if (peersStorage != null) {
                    peersStorage.blockPeer(peer);
                }
            }
            blacklistedPeers.addAndGet(peers.size());
            rollbacksExecuted.incrementAndGet();
            log.error("state-root mismatch ENFORCED (rollback): targetHeight={}, rolledBack={} blocks, "
                            + "deletedAccounts={}, deletedIncubators={}, blacklistedPeers={}, block={}, reason={}",
                    target, counts[0], counts[1], counts[2], peers.size(),
                    block.getHashHexString(), event.getReason());
        } catch (RuntimeException e) {
            log.error("state-root mismatch enforcement failed: height={}, block={}",
                    block.nHeight, block.getHashHexString(), e);
        }
    }

    private boolean isRollbackMode() {
        return action != null && ACTION_ROLLBACK.equalsIgnoreCase(action.trim());
    }

    private long epochOf(long height) {
        long len = epochLength > 0 ? epochLength : 32;
        return (height - 1) / len + 1;
    }

    public long getMismatchEvents() {
        return mismatchEvents.get();
    }

    public long getRollbacksExecuted() {
        return rollbacksExecuted.get();
    }

    public long getFinalityRejections() {
        return finalityRejections.get();
    }

    public long getBlacklistedPeers() {
        return blacklistedPeers.get();
    }
}
