package org.nexus.consensus.pos;

import org.nexus.core.Block;

/**
 * PoS 共识核心接口。
 *
 * <p>定义权益证明共识下的提案、验证与惩罚能力。</p>
 *
 * @since 1.2
 */
public interface PosConsensus {

    /**
     * 发起新区块提案。
     *
     * @return 提案区块
     */
    Block propose();

    /**
     * 验证区块合法性。
     *
     * @param block 待验证区块
     * @return 验证通过返回 true
     */
    boolean validate(Block block);

    /**
     * 对作恶验证者执行惩罚（slash）。
     *
     * @param validator 被惩罚的验证者
     */
    void slash(Validator validator);
}