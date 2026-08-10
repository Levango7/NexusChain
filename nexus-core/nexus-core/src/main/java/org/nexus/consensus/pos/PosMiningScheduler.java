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
     * 构造函数注入。
     *
     * @param posConsensus    PoS 共识门面（实际为 {@link DefaultPosConsensus}，委托至 {@link PosConsensusEngine}）
     * @param consensusConfig 共识配置
     */
    public PosMiningScheduler(PosConsensus posConsensus, ConsensusConfig consensusConfig) {
        this.posConsensus = posConsensus;
        this.consensusConfig = consensusConfig;
        logger.info("PosMiningScheduler initialized; will propose every 1s when enabled");
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