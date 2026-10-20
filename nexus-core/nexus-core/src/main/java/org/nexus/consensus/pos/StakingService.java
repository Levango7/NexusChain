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
     * 初始化质押账本（幂等，不改变 Validator 注册信息）。
     *
     * <p>用于启动恢复与对端验证人同步：验证人注册表已记录初始质押，
     * 但 StakingService 的账本可能为空。若直接调用 {@link #stake(String, BigDecimal)}
     * 会把注册表里的质押再加一遍，导致权重翻倍。</p>
     *
     * @param validator 验证者地址
     * @param amount    初始质押金额
     */
    void seedStake(String validator, BigDecimal amount);

    /**
     * 按周期向活跃验证者分发奖励。
     */
    void distributeRewards();
}