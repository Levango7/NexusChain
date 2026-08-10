package org.nexus.bridge.relayer;

import java.math.BigDecimal;

/**
 * Relayer 激励机制接口。
 *
 * <p>定义对 relayer 的奖励计算、分发与惩罚能力。</p>
 *
 * @since 1.2
 */
public interface RelayerIncentive {

    /**
     * 计算中继请求应得奖励。
     *
     * @param request 中继请求
     * @return 奖励金额
     */
    BigDecimal calculateReward(RelayRequest request);

    /**
     * 向指定 relayer 分发奖励。
     *
     * @param relayerId relayer ID
     * @param amount    奖励金额
     */
    void distributeReward(String relayerId, BigDecimal amount);

    /**
     * 对作恶 relayer 执行惩罚。
     *
     * @param relayerId relayer ID
     * @param reason    惩罚原因
     */
    void slashRelayer(String relayerId, String reason);
}