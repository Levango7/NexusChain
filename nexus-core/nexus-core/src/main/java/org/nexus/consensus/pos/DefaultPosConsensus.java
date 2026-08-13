package org.nexus.consensus.pos;

import org.nexus.core.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * PoS 共识默认实现。
 *
 * <p>委托 {@link PosConsensusEngine} 完成实际的提案、校验与惩罚逻辑，
 * 避免代码重复。两者都在 {@code nexus.consensus.mode=pos} 时启用。</p>
 *
 * <p>启用条件：{@code nexus.consensus.mode=pos}。默认（dpos）不会注入本类，
 * 避免占位实现被误注入。</p>
 *
 * @since 1.2
 */
@Component
@Primary
@ConditionalOnProperty(name = "nexus.consensus.mode", havingValue = "pos")
public class DefaultPosConsensus implements PosConsensus {

    private static final Logger logger = LoggerFactory.getLogger(DefaultPosConsensus.class);

    @Autowired
    private PosConsensusEngine engine;

    @Override
    public Block propose() {
        logger.debug("DefaultPosConsensus.propose delegating to PosConsensusEngine");
        return engine.propose();
    }

    @Override
    public boolean validate(Block block) {
        logger.debug("DefaultPosConsensus.validate delegating to PosConsensusEngine");
        return engine.validate(block);
    }

    @Override
    public void slash(Validator validator) {
        logger.debug("DefaultPosConsensus.slash delegating to PosConsensusEngine");
        engine.slash(validator);
    }

    /**
     * 对指定验证者执行指定类型的惩罚。
     *
     * @param validator 验证者
     * @param offense   违规类型
     * @return 罚没金额
     */
    public BigDecimal slash(Validator validator, SlashingService.Offense offense) {
        return engine.slash(validator, offense);
    }

    public PosConsensusEngine getEngine() {
        return engine;
    }
}
