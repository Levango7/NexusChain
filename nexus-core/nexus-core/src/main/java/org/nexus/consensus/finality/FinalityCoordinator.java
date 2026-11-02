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
     * 最终性投票写库失败后的重试中枢（PLAN-018）。
     *
     * <p>Phase 6B 故障注入实证：数据库短时不可用时，{@code submitVote} 抛出的异常
     * 会沿 {@code NewBlockEvent} 监听器传播到 {@code StateDB}，使 pendingBlock 等待
     * 60 秒才清理，连带推迟区块落盘。最终性写失败不应破坏区块采纳，因此把投票
     * 交给 {@link FinalityOutboxManager} 排队重试。</p>
     *
     * <p>Phase 6C 第二切片起，该队列不再只是内存：管理器会同时把"写库意图"
     * （含完整签名）落到持久化 outbox 表，进程重启后仍能补写；接收侧收到的远端票
     * 也走同一条通道，<b>不再依赖发送方重发</b>。</p>
     */
    private final FinalityOutboxManager outboxManager;

    /** 失败日志采样窗口（毫秒）与窗口内被抑制的条数。 */
    private static final long FAILURE_LOG_WINDOW_MS = 10_000L;

    /**
     * 最终性快照的回溯窗（epoch 数）：链高多数时刻不在检查点上，快照从当前 epoch
     * 向下找最近一个有投票记录的 epoch（PLAN-044 实测修正：只看当前 epoch 会
     * 连续返回 no-progress）。8 个 epoch 足以覆盖"最终性正常跟上出块"的时延，
     * 又不会在大 epoch 号时线性扫描。
     */
    private static final long FINALITY_SNAPSHOT_LOOKBACK_EPOCHS = 8L;

    private final java.util.concurrent.atomic.AtomicLong lastFailureLogAt =
            new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong suppressedFailureLogs =
            new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * @param gadget               最终性投票收集器
     * @param validatorRegistry    验证人注册表（判定本节点是否活跃验证人）
     * @param epochLength          epoch 长度（每多少个块一个检查点）
     * @param broadcaster          P2P 投票广播器（可为 null）
     * @param outboxManager        投票补写 outbox 管理器
     */
    @Autowired
    public FinalityCoordinator(FinalityGadget gadget,
                               ValidatorRegistry validatorRegistry,
                               @org.springframework.beans.factory.annotation.Value("${nexus.finality.epoch-length:32}") long epochLength,
                               @Autowired(required = false) FinalityVoteBroadcaster broadcaster,
                               FinalityOutboxManager outboxManager) {
        this.gadget = Objects.requireNonNull(gadget, "gadget must not be null");
        this.validatorRegistry = Objects.requireNonNull(validatorRegistry, "validatorRegistry must not be null");
        this.epochLength = epochLength <= 0 ? 32 : epochLength;
        this.broadcaster = broadcaster;
        this.outboxManager = Objects.requireNonNull(outboxManager, "outboxManager must not be null");
        // 补写成功后必须补发一次广播：这张票在写库失败时并没有发出去过
        // （广播发生在写库成功之后），不补发就对端永远收不到它。
        this.outboxManager.setLocalVotePersistedListener(this::broadcastQuietly);
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
     * 兼容构造器（单进程/测试场景：无 P2P 广播器，但有独立的 outbox 管理器）。
     */
    public FinalityCoordinator(FinalityGadget gadget,
                               ValidatorRegistry validatorRegistry,
                               long epochLength,
                               FinalityVoteBroadcaster broadcaster) {
        this(gadget, validatorRegistry, epochLength, broadcaster, new FinalityOutboxManager(gadget));
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
        // 先补写历史失败票，再处理当前区块。非检查点区块也会触发补写，
        // 使数据库恢复后无需等待下一个 epoch 边界。
        //
        // PLAN-018：这句必须**在下面两个早退之前** —— 否则本节点被移出 ACTIVE
        // 之后，队列将永远得不到补写（PLAN-016 审计 P3）。
        flushPendingVotes();

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
        if (blockHash == null || blockHash.length != Block.HASH_SIZE) {
            // PLAN-067：哈希缺失/长度不对时不能退化成空哈希投票——最终性层现在
            // 会按 32 字节严格拒绝，这里提前 fail-closed 并留下可定位的日志。
            log.error("Skip finality vote at epoch={}, height={}: block hash length={} (required {})",
                    epoch, height, blockHash == null ? -1 : blockHash.length, Block.HASH_SIZE);
            return null;
        }
        byte[] checkpointHash = blockHash;

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
            // PLAN-018：写库失败 → 交给 outbox 管理器排队补写。
            // 管理器会尽力把"写库意图"落进持久化 outbox（数据库整体不可用时
            // 只能靠内存队列兜底），并由定时任务/下一个区块事件继续补写。
            outboxManager.enqueueLocal(vote, e);
            if (shouldLogFailure()) {
                log.error("Finality vote persistence failed at epoch={}, height={}; queued for retry "
                                + "(pending={}): {}",
                        epoch, height, pendingVoteCount(), e.getMessage());
            }
            return null;
        }
    }

    /**
     * 失败日志采样（10 秒窗口）。
     *
     * <p>数据库整体不可用时，每个 epoch 都会有一张票写库失败 —— 不采样的话，
     * 30 秒故障能刷出几十条 ERROR，把真正的信号淹没（与 Phase 6C 第一切片对
     * {@code AccountDB}/{@code Propose paused} 的口径一致）。</p>
     */
    private boolean shouldLogFailure() {
        long now = System.currentTimeMillis();
        long start = lastFailureLogAt.get();
        if (start == 0 || now - start >= FAILURE_LOG_WINDOW_MS) {
            lastFailureLogAt.set(now);
            long suppressed = suppressedFailureLogs.getAndSet(0);
            if (suppressed > 0) {
                log.info("Finality vote persistence failure log: {} suppressed in the last {}s",
                        suppressed, FAILURE_LOG_WINDOW_MS / 1000);
            }
            return true;
        }
        suppressedFailureLogs.incrementAndGet();
        return false;
    }

    /**
     * 补写待处理投票（PLAN-018：队列与重试逻辑已收敛到 {@link FinalityOutboxManager}）。
     *
     * <p>旧的"队首阻塞"语义（队首失败即 return，后面的票被无限期饿死）已去除：
     * 管理器每轮遍历全部待写票，单张失败不影响其它票。</p>
     */
    private void flushPendingVotes() {
        try {
            outboxManager.flush();
        } catch (RuntimeException e) {
            // 补写失败不影响出块与投票主流程。
            log.warn("Finality pending vote flush failed: {}", e.getMessage());
        }
    }

    /** 待补写投票数（测试与观测用）。 */
    synchronized int pendingVoteCount() {
        return outboxManager.pendingCount();
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
     * 给定高度的最终性视图快照（PLAN-044；只读内存，不查库）。
     *
     * <p><b>为什么不能只看"当前高度的 epoch"</b>：链高在绝大多数时刻**不是** epoch
     * 边界（每次采样只有 1/epochLength 的概率落在检查点上），而投票发生在节点看到
     * 检查点高度的那一刻——PLAN-044 首轮实测因此连续 360 次采样全是
     * {@code no-progress}（遥测不可用）。修正为"<b>最近一个有本地视图记录的
     * epoch</b>"（向下回溯 {@value #FINALITY_SNAPSHOT_LOOKBACK_EPOCHS} 个 epoch）。</p>
     *
     * <p><b>口径（重要）</b>：{@code progress} 是 {@link FinalityGadget} 的
     * <b>进程内聚合视图</b>，不是"该 epoch 的永久结论"——它随对端票到达而增长：
     * 刚投完自己那一票时通常是 50%（2 验证人拓扑），收到对端票后翻 100%。
     * PLAN-043 的 10 分钟窗口实测证明这属于**提交时快照的顺序浮动**，不是
     * 状态分歧；本端点把它变成可观测值，运维/脚本可自行抽样确认收敛。</p>
     *
     * @param height 当前内存链头高度（调用方传 {@code chainHeadProvider.currentHeader().nHeight}）
     * @return 字段：height / epochLength / currentEpoch（该高度的 epoch）/ checkpoint
     *         （该高度是否恰为检查点）/ finalizedCheckpoints（内存已最终化检查点数）/
     *         latestVotedEpoch（**粘滞水位**：最近一次有本地视图记录的 epoch，只在
     *         有记录时前进——见 {@link #finalityWatermarkEpoch}；进程内从未有记录
     *         才为 null）/ finalityLag（currentEpoch − 水位：最终性落后几个 epoch，
     *         死锁时无界增长）/ progress（水位对应的 finalized/progressPercent/
     *         votedWeight/totalWeight/checkpointHash；从未有记录才为 null）
     */
    public java.util.Map<String, Object> finalitySnapshot(long height) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("height", height);
        data.put("epochLength", epochLength);
        data.put("checkpoint", isCheckpoint(height));
        long currentEpoch = epochOf(height);
        data.put("currentEpoch", currentEpoch);
        data.put("finalizedCheckpoints", gadget.finalizedCheckpointCount());

        FinalityRecord found = null;
        long foundEpoch = -1L;
        for (long epoch = currentEpoch; epoch >= Math.max(1L, currentEpoch - FINALITY_SNAPSHOT_LOOKBACK_EPOCHS); epoch--) {
            FinalityRecord record = gadget.getEpochProgress(epoch);
            if (record != null) {
                found = record;
                foundEpoch = epoch;
                break;
            }
        }
        if (found != null) {
            // 粘滞水位：只前进不后退（PLAN-047 实测修正，见 finalityWatermark 注释）
            if (foundEpoch >= finalityWatermarkEpoch) {
                finalityWatermarkEpoch = foundEpoch;
                java.util.Map<String, Object> progress = new java.util.LinkedHashMap<>();
                progress.put("finalized", found.isFinalized());
                progress.put("progressPercent", found.progressPercent());
                progress.put("votedWeight", found.getVotedWeight());
                progress.put("totalWeight", found.getTotalWeight());
                progress.put("checkpointHash",
                        org.apache.commons.codec.binary.Hex.encodeHexString(found.getCheckpointHash()));
                finalityWatermarkProgress = progress;
            }
        }
        if (finalityWatermarkEpoch < 0) {
            // 进程以来从未见过任何最终性记录（非 pos 模式/刚启动）
            data.put("latestVotedEpoch", null);
            data.put("finalityLag", null);
            data.put("progress", null);
            return data;
        }
        data.put("latestVotedEpoch", finalityWatermarkEpoch);
        data.put("finalityLag", currentEpoch - finalityWatermarkEpoch);
        data.put("progress", finalityWatermarkProgress);
        return data;
    }

    /**
     * 最终性**粘滞水位**：进程内"最近一次有本地视图记录的 epoch"及其 progress。
     *
     * <p>PLAN-047 的实测修正：初版快照在 8-epoch 回溯窗内找不到记录时返回
     * {@code latestVotedEpoch=null / lag=null}，导致两个后果——</p>
     * <ul>
     *   <li>健康 3 验证人拓扑下 lag 基线恒为 2（轮转+视图切换使投票稳定落后
     *       链头 2 个 epoch），{@code lag>=2} 的告警阈值持续误报；</li>
     *   <li><b>最危险的一点</b>：最终性真死锁时窗口内同样找不到记录 ⇒ lag 变
     *       NaN ⇒ 告警熄火——最该响的时候哑了。</li>
     * </ul>
     * <p>修正为粘滞水位：记录在就前进、不在就保持，于是死锁时
     * {@code finalityLag = currentEpoch − 水位} 无界增长、{@code progress}
     * 冻结在最后已知值（如 25%）——两个特征都可告警。</p>
     *
     * <p>线程安全：仅被 Prometheus 抓取线程/状态端点单调推进，volatile 足够；
     * 竞态下最坏多算一次快照，值域仍单调不回退。</p>
     */
    private volatile long finalityWatermarkEpoch = -1L;

    /** 水位对应的 progress 子表（与 {@link #finalityWatermarkEpoch} 同源更新）。 */
    private volatile java.util.Map<String, Object> finalityWatermarkProgress;

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
