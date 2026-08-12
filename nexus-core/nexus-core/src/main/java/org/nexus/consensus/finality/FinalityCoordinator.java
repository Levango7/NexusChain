package org.nexus.consensus.finality;

import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.core.Block;
import org.nexus.core.event.NewBlockMinedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.util.Objects;

/**
 * 最终性协调器（NexFinality 闭环关键件）：监听出块事件，在 epoch 边界自动投票。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>监听 {@link NewBlockMinedEvent}，识别 epoch 边界检查点</li>
 *   <li>若本节点是活跃验证人，自动为该检查点产出 {@link Vote} 并提交至 {@link FinalityGadget}</li>
 *   <li>投票签名当前用 Ed25519 占位字节承载（M3 BLS 集成就位后替换）</li>
 * </ul>
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li>幂等：同节点同 epoch 同检查点只投一次（由 FinalityGadget 防重）</li>
 *   <li>fail-closed：非活跃验证人不投票；gadget 未装配时不动作</li>
 *   <li>epoch 长度可配，默认 32（与 ADR-030 一致）</li>
 * </ul>
 */
public class FinalityCoordinator {

    private static final Logger log = LoggerFactory.getLogger(FinalityCoordinator.class);

    private final FinalityGadget gadget;
    private final ValidatorRegistry validatorRegistry;
    private final long epochLength;
    private final String selfValidatorAddress;

    /**
     * @param gadget               最终性投票收集器
     * @param validatorRegistry    验证人注册表（判定本节点是否活跃验证人）
     * @param epochLength          epoch 长度（每多少个块一个检查点）
     * @param selfValidatorAddress 本节点验证人地址；非验证人传 null（协调器空转）
     */
    public FinalityCoordinator(FinalityGadget gadget,
                               ValidatorRegistry validatorRegistry,
                               long epochLength,
                               String selfValidatorAddress) {
        this.gadget = Objects.requireNonNull(gadget, "gadget must not be null");
        this.validatorRegistry = Objects.requireNonNull(validatorRegistry, "validatorRegistry must not be null");
        this.epochLength = epochLength <= 0 ? 32 : epochLength;
        this.selfValidatorAddress = selfValidatorAddress;
    }

    /**
     * 出块事件处理：命中 epoch 边界时自动投票。
     */
    @EventListener
    public void onNewBlock(NewBlockMinedEvent event) {
        if (event == null || event.getBlock() == null) {
            return;
        }
        Block block = event.getBlock();
        onBlock(block);
    }

    /**
     * 核心逻辑（包内可测）：对给定区块判断是否到达检查点并投票。
     *
     * @return 若投出票则返回 FinalityRecord，否则返回 null
     */
    FinalityRecord onBlock(Block block) {
        // fail-closed：本节点非验证人则不参与投票
        if (selfValidatorAddress == null || selfValidatorAddress.isEmpty()) {
            return null;
        }
        Validator self = validatorRegistry.getValidator(selfValidatorAddress);
        if (self == null || self.getStatus() != ValidatorStatus.ACTIVE) {
            log.debug("Skip finality vote: self validator {} not active", selfValidatorAddress);
            return null;
        }

        long height = block.nHeight;
        if (!isCheckpoint(height)) {
            return null;
        }

        long epoch = epochOf(height);
        // 检查点哈希直接使用 Block 原始哈希字节（避免 hex 字符串二次编码造成的口径不一致）
        byte[] blockHash = block.getHash();
        byte[] checkpointHash = blockHash != null ? blockHash : new byte[0];

        // M1/M2：签名以 Ed25519 占位字节承载；M3 集成 BLS 后替换
        byte[] sig = ("finality-vote:" + epoch).getBytes();
        Vote vote = new Vote(epoch, checkpointHash, selfValidatorAddress, sig);

        FinalityRecord record = gadget.submitVote(vote);
        log.info("Finality vote submitted: epoch={}, height={}, validator={}, finalized={}, progress={}%",
                epoch, height, selfValidatorAddress, record.isFinalized(), record.progressPercent());
        return record;
    }

    /**
     * 判断某高度是否为检查点（epoch 边界）。
     */
    public boolean isCheckpoint(long height) {
        return height > 0 && height % epochLength == 0;
    }

    /**
     * 高度所属 epoch（1-based）。
     */
    public long epochOf(long height) {
        return (height - 1) / epochLength + 1;
    }

    public long getEpochLength() {
        return epochLength;
    }
}
