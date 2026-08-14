package org.nexus.consensus.pos;

import org.nexus.consensus.pow.ConsensusConfig;
import org.nexus.core.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PoS 出块调度器。
 *
 * <p>镜像 PoW {@link org.nexus.consensus.pow.Miner#tryMine()} 的调度模式，
 * 以 {@code @Scheduled(fixedRate = 1000)} 每秒触发一次，调用
 * {@link PosConsensus#propose()} 尝试出块。</p>
 *
 * <p>启用条件：{@code nexus.consensus.mode=pos}。与 {@link PosConsensusEngine}
 * 和 {@link DefaultPosConsensus} 的 {@code @ConditionalOnProperty} 保持一致，
 * 避免在 dpos/pow 模式下因 {@link PosConsensus} bean 缺失而导致上下文装配失败。</p>
 *
 * <h3>调度流程</h3>
 * <ol>
 *   <li>每秒触发 {@link #tryPropose()}</li>
 *   <li>非 PoS 模式或未开启挖矿则直接返回（防御性检查，正常情况下本 bean 不会在非 pos 模式存在）</li>
 *   <li>调用 {@link PosConsensus#propose()}：内部已完成验证人身份检查、提案者选择、
 *       签名、广播；返回 null 表示非本节点轮次或条件不满足，属正常情况</li>
 *   <li>异常被捕获并记录，避免调度线程中断</li>
 * </ol>
 *
 * @since 1.2
 */
@Component
@ConditionalOnProperty(name = "nexus.consensus.mode", havingValue = "pos")
public class PosMiningScheduler {

    private static final Logger logger = LoggerFactory.getLogger(PosMiningScheduler.class);

    private final PosConsensus posConsensus;
    private final ConsensusConfig consensusConfig;

    /**
     * 对端高度来源（PLAN-002 本地出块抑制）。@Lazy 打破与 SyncManager 的循环依赖。
     */
    private final org.springframework.beans.factory.ObjectProvider<org.nexus.sync.SyncManager> syncManagerProvider;

    /** 本节点状态库（获取本地最佳高度，PLAN-002 抑制比较）。 */
    @org.springframework.beans.factory.annotation.Autowired
    private org.nexus.db.StateDB stateDB;

    /**
     * 构造函数注入。
     *
     * @param posConsensus         PoS 共识门面（实际为 {@link DefaultPosConsensus}，委托至 {@link PosConsensusEngine}）
     * @param consensusConfig      共识配置
     * @param syncManagerProvider  对端高度来源（可选，单节点/无 P2P 为 empty）
     */
    public PosMiningScheduler(PosConsensus posConsensus, ConsensusConfig consensusConfig,
                              @org.springframework.beans.factory.annotation.Autowired(required = false)
                              org.springframework.beans.factory.ObjectProvider<org.nexus.sync.SyncManager> syncManagerProvider) {
        this.posConsensus = posConsensus;
        this.consensusConfig = consensusConfig;
        this.syncManagerProvider = syncManagerProvider;
        logger.info("PosMiningScheduler initialized; will propose every 1s when enabled");
    }

    /**
     * PLAN-002 本地出块抑制：若已知对端链高度显著高于本节点（落后于更长链），
     * 暂停本地出块让更长链先传播收敛，避免双链分叉持续。
     * 单节点/无对端（provider 无值）时不抑制。
     */
    private boolean isBehindPeerChain() {
        org.nexus.sync.SyncManager sm = syncManagerProvider == null ? null : syncManagerProvider.getIfAvailable();
        if (sm == null) {
            return false;
        }
        long peerHeight = sm.getKnownMaxPeerHeight();
        if (peerHeight <= 0) {
            return false;
        }
        long localHeight = localBestHeight();
        // 对端显著领先（≥ 阈值）才抑制，避免瞬时抖动误停出块
        return peerHeight > localHeight + BEHIND_THRESHOLD;
    }

    /** 对端领先多少块才抑制本地出块（防瞬时抖动）。 */
    private static final long BEHIND_THRESHOLD = 2;

    /** 本节点当前最佳高度（stateDB.getBestBlock）。 */
    private long localBestHeight() {
        try {
            if (stateDB != null) {
                org.nexus.core.Block best = stateDB.getBestBlock();
                return best == null ? 0 : best.nHeight;
            }
        } catch (Exception e) {
            logger.debug("localBestHeight unavailable: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 每秒尝试提案一个新区块。
     *
     * <p>仅当处于 PoS 模式且开启挖矿时才调用 {@link PosConsensus#propose()}。
     * {@code propose()} 内部已处理：验证人身份检查、提案者选择、签名、广播。
     * 返回 {@code null} 表示非本节点轮次或条件不满足，属正常情况，不记录错误。</p>
     */
    @Scheduled(fixedRate = 1000)
    public void tryPropose() {
        // 防御性检查：本 bean 理论上仅在 pos 模式下创建，但仍校验以应对配置热更新等边界场景
        if (!consensusConfig.isPosMode()) {
            return;
        }
        if (!consensusConfig.isEnableMining()) {
            return;
        }
        // PLAN-002 本地出块抑制：若已知对端链更长（本节点落后），暂停本地出块，
        // 让更长链先传播收敛（避免双链分叉持续）；跟随由同步层（receiveBlocks）完成。
        if (isBehindPeerChain()) {
            return;
        }
        try {
            Block block = posConsensus.propose();
            // propose() 返回 null 属正常情况（非本节点轮次、密钥未绑定验证人等），仅 debug 级别记录
            if (block != null) {
                logger.debug("PoS block proposed at height {}", block.nHeight);
            }
        } catch (Exception e) {
            // 捕获所有异常，避免 Scheduled 任务因未捕获异常而停止调度
            logger.error("PoS propose error: {}", e.getMessage(), e);
        }
    }
}