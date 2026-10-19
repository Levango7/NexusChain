package org.nexus.core;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import org.nexus.p2p.Peer;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

/**
 * 区块来源追踪（PLAN-014 Phase 2）。
 *
 * <p>问题：同步入口收到区块后即与对端解耦（{@code SyncManager} 原实现丢弃
 * remote），确认期（≥3 深度）发现状态根不一致时已无从知道"这些块是谁给的"。
 * 本组件在 P2P 收块入口记录 <b>区块哈希 → (高度, 对端)</b> 的有界映射，
 * 供强制处置（回滚 + 拉黑）阶段按高度区间反查对端。</p>
 *
 * <p>边界（诚实记录）：</p>
 * <ul>
 *   <li>只记录 P2P 同步入口（{@code SyncManager.onBlocks / onProposal}）；
 *       本地出块（{@code PosConsensusEngine} 采纳）不记录——不存在"自拉黑"风险。</li>
 *   <li>有界容量（{@value #CAPACITY} 条，LRU 淘汰）、不持久化：进程重启后
 *       历史归因丢失，仅影响重启后的旧块追溯，不影响实时处置。</li>
 * </ul>
 */
@Component
public class BlockOriginTracker {

    static final int CAPACITY = 4096;

    private final ConcurrentMap<String, Origin> origins;

    public BlockOriginTracker() {
        this.origins = new ConcurrentLinkedHashMap.Builder<String, Origin>()
                .maximumWeightedCapacity(CAPACITY)
                .build();
    }

    /** 记录一个 P2P 收到的区块及其来源对端；block / peer 任一为空则忽略。 */
    public void record(Block block, Peer peer) {
        if (block == null || peer == null) {
            return;
        }
        record(block.getHashHexString(), block.nHeight, peer);
    }

    /** 记录入口（哈希以 hex 字符串表示；便于测试与内部复用）。 */
    public void record(String hashHex, long height, Peer peer) {
        if (hashHex == null || peer == null) {
            return;
        }
        origins.put(hashHex, new Origin(height, peer));
    }

    /** 返回所有"高度严格大于 height"的已记录对端（去重）。 */
    public Set<Peer> peersAboveHeight(long height) {
        Set<Peer> peers = new HashSet<>();
        for (Origin origin : origins.values()) {
            if (origin.height > height) {
                peers.add(origin.peer);
            }
        }
        return peers;
    }

    /** 当前记录条数（测试/观测用）。 */
    public int size() {
        return origins.size();
    }

    /**
     * 单条来源记录（不可变）。
     */
    public static final class Origin {

        public final long height;

        public final Peer peer;

        public Origin(long height, Peer peer) {
            this.height = height;
            this.peer = peer;
        }
    }
}
