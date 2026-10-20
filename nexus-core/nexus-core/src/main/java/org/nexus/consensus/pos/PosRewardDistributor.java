package org.nexus.consensus.pos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * PoS 奖励分配器。
 *
 * <p>负责向出块者分配出块奖励 + 交易手续费，
 * 并按质押比例向所有活跃验证人分配 epoch 奖励。</p>
 *
 * @since 1.2
 */
@Component
public class PosRewardDistributor {

    private static final Logger logger = LoggerFactory.getLogger(PosRewardDistributor.class);

    /** 默认基础出块奖励 */
    private static final BigDecimal DEFAULT_BLOCK_REWARD = new BigDecimal("10");

    @Autowired
    private ValidatorRegistry validatorRegistry;

    @Autowired
    private StakingService stakingService;

    private final BigDecimal baseBlockReward;

    public PosRewardDistributor() {
        this(DEFAULT_BLOCK_REWARD);
    }

    public PosRewardDistributor(BigDecimal baseBlockReward) {
        this.baseBlockReward = baseBlockReward;
    }

    /**
     * 计算并记录出块奖励（基础出块奖励 + 交易手续费）。
     *
     * <p>出块奖励已经通过 coinbase 交易计入出块者账户余额；这里不再调用
     * {@link StakingService#stake(String, BigDecimal)} 把它累加到质押权重。
     * 否则每出一个块都会抬高本地验证人权重，而远端节点不会同步这笔奖励，
     * 最终导致各节点最终性权重分叉、单节点即可跨过 2/3 阈值。</p>
     *
     * @param proposerAddress 出块者地址
     * @param totalFees       本块包含的交易手续费总额
     * @return 实际分配奖励金额
     */
    public BigDecimal distributeBlockReward(String proposerAddress, BigDecimal totalFees) {
        BigDecimal fees = totalFees == null ? BigDecimal.ZERO : totalFees;
        BigDecimal reward = baseBlockReward.add(fees);
        logger.info("Block reward {} (base {} + fees {}) credited via coinbase to {}",
                reward, baseBlockReward, fees, proposerAddress);
        return reward;
    }

    /**
     * 向所有活跃验证人按质押比例分配 epoch 奖励。
     *
     * @param epochReward epoch 奖励总池
     */
    public void distributeEpochReward(BigDecimal epochReward) {
        if (epochReward == null || epochReward.signum() <= 0) {
            return;
        }
        List<Validator> active = validatorRegistry.getActiveValidators();
        if (active.isEmpty()) {
            logger.warn("No active validators to distribute epoch reward");
            return;
        }
        BigDecimal totalStake = BigDecimal.ZERO;
        for (Validator v : active) {
            totalStake = totalStake.add(v.getStakeAmount());
        }
        if (totalStake.signum() == 0) {
            logger.warn("Total stake is zero, cannot distribute epoch reward");
            return;
        }
        for (Validator v : active) {
            BigDecimal share = epochReward.multiply(v.getStakeAmount())
                    .divide(totalStake, 18, RoundingMode.DOWN);
            if (share.signum() > 0) {
                stakingService.stake(v.getAddress(), share);
                logger.info("Distributed epoch reward share {} to {}", share, v.getAddress());
            }
        }
    }

    public BigDecimal getBaseBlockReward() {
        return baseBlockReward;
    }
}