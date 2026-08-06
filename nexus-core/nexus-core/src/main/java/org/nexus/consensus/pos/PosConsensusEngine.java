package org.nexus.consensus.pos;

import org.nexus.core.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * PoS 共识引擎。
 *
 * <p>集成验证人注册、质押、提案者选取、奖励分配与惩罚机制，
 * 是 PoS 共识的编排门面。实现 {@link PosConsensus} 接口，
 * 标记 {@code @Primary} 优先于骨架实现 {@link DefaultPosConsensus}。</p>
 *
 * @since 1.2
 */
@Component
@Primary
public class PosConsensusEngine implements PosConsensus {

    private static final Logger logger = LoggerFactory.getLogger(PosConsensusEngine.class);

    @Autowired
    private ValidatorRegistry validatorRegistry;

    @Autowired
    private StakingService stakingService;

    @Autowired
    private PosProposer proposer;

    @Autowired
    private PosRewardDistributor rewardDistributor;

    @Autowired
    private SlashingService slashingService;

    /**
     * 发起新区块提案。
     *
     * <p>选取提案者后打包交易生成新区块。当前为骨架实现，
     * 实际出块流程待接入交易池与状态机。</p>
     *
     * @return 提案区块；无可用提案者返回 null
     */
    @Override
    public Block propose() {
        // 区块高度需从最新链头获取，骨架阶段使用占位高度 0
        long height = 0L;
        Validator selected = proposer.selectProposer(height);
        if (selected == null) {
            logger.warn("Propose failed: no proposer selected at height {}", height);
            return null;
        }
        logger.info("Proposing block at height {} by {}", height, selected.getAddress());
        // TODO: 打包交易池交易、构造区块、签名并广播
        return null;
    }

    /**
     * 验证区块合法性。
     *
     * <p>校验提案者是否在活跃验证人集合中，且质押满足门槛。
     * 完整校验（签名、slash 条件等）待接入。</p>
     *
     * @param block 待验证区块
     * @return 验证通过返回 true
     */
    @Override
    public boolean validate(Block block) {
        if (block == null) {
            return false;
        }
        // 骨架校验：区块非空即视为基本合法，完整校验待实现
        logger.debug("Validating block at height {}", block.nHeight);
        return true;
    }

    /**
     * 对作恶验证者执行惩罚。
     *
     * @param validator 被惩罚的验证者
     */
    @Override
    public void slash(Validator validator) {
        if (validator == null) {
            return;
        }
        slashingService.slash(validator.getAddress(), SlashingService.Offense.MALICIOUS);
    }

    /**
     * 对指定验证者执行指定类型的惩罚。
     *
     * @param validator 验证者
     * @param offense   违规类型
     * @return 罚没金额
     */
    public BigDecimal slash(Validator validator, SlashingService.Offense offense) {
        if (validator == null) {
            return BigDecimal.ZERO;
        }
        return slashingService.slash(validator.getAddress(), offense);
    }

    /**
     * 选取指定高度的提案者。
     *
     * @param height 区块高度
     * @return 提案者验证人
     */
    public Validator selectProposer(long height) {
        return proposer.selectProposer(height);
    }

    /**
     * 向出块者分配奖励。
     *
     * @param proposerAddress 出块者地址
     * @param fees            交易手续费
     * @return 分配奖励金额
     */
    public BigDecimal rewardProposer(String proposerAddress, BigDecimal fees) {
        return rewardDistributor.distributeBlockReward(proposerAddress, fees);
    }

    public ValidatorRegistry getValidatorRegistry() {
        return validatorRegistry;
    }

    public StakingService getStakingService() {
        return stakingService;
    }

    public PosProposer getProposer() {
        return proposer;
    }

    public PosRewardDistributor getRewardDistributor() {
        return rewardDistributor;
    }

    public SlashingService getSlashingService() {
        return slashingService;
    }
}