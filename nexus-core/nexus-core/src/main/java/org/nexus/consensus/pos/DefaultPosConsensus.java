package org.nexus.consensus.pos;

import org.nexus.core.Block;
import org.springframework.stereotype.Component;

/**
 * PoS 共识默认骨架实现。
 *
 * <p>当前为占位实现，留待后续接入完整 PoS 共识逻辑。</p>
 *
 * @since 1.2
 */
@Component
public class DefaultPosConsensus implements PosConsensus {

    @Override
    public Block propose() {
        // TODO: 按 VRF / VDF 选取提案者，打包交易生成新区块
        throw new UnsupportedOperationException("DefaultPosConsensus.propose: not yet implemented");
    }

    @Override
    public boolean validate(Block block) {
        // TODO: 校验区块签名、提案者合法性、slash 条件等
        return false;
    }

    @Override
    public void slash(Validator validator) {
        // TODO: 没收部分质押并将状态置为 SLASHED
    }
}