package org.nexus.consensus.pos;

import java.math.BigDecimal;
import java.util.List;

/**
 * 验证者集合管理接口。
 *
 * <p>维护当前活跃验证者集合，提供按高度选取提案者、
 * 更新质押等能力。</p>
 *
 * @since 1.2
 */
public interface ValidatorSet {

    /**
     * 获取当前活跃验证者列表。
     *
     * @return 活跃验证者集合
     */
    List<Validator> getActiveValidators();

    /**
     * 按区块高度选取提案者。
     *
     * @param height 区块高度
     * @return 被选中的提案者
     */
    Validator selectProposer(long height);

    /**
     * 更新指定验证者的质押金额。
     *
     * @param validator 验证者
     * @param amount    变更金额（正为增质押，负为减质押）
     */
    void updateStake(Validator validator, BigDecimal amount);
}