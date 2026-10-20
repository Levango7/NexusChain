package org.nexus.consensus.finality;

import org.nexus.consensus.finality.net.FinalityVoteBroadcaster;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.core.Block;
import org.nexus.core.event.NewBlockEvent;
import org.nexus.crypto.ed25519.Ed25519PrivateKey;
import org.nexus.crypto.ed25519.Ed25519PublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Objects;

/**
 * 最终性协调器（NexFinality 闭环关键件）：监听出块事件，在 epoch 边界自动投票。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>监听 {@link NewBlockMinedEvent}，识别 epoch 边界检查点</li>
 *   <li>若本节点是活跃验证人，自动为该检查点产出 {@link Vote} 并提交至 {@link FinalityGadget}</li>
 *   <li>投票签名使用注入的 Ed25519 密钥对产生真实签名（P0-1 审计修复，
 *       替代可伪造的 BLS-like 构造）；未注入密钥对时 fail-closed 拒绝投票</li>
 * </ul>
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li>幂等：同节点同 epoch 同检查点只投一次（由 FinalityGadget 防重）</li>
 *   <li>fail-closed：非活跃验证人不投票；gadget 未装配时不动作</li>
 *   <li>epoch 长度可配，默认 32（与 ADR-030 一致）</li>
 *   <li><b>Spring 装配</b>（ADR-031 决策 8 补充）：无 @Component 时新块事件无监听
 *       → 投票永不触发；本类现为 @Component + @EventListener，selfAddress 由
 *       {@link ValidatorNodeBootstrapper} 注册验证人后经 {@link #setSelfValidatorAddress} 注入</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "nexus.consensus.mode", havingValue = "pos")
public class FinalityCoordinator {

    private static final Logger log = LoggerFactory.getLogger(FinalityCoordinator.class);

    private final FinalityGadget gadget;
    private final ValidatorRegistry validatorRegistry;
    private final long epochLength;
    private final FinalityVoteBroadcaster broadcaster;
    /**
     * 投票签名密钥（P0-1 审计修复）。
     *
     * <p>早期实现注入 {@code BlsSigner}（secp256k1 上的 σ=H(m)·PK 构造——PK 公开，
     * 任何人可对任意消息计算出通过验证的"签名"，属可伪造方案）。现改为 Ed25519
     * 真实签名：签名密钥与 {@code ValidatorRegistry} 登记的验证人公钥配对
     * （由 {@code ValidatorNodeBootstrapper} 自举注册时注入），伪造签名需要私钥。</p>
     *
     * <p>通过 setter 注入（密钥非 Spring bean）：未注入时 {@link #onBlock(Block)}
     * fail-closed 拒绝投票。</p>
     */
    private Ed25519PrivateKey voteSigningKey;
    /** 随投票广播的验证人公钥（与注册表登记 hex 一致，供聚合器/绑定校验使用） */
    private Ed25519PublicKey voteVerifyingKey;
    private String selfValidatorAddress;

    /**
     * 最终性投票写库失败时的有界重试队列。
     *
     * <p>Phase 6B 故障注入实证：数据库短时不可用时，{@code submitVote} 抛出的异常
     * 会沿 {@code NewBlockEvent} 监听器传播到 {@code StateDB}，使 pendingBlock 等待
     * 60 秒才清理，连带推迟区块落盘。最终性写失败不应破坏区块采纳，因此这里把
     * 投票留在内存队列，下一个区块事件再重试；队列满时丢弃最旧票并显式报错。</p>
     *
     * <p>边界：队列不持久化，进程崩溃会丢失；只覆盖本节点本地投票，远端票的
     * 重投依赖发送方重试。</p>
     */
    private static final int MAX_PENDING_VOTES = 256;
    private final Deque<Vote> pendingVotes = new ArrayDeque<>();

    /**
     * @param gadget               最终性投票收集器
     * @param validatorRegistry    验证人注册表（判定本节点是否活跃验证人）
     * @param epochLength          epoch 长度（每多少个块一个检查点）
     * @param broadcaster          P2P 投票广播器（可为 null）
     */
    @Autowired
    public FinalityCoordinator(FinalityGadget gadget,
                               ValidatorRegistry validatorRegistry,
                               @org.springframework.beans.factory.annotation.Value("${nexus.finality.epoch-length:32}") long epochLength,
                               @Autowired(required = false) FinalityVoteBroadcaster broadcaster) {
        this.gadget = Objects.requireNonNull(gadget, "gadget must not be null");
        this.validatorRegistry = Objects.requireNonNull(validatorRegistry, "validatorRegistry must not be null");
        this.epochLength = epochLength <= 0 ? 32 : epochLength;
        this.broadcaster = broadcaster;
    }

    /**
     * 兼容构造器（单进程/测试场景：无 P2P 广播器）。
     */
    public FinalityCoordinator(FinalityGadget gadget,
                               ValidatorRegistry validatorRegistry,
                               long epochLength) {
        this(gadget, validatorRegistry, epochLength, null);
    }

    /**
     * 注入本节点验证人地址（由 {@link ValidatorNodeBootstrapper} 自举注册后调用）。
     * 非验证人节点（bootstrapper 未启用）保持 null → 协调器空转不投票。
     */
    public void setSelfValidatorAddress(String selfValidatorAddress) {
        this.selfValidatorAddress = selfValidatorAddress;
    }

    /**
     * 注入投票签名密钥对（P0-1 审计修复）。
     *
     * <p>由 {@code ValidatorNodeBootstrapper} 在自举注册时注入：私钥用于签署投票，
     * 公钥必须与 {@code ValidatorRegistry} 为本验证人登记的公钥一致
     * （{@link FinalityGadget} 在计票前做绑定校验）。未注入时 fail-closed 不投票。</p>
     *
     * @param signingKey   Ed25519 私钥
     * @param verifyingKey 与私钥配对的 Ed25519 公钥（即注册表登记公钥）
     */
    public void setVoteSigningKeyPair(Ed25519PrivateKey signingKey, Ed25519PublicKey verifyingKey) {
        this.voteSigningKey = Objects.requireNonNull(signingKey, "signingKey must not be null");
        this.voteVerifyingKey = Objects.requireNonNull(verifyingKey, "verifyingKey must not be null");
    }

    /**
     * 已确认区块事件处理：命中 epoch 边界时自动投票。
     *
     * <p>必须监听 {@link NewBlockEvent}（StateDB 写链成功后发布），而不是
     * {@code NewBlockMinedEvent}（仅本节点提案时发布）。否则只有检查点高度的
     * 出块节点会投票；在 round-robin 下如果检查点高度总是同一个节点，
     * 另一验证人永远不投票，最终性退化为单节点。</p>
     */
    @EventListener
    public void onNewBlock(NewBlockEvent event) {
        if (event == null || event.getBlock() == null) {
            return;
        }
        Block block = event.getBlock();
        try {
            onBlock(block);
        } catch (RuntimeException e) {
            // 最终性只是区块采纳后的附加动作：任何异常都不能反向破坏 StateDB
            // 的 pendingBlock 状态更新。onBlock 内部已对已知的持久化失败做队列重试；
            // 这里兜底未来新增的运行时异常。
            log.error("Finality listener failed at height {}; block adoption continues", block.nHeight, e);
        }
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

        // 先重试历史失败票，再处理当前区块。非检查点区块也会触发重试，
        // 使数据库恢复后无需等待下一个 epoch 边界。
        flushPendingVotes();

        long height = block.nHeight;
        if (!isCheckpoint(height)) {
            return null;
        }

        long epoch = epochOf(height);
        // 检查点哈希直接使用 Block 原始哈希字节（避免 hex 字符串二次编码造成的口径不一致）
        byte[] blockHash = block.getHash();
        byte[] checkpointHash = blockHash != null ? blockHash : new byte[0];

        // P0-1 审计修复：使用 Ed25519 真实签名（替代可伪造的 BLS-like 构造）
        // fail-closed：未注入投票密钥对时拒绝投票，防止任何节点伪造投票
        if (voteSigningKey == null || voteVerifyingKey == null) {
            log.warn("Skip finality vote at epoch={}: no vote signing key pair injected, fail-closed", epoch);
            return null;
        }

        // 构造投票载荷并产生 Ed25519 签名
        // 载荷格式与 Vote.signingPayload() 保持一致：epoch || checkpointHash
        byte[] signingPayload = buildSigningPayload(epoch, checkpointHash);
        byte[] sigBytes;
        byte[] publicKeyBytes;
        try {
            sigBytes = voteSigningKey.sign(signingPayload);
            publicKeyBytes = voteVerifyingKey.getEncoded();
        } catch (Exception e) {
            log.error("Failed to sign finality vote at epoch={}: {}", epoch, e.getMessage());
            return null;
        }

        // 签名长度护栏：Ed25519 签名为 64 字节，确保满足聚合器最小长度要求
        if (sigBytes == null || sigBytes.length < 32) {
            log.error("Vote signature too short ({} bytes) at epoch={}, fail-closed",
                    sigBytes == null ? 0 : sigBytes.length, epoch);
            return null;
        }

        Vote vote = new Vote(epoch, checkpointHash, selfValidatorAddress, sigBytes, publicKeyBytes);

        try {
            FinalityRecord record = gadget.submitVote(vote);
            log.info("Finality vote submitted: epoch={}, height={}, checkpoint={}, validator={}, finalized={}, progress={}%",
                    epoch, height, org.apache.commons.codec.binary.Hex.encodeHexString(checkpointHash),
                    selfValidatorAddress, record.isFinalized(), record.progressPercent());
            // 广播投票至 P2P 网络（跨节点汇聚发送侧接线）
            broadcastQuietly(vote);
            return record;
        } catch (RuntimeException e) {
            enqueuePendingVote(vote);
            log.error("Finality vote persistence failed at epoch={}, height={}; queued for retry (pending={}): {}",
                    epoch, height, pendingVoteCount(), e.getMessage());
            return null;
        }
    }

    /**
     * 重试队列中的本地投票。写库仍失败时保留队首票，等待下一个区块事件；
     * 成功后移出队列并补发 P2P 广播。
     */
    private synchronized void flushPendingVotes() {
        while (!pendingVotes.isEmpty()) {
            Vote vote = pendingVotes.peekFirst();
            try {
                FinalityRecord record = gadget.submitVote(vote);
                pendingVotes.removeFirst();
                log.info("Finality vote retried: epoch={}, validator={}, finalized={}, progress={}%",
                        vote.getEpoch(), vote.getValidatorAddress(), record.isFinalized(), record.progressPercent());
                broadcastQuietly(vote);
            } catch (RuntimeException e) {
                log.warn("Finality vote retry still failing at epoch={}, validator={}; retained for next block: {}",
                        vote.getEpoch(), vote.getValidatorAddress(), e.getMessage());
                return;
            }
        }
    }

    /**
     * 入队一张本地投票。队列有界，避免数据库长期不可用时无界占用内存；
     * 溢出时丢弃最旧票并显式 ERROR，绝不静默吞掉。
     */
    private synchronized void enqueuePendingVote(Vote vote) {
        // 同一张票可能因区块重放/重复事件再次进入 onBlock，避免队列里堆积
        // 完全相同的 (epoch, checkpoint, validator) 项。
        for (Vote pending : pendingVotes) {
            if (pending.getEpoch() == vote.getEpoch()
                    && pending.getValidatorAddress().equals(vote.getValidatorAddress())
                    && Arrays.equals(pending.getCheckpointHash(), vote.getCheckpointHash())) {
                return;
            }
        }
        if (pendingVotes.size() >= MAX_PENDING_VOTES) {
            Vote dropped = pendingVotes.removeFirst();
            log.error("Finality retry queue full ({}); dropping oldest vote epoch={}, validator={}",
                    MAX_PENDING_VOTES, dropped.getEpoch(), dropped.getValidatorAddress());
        }
        pendingVotes.addLast(vote);
    }

    synchronized int pendingVoteCount() {
        return pendingVotes.size();
    }

    /** 广播失败不能反向影响已持久化的投票或区块采纳。 */
    private void broadcastQuietly(Vote vote) {
        if (broadcaster == null) {
            return;
        }
        try {
            broadcaster.broadcast(vote);
        } catch (RuntimeException e) {
            log.warn("Finality vote broadcast failed after persistence: epoch={}, validator={}: {}",
                    vote.getEpoch(), vote.getValidatorAddress(), e.getMessage());
        }
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

    /**
     * 构造投票签名载荷（与 {@link Vote#signingPayload()} 格式一致）。
     *
     * <p>载荷 = epoch（8 字节大端） || checkpointHash。</p>
     *
     * @param epoch          epoch 编号
     * @param checkpointHash 检查点哈希
     * @return 签名载荷字节
     */
    private static byte[] buildSigningPayload(long epoch, byte[] checkpointHash) {
        byte[] payload = new byte[8 + checkpointHash.length];
        for (int i = 0; i < 8; i++) {
            payload[i] = (byte) (epoch >>> (56 - 8 * i));
        }
        System.arraycopy(checkpointHash, 0, payload, 8, checkpointHash.length);
        return payload;
    }
}
