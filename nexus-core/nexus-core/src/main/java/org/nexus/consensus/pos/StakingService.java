package org.nexus.consensus.pos;

import java.math.BigDecimal;

/**
 * 质押服务接口。
 *
 * <p>面向验证者与委托者的质押 / 解质押 / 奖励分发能力。</p>
 *
 * @since 1.2
 */
public interface StakingService {

    /**
     * 增加质押。
     *
     * @param validator 验证者地址
     * @param amount    质押金额
     */
    void stake(String validator, BigDecimal amount);

    /**
     * 解除质押。
     *
     * @param validator 验证者地址
     * @param amount    解质押金额
     */
    void unstake(String validator, BigDecimal amount);

    /**
     * 查询验证者当前质押金额。
     *
     * @param validator 验证者地址
     * @return 质押金额
     */
    BigDecimal getStake(String validator);

    /**
     * 按周期向活跃验证者分发奖励。
     */
    void distributeRewards();
}