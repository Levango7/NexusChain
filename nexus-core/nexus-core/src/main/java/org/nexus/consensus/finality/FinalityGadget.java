package org.nexus.consensus.finality;

import org.apache.commons.codec.binary.Hex;
import org.nexus.consensus.pos.SlashingService;
import org.nexus.consensus.pos.StakingService;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.consensus.finality.SignatureAggregator.AggregatedSignature;
import org.nexus.consensus.finality.persistence.FinalityStateStore;
import org.nexus.consensus.finality.persistence.InMemoryFinalityStateStore;
import org.nexus.consensus.finality.persistence.JdbcFinalityStateStore;
import org.nexus.core.Block;
import org.nexus.crypto.ed25519.Ed25519PublicKey;
import org.nexus.consensus.finality.FinalityRejectionCounter.FinalizeReason;
import org.nexus.consensus.finality.FinalityRejectionCounter.Phase;
import org.nexus.consensus.finality.FinalityRejectionCounter.Reason;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NexFinality 最终性层核心（ADR-030 M2：BFT 投票子协议，权重判定；M3 聚合签名前置）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>收集验证者对 epoch 检查点的 {@link Vote}</li>
 *   <li>按 {@link StakingService#getStake} 累积投票权重</li>
 *   <li>当 votedWeight ≥ 2/3 × totalWeight 时标记 finalized</li>
 *   <li>检测双签产生 {@link EquivocationEvidence}</li>
 * </ul>
 *
 * <p>签名验证分两层：投票进入计票/落库之前先做**单票 Ed25519 验签 + 公钥绑定**
 * （PLAN-065 修复：只比对公钥不验签，会让伪造票占住验证人位、把合法票去重掉，
 * 最终化永远失败）；最终化时再做一次聚合验签，作为纵深防御保留。</p>
 */
@Component
public class FinalityGadget {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FinalityGadget.class);

    /** 单轮补最终化扫描最多处理的检查点数（避免 CPU 尖峰）。 */
    static final int SWEEP_BATCH = 50;

    /** 构造期拒绝事件缓冲上限（PLAN-066 重放队列的防泄漏护栏）。 */
    private static final int MAX_REPLAY_EVENTS = 4_096;

    /**
     * 收敛路径拒绝计数的去重集合上限（PLAN-067）。
     *
     * <p>收敛每 5 秒重扫一次库；同一条坏行不能每轮都往 counter 里加 1，否则
     * "库里有一条历史脏行"会被放大成持续增长的攻击曲线。集合按
     * (key, validator, reason) 去重，且有界——超出后不再增长，只停止为**新**
     * 坏行计数（判定/日志不受影响）。</p>
     */
    private static final int MAX_CONVERGENCE_REJECTION_KEYS = 4_096;

    /**
     * 单个验证人在单个 epoch 内最多可**新引入**的检查点键数（PLAN-067）。
     *
     * <p>诚实验证人每个 epoch 只签一个检查点；双签证据最多需要保留 2 个不同键。
     * 取 4 留 2 倍余量：既能保留双签证据，又让"一个拜占庭验证人用不同哈希无限
     * 造键"的放大系数变成常数。上限只作用于<b>新键</b>——对已存在键投票永不拦截，
     * 否则攻击者先造 4 个假键就能把其他验证人挡在真检查点之外。</p>
     */
    static final int MAX_CHECKPOINTS_PER_VALIDATOR_PER_EPOCH = 4;

    private final ValidatorRegistry validatorRegistry;
    private final StakingService stakingService;

    private final Map<String, Set<String>> epochCheckpointVoters = new ConcurrentHashMap<>();
    /**
     * epoch → 该 epoch 下已创建的检查点键（PLAN-067）。
     *
     * <p>用途有两个：</p>
     * <ul>
     *   <li>双签检测只扫本 epoch 的键，而不是全表所有键（原实现每张新票
     *       O(全部键)，拜占庭验证人可用"同 epoch 无限造键"把它放大成 O(N²)）；</li>
     *   <li>按 (epoch, validator) 统计"新引入了几个键"，支撑
     *       {@link #MAX_CHECKPOINTS_PER_VALIDATOR_PER_EPOCH}。</li>
     * </ul>
     *
     * <p>只索引**至少有一张通过绑定校验的票**的键；收敛路径不会为脏行建空键。
     * 与 {@code epochCheckpointVoters} 同生命周期，{@link #setStateStore} 时清空重建。</p>
     */
    private final Map<Long, Set<String>> epochCheckpointKeys = new ConcurrentHashMap<>();
    /**
     * 收敛路径已计过数的拒绝（key|validator|reason，PLAN-067）。
     *
     * <p>只为"库里已有的坏行"去重；admission 路径的每一条网络消息仍然逐条计数。</p>
     */
    private final Set<String> convergenceRejectionsCounted = ConcurrentHashMap.newKeySet();
    private final Map<String, List<Vote>> epochCheckpointVotes = new ConcurrentHashMap<>(); // M3: 投票对象存档，供聚合验签（并发安全：CopyOnWriteArrayList）
    private final Map<String, BigDecimal> epochCheckpointWeights = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> epochTotalWeight = new ConcurrentHashMap<>();
    private final Set<String> finalizedCheckpoints = ConcurrentHashMap.newKeySet();
    /**
     * 聚合验签失败的检查点。重复投票不重复触发聚合验签，避免 P2P 重复消息
     * 放大 CPU 开销；一旦有新的投票者加入，说明投票集合已变化，才会重新尝试。
     */
    private final Set<String> finalizationRejected = ConcurrentHashMap.newKeySet();
    private final List<EquivocationEvidence> detectedEquivocations = new java.util.concurrent.CopyOnWriteArrayList<>();  // P0-3: 并发安全
    /**
     * 收敛起点（PLAN-021）：下一次从哪个 epoch 开始拉"新事实"。
     *
     * <p>启动恢复时置为"已恢复的最大 epoch"（含），避免第一次收敛就全表扫描；
     * 之后随收敛推进。</p>
     */
    private volatile long convergeFromEpoch = 0L;

    /** 已执行罚没的作恶者（地址），防止同一证据重复 slash 造成过度惩罚。 */
    private final Set<String> slashedOffenders = ConcurrentHashMap.newKeySet();

    /** 可选：罚没联动（ADR-030 M4 连接轴）。未注入时仅记录证据，不执行罚没。 */
    private SlashingService slashingService;

    /**
     * 签名聚合器（ADR-030 M3 架构层）。
     * 默认使用 {@link CollectingAggregator}（收集式，诚实降级）；
     * blst 就位后注入 BlstSignatureAggregator 实现真实 O(1) 聚合验签。
     */
    private SignatureAggregator signatureAggregator = new SignatureAggregator.CollectingAggregator();

    /**
     * 最终性状态持久化（ADR-030 P0-1：节点重启不丢失已最终化检查点）。
     * 默认内存版（测试/单机）；配置 {@code nexus.finality.store=jdbc} 时
     * Spring 注入 {@link JdbcFinalityStateStore}（PostgreSQL）。
     *
     * <p>{@code volatile}：该字段由 {@link #setStateStore} 在锁内写入，
     * 但 {@link #getStateStore} 可能在锁外读取；用 volatile 保证可见性，
     * 避免 SpotBugs 的 IS2_INCONSISTENT_SYNC / UG_SYNC_SET_UNSYNC_GET 告警
     * （CI 的 SAST 门禁会把任何 finding 判红）。</p>
     */
    private volatile FinalityStateStore stateStore;

    /**
     * 可选注入拒绝计数端口（PLAN-066）。
     *
     * <p>{@code volatile}：本字段由 {@link #setRejectionCounter} 在锁内写入、
     * 由计票/恢复路径在锁外读取——与 {@link #stateStore} 同样的同步口径，
     * 避免 SpotBugs 的 IS2_INCONSISTENT_SYNC / UG_SYNC_SET_UNSYNC_GET
     * （CI 的 SAST 门禁会把任何 finding 判红）。</p>
     */
    private volatile FinalityRejectionCounter rejectionCounter;

    /**
     * 构造期累积的票级拒绝事件，待 {@link #setRejectionCounter} 注入后重放
     * （PLAN-066）。
     *
     * <p>为什么需要它：{@link #restoreFromStore()} 跑在构造器里，而端口是
     * setter 注入 ⇒ 启动恢复丢弃不可信行的计数若直接丢掉，等于
     * "重启后才发现库被污染"这个最关键的信号在指标上恒为 0。这里先缓冲再补记，
     * 不让"观测"自己变成一个静默失败。</p>
     *
     * <p>上限 {@link #MAX_REPLAY_EVENTS}：缓冲只在"从未注入端口"时堆积，正常情况下
     * 注入后立刻清空；留硬上限是为了异常情况下不成为内存泄漏面。</p>
     */
    private final java.util.List<RejectEvent> pendingRejections =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 验证者集指纹（P0-② 治理关联）：活跃验证人地址+质押的规范化指纹。
     * 治理（ValidatorRegistry.register/unregister 或 stake 变更）后指纹变化，
     * 触发 {@link #epochTotalWeight} 快照失效——新验证人权重计入后续判定。
     */
    private volatile int validatorSetFingerprint = Integer.MIN_VALUE;

    /**
     * Spring/测试便捷构造器：默认使用内存最终性存储。
     *
     * <p>生产环境由 Spring 在构造后通过
     * {@link #setStateStore(FinalityStateStore)} 注入 JDBC 实现（PLAN-015 Phase 6A）。</p>
     */
    @Autowired
    public FinalityGadget(ValidatorRegistry registry, StakingService stakingService) {
        this(registry, stakingService, new InMemoryFinalityStateStore());
    }

    public FinalityGadget(ValidatorRegistry registry, StakingService stakingService,
                          FinalityStateStore stateStore) {
        this.validatorRegistry = registry;
        this.stakingService = stakingService;
        this.stateStore = stateStore;
        restoreFromStore();
    }

    /**
     * 可选注入持久化存储（PLAN-015 Phase 6A）。
     *
     * <p>使用 setter 注入而不是多构造器，避免 Spring 在多个重载构造器之间
     * 选择失败时静默回退内存实现；没有 {@link FinalityStateStore} bean 时
     * 保持构造器创建的 {@link InMemoryFinalityStateStore}。</p>
     */
    @Autowired(required = false)
    public synchronized void setStateStore(FinalityStateStore stateStore) {
        if (stateStore == null || stateStore == this.stateStore) {
            return;
        }
        this.stateStore = stateStore;
        // 注入发生在 bean 初始化阶段，正常路径下缓存为空；清空后按持久化状态重建，
        // 保证即使未来改为延迟注入也不会把内存态与持久化态混在一起。
        finalizedCheckpoints.clear();
        epochCheckpointVoters.clear();
        epochCheckpointKeys.clear();
        epochCheckpointVotes.clear();
        epochCheckpointWeights.clear();
        finalizationRejected.clear();
        convergenceRejectionsCounted.clear();
        restoreFromStore();
        log.info("FinalityGadget: using finality state store {}", stateStore.getClass().getSimpleName());
    }

    /**
     * 可选注入拒绝计数端口（PLAN-066）。
     *
     * <p>用 setter 而不是构造器：</p>
     * <ul>
     *   <li>本类的 3 参构造器被大量测试替身使用，加参数会一次打断所有用例；</li>
     *   <li>指标是<strong>观测</strong>而非<strong>正确性</strong>依赖，缺了它
     *       共识行为不变（退化为只打日志），没有理由让它变成必需依赖。</li>
     * </ul>
     *
     * <p>注入完成时把构造期（{@link #restoreFromStore()} 阶段）缓冲的拒绝事件
     * 补记进计数器，再清空缓冲——只补记一次。</p>
     */
    @Autowired(required = false)
    public synchronized void setRejectionCounter(FinalityRejectionCounter rejectionCounter) {
        this.rejectionCounter = rejectionCounter;
        if (rejectionCounter == null || pendingRejections.isEmpty()) {
            return;
        }
        for (RejectEvent event : pendingRejections) {
            rejectionCounter.countVoteRejected(event.reason, event.phase);
        }
        pendingRejections.clear();
    }

    /**
     * 记一次票级拒绝（PLAN-066）。
     *
     * <p>无端口时缓冲（供 {@link #setRejectionCounter} 重放）；判定本身仍
     * fail-closed、日志仍打，只是没有指标。</p>
     */
    private void countVoteRejected(Reason reason, Phase phase) {
        FinalityRejectionCounter counter = rejectionCounter;
        if (counter == null) {
            bufferPendingRejection(reason, phase);
            return;
        }
        counter.countVoteRejected(reason, phase);
    }

    /**
     * 记一次检查点级拒绝（PLAN-066）。
     *
     * <p>检查点级拒绝发生在最终化路径，那时端口早已注入 ⇒ 不需要重放缓冲；
     * 缺失即 no-op。</p>
     */
    private void countFinalizeRejected(FinalizeReason reason) {
        FinalityRejectionCounter counter = rejectionCounter;
        if (counter != null) {
            counter.countFinalizeRejected(reason);
        }
    }

    /** 端口未注入前的缓冲（仅用于启动恢复阶段的补记）。 */
    private void bufferPendingRejection(Reason reason, Phase phase) {
        if (pendingRejections.size() < MAX_REPLAY_EVENTS) {
            pendingRejections.add(new RejectEvent(reason, phase));
        }
    }

    /** 构造期拒绝事件（reason + phase 对，仅重放用）。 */
    private record RejectEvent(Reason reason, Phase phase) {
    }

    /** 启动恢复：从持久化层重建已最终化检查点、投票者缓存与投票权重。 */
    private void restoreFromStore() {
        stateStore.loadAllFinalized().keySet().forEach(finalizedCheckpoints::add);
        // 已终局的检查点：历史事实，保留全部投票者（含迁移前无签名行）仅用于展示。
        stateStore.loadAllVotes().forEach((key, voters) -> {
            if (!finalizedCheckpoints.contains(key)) {
                return;
            }
            // 恢复投票者集合，并按当前质押重建投票权重（权重为衍生值，不持久化）
            Set<String> restored = ConcurrentHashMap.newKeySet();
            restored.addAll(voters);
            epochCheckpointVoters.put(key, restored);
            // PLAN-067：只把 32 字节哈希的键放进新索引。历史库里的短哈希键是
            // 迁移前遗留事实（非目标：本切片不洗库），但它们不应占用新键配额，
            // 也不应参与双签扫描。
            if (hasValidCheckpointKey(key)) {
                indexCheckpointKey(epochOfKey(key), key);
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (String addr : voters) {
                Validator v = validatorRegistry == null ? null : validatorRegistry.getValidator(addr);
                if (v != null && v.getStatus() == ValidatorStatus.ACTIVE) {
                    sum = sum.add(stakingService == null
                            ? BigDecimal.ZERO : stakingService.getStake(addr));
                }
            }
            epochCheckpointWeights.put(key, sum);
        });
        // 未终局的检查点：只信任"签名有效"的票（PLAN-065）。
        // 修复前伪造票可以落库；启动恢复若无条件信任历史行，脏行会占住验证人位，
        // 真正的合法票随后被当作重复票去重，检查点永久无法最终化。
        stateStore.loadAllVoteObjects().forEach((key, votes) -> {
            if (finalizedCheckpoints.contains(key) || votes == null || votes.isEmpty()) {
                return;
            }
            Set<String> restored = ConcurrentHashMap.newKeySet();
            List<Vote> validVotes = new ArrayList<>();
            BigDecimal sum = BigDecimal.ZERO;
            for (Vote vote : votes) {
                if (vote == null) {
                    log.warn("Startup restore: dropping null vote row");
                    countVoteRejected(Reason.MALFORMED_VOTE, Phase.RESTORE);
                    continue;
                }
                if (!hasValidCheckpointHash(vote.getCheckpointHash())) {
                    log.warn("Startup restore: dropping vote with invalid checkpoint hash: "
                                    + "epoch={}, validator={}, bytes={}",
                            vote.getEpoch(), vote.getValidatorAddress(),
                            vote.getCheckpointHash() == null ? -1 : vote.getCheckpointHash().length);
                    countVoteRejected(Reason.CHECKPOINT_HASH_INVALID, Phase.RESTORE);
                    continue;
                }
                String validatorAddress = vote.getValidatorAddress();
                if (validatorAddress == null || validatorAddress.isEmpty()) {
                    log.warn("Startup restore: dropping malformed vote row (missing validator address)");
                    countVoteRejected(Reason.MALFORMED_VOTE, Phase.RESTORE);
                    continue;   // 防御：畸形行不得让启动恢复抛 NPE
                }
                if (!hasValidVoteSignature(vote)) {
                    log.warn("Startup restore: dropping vote with invalid signature: "
                                    + "epoch={}, validator={}",
                            vote.getEpoch(), validatorAddress);
                    countVoteRejected(Reason.SIGNATURE_INVALID, Phase.RESTORE);
                    continue;
                }
                if (!restored.add(validatorAddress)) {
                    continue;   // 同一验证人只计一次（补票可能重复投递）
                }
                validVotes.add(vote);
                Validator v = validatorRegistry == null
                        ? null : validatorRegistry.getValidator(validatorAddress);
                if (v != null && v.getStatus() == ValidatorStatus.ACTIVE) {
                    sum = sum.add(stakingService == null
                            ? BigDecimal.ZERO : stakingService.getStake(validatorAddress));
                }
            }
            if (!restored.isEmpty()) {
                epochCheckpointVoters.put(key, restored);
                if (hasValidCheckpointKey(key)) {
                    indexCheckpointKey(epochOfKey(key), key);
                }
                epochCheckpointWeights.put(key, sum);
                epochCheckpointVotes.put(key,
                        new java.util.concurrent.CopyOnWriteArrayList<>(validVotes));
            }
        });
        // PLAN-018：恢复后立即尝试补最终化 —— 之前"重启即失去最终化能力"的根因是
        // 完整 Vote 没落库；现在签名恢复了，重启后应当能自己把已达阈值的检查点补上。
        // 构造期聚合器可能尚未注入，失败不阻断启动（定时补写会再试）。
        sweepFinalization("startup-restore");
        if (!finalizedCheckpoints.isEmpty() || !epochCheckpointVoters.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(FinalityGadget.class)
                    .info("FinalityGadget: restored {} finalized checkpoints, {} vote records, {} signed vote objects",
                            finalizedCheckpoints.size(), epochCheckpointVoters.size(),
                            epochCheckpointVotes.size());
        }
        // PLAN-021：收敛起点 = 已恢复的最大 epoch（含），避免第一次收敛就全表扫描。
        long maxEpoch = 0L;
        for (String key : epochCheckpointVoters.keySet()) {
            maxEpoch = Math.max(maxEpoch, epochOfKey(key));
        }
        for (String key : finalizedCheckpoints) {
            maxEpoch = Math.max(maxEpoch, epochOfKey(key));
        }
        convergeFromEpoch = maxEpoch;
    }

    /**
     * 暴露当前持久化存储（PLAN-018：outbox 管理器需要与本类**同一个**存储实例，
     * 否则内存版与 JDBC 版各写各的，队列语义对不上）。
     *
     * <p>方法本身加 {@code synchronized}：写入方 {@link #setStateStore} 是同步方法，
     * 读取方保持一致即可消除 SpotBugs 的 unsync-get 告警（CI 的 SAST 门禁对
     * 任何 finding 判红）。读取成本极低（一次字段读），不构成热点。</p>
     */
    public synchronized FinalityStateStore getStateStore() {
        return stateStore;
    }

    /**
     * 注入签名聚合器（M3 架构挂接点）。
     * 默认收集式；生产环境应在 blst 就位后替换为真实聚合实现。
     */
    public synchronized void setSignatureAggregator(SignatureAggregator aggregator) {
        this.signatureAggregator = Objects.requireNonNull(aggregator, "aggregator must not be null");
    }

    /**
     * 注入罚没服务（M4 连接轴）。注入后，检测到双签证据将自动执行
     * {@link SlashingService#slash(String, SlashingService.Offense)}（DOUBLE_SIGN，没收全部质押并置 SLASHED）。
     */
    public synchronized void setSlashingService(SlashingService slashingService) {
        this.slashingService = slashingService;
    }

    /**
     * 单票签名验证（只依赖票自身携带的公钥，不查注册表）。
     *
     * <p>启动恢复时验证人集可能尚未装载，不能在这里做注册表绑定；网络/收敛入口
     * 先做绑定校验，再调用本方法。</p>
     */
    private boolean hasValidVoteSignature(Vote vote) {
        if (vote == null) {
            return false;
        }
        byte[] signature = vote.getSignature();
        byte[] checkpoint = vote.getCheckpointHash();
        byte[] publicKey = vote.getPublicKeyBytes();
        if (signature == null || signature.length == 0
                || !hasValidCheckpointHash(checkpoint)
                || publicKey == null || publicKey.length == 0) {
            return false;
        }
        try {
            return new Ed25519PublicKey(publicKey).verify(vote.signingPayload(), signature);
        } catch (RuntimeException e) {
            // fail-closed：公钥/签名格式异常一律视为验签失败。
            return false;
        }
    }

    /**
     * 投票准入校验：身份/公钥绑定 + **单票签名验签**（PLAN-065）。
     *
     * <p>为什么不能只比对公钥：公钥是公开信息。攻击者无需私钥即可伪造
     * {@code Vote(epoch, cp, 合法验证人, 垃圾签名, 该验证人公钥)}——绑定校验会通过，
     * 伪造票随后落库、计权重、进入 voters；真正的验证人投出合法票时反而被当作
     * "重复票"幂等拒绝，最终化时聚合验签永远失败，该检查点永久无法最终化
     * （重启后依旧，因为伪造票已经落库）。因此验签必须发生在**计票/落库之前**。</p>
     *
     * <p>口径与 {@link #submitVote} / 跨节点收敛路径共用：任何一条路径放宽，
     * 都等于让"库里的任意行"或"网络上的任意消息"给自己加权重。</p>
     *
     * <p>成本：每票一次 Ed25519 验签（验证人数量级）；最终化时的聚合验签保留为
     * 纵深防御，不因本校验而移除。</p>
     *
     * <p>PLAN-066：每条拒绝分支同时把原因/路径送进 {@link FinalityRejectionCounter}，
     * 否则"被伪造票攻击了多久"只能靠 grep 日志回答。</p>
     *
     * @param phase 拒绝发生在哪条路径（指标标签；判定逻辑与 phase 无关）
     */
    private boolean validateVoteBinding(Vote vote, Phase phase) {
        Reason reason = checkVoteBinding(vote);
        if (reason == null) {
            return true;
        }
        logVoteRejection(vote, reason);
        countVoteRejected(reason, phase);
        return false;
    }

    /**
     * 与路径无关的绑定校验：返回拒绝原因，{@code null} 表示通过。
     *
     * <p>PLAN-067：把"判定"与"计数"拆开，收敛路径可以按
     * (key, validator, reason) 去重计数——同一条库里的坏行每 5 秒被重新扫描
     * 一次，不能每轮都累加指标（否则历史脏行会伪装成持续攻击）。</p>
     */
    private Reason checkVoteBinding(Vote vote) {
        if (vote == null) {
            return Reason.MALFORMED_VOTE;
        }
        String validatorAddress = vote.getValidatorAddress();
        if (validatorAddress == null || validatorAddress.isEmpty()) {
            return Reason.MALFORMED_VOTE;
        }
        // PLAN-067：检查点哈希必须是 32 字节。必须在注册表/验签之前挡掉——
        // 超长哈希会让 signingPayload() 按 8+len 分配，也会让 epochKey() hex 成
        // 2 倍体积的字符串；这是"先校验、后建键"的第一道闸。
        if (!hasValidCheckpointHash(vote.getCheckpointHash())) {
            return Reason.CHECKPOINT_HASH_INVALID;
        }
        Validator v = validatorRegistry.getValidator(validatorAddress);
        if (v == null) {
            return Reason.VALIDATOR_UNKNOWN;
        }
        if (v.getStatus() != ValidatorStatus.ACTIVE) {
            return Reason.VALIDATOR_INACTIVE;
        }
        String registeredPubHex = v.getPublicKey();
        byte[] votePubKey = vote.getPublicKeyBytes();
        if (registeredPubHex == null || registeredPubHex.isEmpty()
                || votePubKey == null || votePubKey.length == 0
                || !registeredPubHex.equalsIgnoreCase(Hex.encodeHexString(votePubKey))) {
            return Reason.PUBKEY_MISMATCH;
        }
        // PLAN-065：公钥匹配不等于签名有效——必须在计票/落库前验签。
        if (!hasValidVoteSignature(vote)) {
            return Reason.SIGNATURE_INVALID;
        }
        return null;
    }

    /** 拒绝日志（PLAN-065/067 的排障口径；调用方已确认 reason 非 null）。 */
    private void logVoteRejection(Vote vote, Reason reason) {
        if (reason == Reason.CHECKPOINT_HASH_INVALID) {
            log.warn("Vote checkpoint hash invalid for validator {}: epoch={}, bytes={}",
                    vote == null ? "unknown" : vote.getValidatorAddress(),
                    vote == null ? -1 : vote.getEpoch(),
                    vote == null || vote.getCheckpointHash() == null
                            ? -1 : vote.getCheckpointHash().length);
        } else if (reason == Reason.PUBKEY_MISMATCH) {
            byte[] votePubKey = vote == null ? null : vote.getPublicKeyBytes();
            log.warn("Vote pubkey binding FAILED for validator {}: votePayloadPubKey={}",
                    vote == null ? "unknown" : vote.getValidatorAddress(),
                    votePubKey == null || votePubKey.length == 0 ? "none" : "mismatch");
        } else if (reason == Reason.SIGNATURE_INVALID) {
            log.warn("Vote signature verification FAILED for validator {} (epoch={})",
                    vote == null ? "unknown" : vote.getValidatorAddress(),
                    vote == null ? -1 : vote.getEpoch());
        }
    }

    /**
     * 收敛路径的拒绝计数：同一 (key, validator, reason) 只计一次（PLAN-067）。
     *
     * <p>集合有界：达到上限后不再为新的坏行计数，避免被大量脏行撑爆内存；
     * 判定与日志不受影响。</p>
     */
    private void countConvergenceRejection(String checkpointKey, Vote vote, Reason reason) {
        String validator = vote == null ? "" : String.valueOf(vote.getValidatorAddress());
        String id = checkpointKey + "|" + validator + "|" + reason;
        if (!convergenceRejectionsCounted.contains(id)
                && convergenceRejectionsCounted.size() >= MAX_CONVERGENCE_REJECTION_KEYS) {
            return;
        }
        if (convergenceRejectionsCounted.add(id)) {
            countVoteRejected(reason, Phase.CONVERGENCE);
        }
    }

    /**
     * 跨节点最终性视图收敛（PLAN-021）。
     *
     * <p><b>要解决的缺口</b>：本类的内存视图（投票者集合 / 投票对象 / 已终局集合）
     * 原本只在**启动恢复**时从库里装载一次 ⇒ 启动之后对端投的票本节点永远看不到。
     * 后果（3 节点验收实测）：掉线节点重启后只看到自己那 1 票（1000/3000），
     * 于是①对外报告的权重进度是错的；②即便对端已把该检查点终局，本节点也
     * 永远不会本地补最终化。</p>
     *
     * <p><b>做法</b>：每 5 秒把"库里的新事实"（对端已落库的票 + 已被标记终局的检查点）
     * 拉进本地内存视图，并重试这些检查点的最终化。</p>
     *
     * <p><b>安全边界</b>：</p>
     * <ul>
     *   <li>合并进来的票**必须**通过与提交路径同一套准入校验
     *       （{@link #validateVoteBinding}：注册表身份 + 公钥绑定 + 单票验签）；</li>
     *   <li>合并是**单调**的：只增不减（与"缓存不设 TTL"的理由一致 —— 最终性事实不可逆）；</li>
     *   <li>去重靠投票者集合（同一验证人不会被计两次权重）；</li>
     *   <li>拉取窗口只覆盖"最近若干个 epoch"，避免每次全表扫描。</li>
     * </ul>
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5_000L)
    public void scheduledFinalityViewConvergence() {
        try {
            convergeFinalityView();
        } catch (RuntimeException e) {
            log.warn("Finality view convergence failed: {}", e.getMessage());
        }
    }

    /** 收敛逻辑（包内可测）。 */
    synchronized void convergeFinalityView() {
        FinalityStateStore store = stateStore;
        if (store == null) {
            return;
        }
        long from = convergeFromEpoch;
        Map<String, List<Vote>> votes = store.loadVoteObjectsSince(from);
        Set<String> finalized = store.loadFinalizedKeysSince(from);
        if (votes.isEmpty() && finalized.isEmpty()) {
            return;
        }
        long maxEpoch = from;
        Set<String> affected = new java.util.HashSet<>();
        for (Map.Entry<String, List<Vote>> entry : votes.entrySet()) {
            String key = entry.getKey();
            long epoch = epochOfKey(key);
            Set<String> voters = epochCheckpointVoters.get(key);
            // PLAN-067：已有有效投票者的键在之前已合并过有效票，可以推进游标。
            // 只有"从未合并过任何有效票"的键（voters == null，典型是历史脏行）
            // 必须靠本轮真正合并成功才推进——否则一条 epoch 极大的脏行会把游标
            // 推到 1000，之后写入的 epoch 1..999 合法票再也不会被看到。
            boolean mergedAny = voters != null;
            for (Vote vote : entry.getValue()) {
                if (vote == null) {
                    countConvergenceRejection(key, null, Reason.MALFORMED_VOTE);
                    continue;
                }
                if (voters != null && voters.contains(vote.getValidatorAddress())) {
                    continue;   // 去重：同一验证人不重复计权
                }
                Reason rejection = checkVoteBinding(vote);
                if (rejection != null) {
                    logVoteRejection(vote, rejection);
                    countConvergenceRejection(key, vote, rejection);
                    continue;   // 与提交路径同一口径的绑定校验（含 32 字节哈希）
                }
                // PLAN-067：只有"新键"才受单验证人单 epoch 上限约束；
                // 对已存在键投票永远放行，否则攻击者可用假键挡住真检查点。
                if (voters == null
                        && validatorEpochKeyLimitReached(epoch, vote.getValidatorAddress())) {
                    log.warn("Finality convergence: dropping vote that would exceed per-validator "
                                    + "checkpoint key limit: epoch={}, validator={}, key={}",
                            epoch, vote.getValidatorAddress(), key);
                    countConvergenceRejection(key, vote, Reason.CHECKPOINT_LIMIT_EXCEEDED);
                    continue;
                }
                if (voters == null) {
                    voters = epochCheckpointVoters.computeIfAbsent(
                            key, k -> ConcurrentHashMap.newKeySet());
                }
                voters.add(vote.getValidatorAddress());
                indexCheckpointKey(epoch, key);
                epochCheckpointVotes.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                        .add(vote);
                BigDecimal weight = stakingService == null ? null : stakingService.getStake(vote.getValidatorAddress());
                if (weight != null) {
                    BigDecimal current = epochCheckpointWeights.getOrDefault(key, BigDecimal.ZERO);
                    epochCheckpointWeights.put(key, current.add(weight));
                }
                finalizationRejected.remove(key);
                affected.add(key);
                mergedAny = true;
            }
            // PLAN-067：游标只由"真正合并了有效票的键"或已终局键推进。原实现从
            // 所有键推进，一条 epoch 极大的脏行会把游标推到 1000，之后写入的
            // epoch 1..999 合法票再也不会被 loadVoteObjectsSince(from) 看到。
            if (mergedAny) {
                maxEpoch = Math.max(maxEpoch, epoch);
            }
        }
        for (String key : finalized) {
            finalizedCheckpoints.add(key);
            maxEpoch = Math.max(maxEpoch, epochOfKey(key));
        }
        // 下次仍从"当前最大 epoch"开始（而不是 +1）：同一 epoch 仍可能有迟到票。
        convergeFromEpoch = maxEpoch;
        if (!affected.isEmpty()) {
            log.info("Finality view converged: merged votes for {} checkpoint(s) (epoch>= {})",
                    affected.size(), from);
        }
        for (String key : affected) {
            long epoch = epochOfKey(key);
            byte[] checkpoint = parseCheckpoint(hexOfKey(key));
            try {
                tryFinalize(epoch, checkpoint, key, totalWeightForEpoch(epoch));
            } catch (RuntimeException e) {
                // 写库失败不影响其它检查点：下一轮再试。
                log.warn("Finality convergence finalize attempt failed for {}: {}", key, e.getMessage());
            }
        }
    }

    /** 从 "epoch|hex" 里取 epoch。 */
    private static long epochOfKey(String key) {
        int idx = key.indexOf('|');
        if (idx <= 0) {
            return 0L;
        }
        try {
            return Long.parseLong(key.substring(0, idx));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 从 "epoch|hex" 里取 hex。 */
    private static String hexOfKey(String key) {
        int idx = key.indexOf('|');
        return idx < 0 ? "" : key.substring(idx + 1);
    }

    /**
     * 提交检查点投票。
     *
     * <p>本方法必须串行化：本地投票与 P2P 对端投票可能并发到达，权重累积、
     * 投票列表追加、阈值判断和聚合验签必须是一个原子过程。否则聚合器可能
     * 在“计算总长度”和“复制签名”之间看到不同的列表快照，导致数组越界或
     * 聚合签名与实际投票集合不一致。</p>
     *
     * @param vote BFT 投票记录
     * @return 该检查点的当前最终化记录
     */
    public synchronized FinalityRecord submitVote(Vote vote) {
        if (vote == null) {
            // 防御：P2P 解码正常不会给出 null，但公开方法不能因 null 直接 NPE。
            log.warn("Finality vote ignored: null vote");
            countVoteRejected(Reason.MALFORMED_VOTE, Phase.ADMISSION);
            return new FinalityRecord(0L, new byte[0], BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        long epoch = vote.getEpoch();

        // 治理变更检测：验证者集（活跃地址+质押）变化 → 失效总权重快照，
        // 使新增/移除验证人的权重在下一票起生效（P0-② 治理关联）
        int fp = computeValidatorSetFingerprint();
        if (fp != validatorSetFingerprint) {
            validatorSetFingerprint = fp;
            epochTotalWeight.clear();
            org.slf4j.LoggerFactory.getLogger(FinalityGadget.class)
                    .info("FinalityGadget: validator set changed (fingerprint {}); weight snapshots invalidated", fp);
        }

        // 总权重按 epoch 快照（快照失效时自动按最新验证者集重算）。
        // 注意：0 权重不能写入快照，否则启动早期验证人尚未装载时的一次投票
        // 会让该 epoch 永远以 0 总权重计算，后续合法投票全部被误拒。
        BigDecimal total = totalWeightForEpoch(epoch);

        if (total.signum() <= 0) {
            // 启动早期验证人/质押尚未装载时，不能把“总权重 0”误判为 100% 最终化。
            log.warn("Finality vote ignored at epoch={}: total validator weight is zero", epoch);
            countVoteRejected(Reason.ZERO_TOTAL_WEIGHT, Phase.ADMISSION);
            return record(epoch, vote.getCheckpointHash(), total);
        }

        // P0-1 审计修复：先做身份/公钥绑定校验，再记录投票。
        // PLAN-065：绑定校验同时包含**单票签名验签**——只比对公钥的话，伪造票会
        // 占住验证人位、把合法票去重掉，最终化永远失败。
        // 不能先把未验证的票写入 voters/DB——否则重启恢复时会把无效投票
        // 的质押权重重新计入，破坏最终性安全边界。
        // PLAN-067：校验（含 32 字节哈希）通过后才建键，避免超长哈希在
        // epochKey() 里被 hex 成 2 倍体积的字符串。
        if (!validateVoteBinding(vote, Phase.ADMISSION)) {
            return record(epoch, vote.getCheckpointHash(), total);
        }

        String checkpointKey = epochKey(epoch, vote.getCheckpointHash());
        Set<String> voters = epochCheckpointVoters.get(checkpointKey);
        if (voters != null && voters.contains(vote.getValidatorAddress())) {
            // 同验证者重复投票同检查点：幂等拒绝。若上一次最终化已通过聚合验签、
            // 但 markFinalized 写库失败，重复投票是天然的重试机会；已确认的检查点
            // 或聚合验签已拒绝的检查点不会重复触发昂贵的聚合验签。
            tryFinalize(epoch, vote.getCheckpointHash(), checkpointKey, total);
            return record(epoch, vote.getCheckpointHash(), total);
        }

        // PLAN-067：上限只约束“该验证人新引入的键”。对已存在键投票永远放行，
        // 否则攻击者先造满 4 个假键就能把其他验证人挡在真检查点之外。
        if (voters == null && validatorEpochKeyLimitReached(epoch, vote.getValidatorAddress())) {
            log.warn("Finality vote rejected: per-validator checkpoint key limit reached: "
                            + "epoch={}, validator={}, key={}",
                    epoch, vote.getValidatorAddress(), checkpointKey);
            countVoteRejected(Reason.CHECKPOINT_LIMIT_EXCEEDED, Phase.ADMISSION);
            return record(epoch, vote.getCheckpointHash(), total);
        }

        // 持久化必须在任何内存状态变更之前完成：
        // - 写库失败时 voters/weights/投票对象都保持原状，重试不会被误判为重复票；
        // - 不会出现“内存已计票、重启后却从数据库消失”的不可逆结算丢失窗口。
        // PLAN-018：连同 Ed25519 签名与公钥一起落库 —— 只存地址的话，重启后
        // 恢复不出 Vote 对象，聚合验签无票可验，检查点将永远无法在重启后补最终化。
        // PLAN-067：`computeIfAbsent` 也移到写库之后；原实现先建空 voter set，
        // 写库因 VARCHAR(128) 溢出抛异常时会留下不可回收的空键。
        stateStore.recordVote(epoch, vote.getCheckpointHash(), vote.getValidatorAddress(),
                vote.getSignature(), vote.getPublicKeyBytes());

        voters = epochCheckpointVoters.computeIfAbsent(checkpointKey, k -> ConcurrentHashMap.newKeySet());
        if (!voters.add(vote.getValidatorAddress())) {
            // 防御性分支：正常路径由 synchronized + 上面的 contains 排除；
            // 若未来恢复路径引入并发，这里仍保证不重复计权重。
            tryFinalize(epoch, vote.getCheckpointHash(), checkpointKey, total);
            return record(epoch, vote.getCheckpointHash(), total);
        }
        indexCheckpointKey(epoch, checkpointKey);

        // 新投票进入集合，之前聚合验签失败的结论可能已变化，允许重新尝试。
        finalizationRejected.remove(checkpointKey);

        // 双签检测：同 epoch 不同检查点（PLAN-067：按 epoch 索引扫描，避免全表键遍历）
        Set<String> sameEpochKeys = epochCheckpointKeys.get(epoch);
        if (sameEpochKeys != null) {
            for (String otherKey : sameEpochKeys) {
                if (otherKey.equals(checkpointKey)) {
                    continue;
                }
                Set<String> otherVoters = epochCheckpointVoters.get(otherKey);
                if (otherVoters == null || !otherVoters.contains(vote.getValidatorAddress())) {
                    continue;
                }
                EquivocationEvidence ev = new EquivocationEvidence(
                        vote, new Vote(epoch, parseCheckpoint(hexOfKey(otherKey)),
                        vote.getValidatorAddress(), new byte[0]));
                detectedEquivocations.add(ev);
                // M4 连接轴：注入罚没服务时，双签证据即时触发 slash（幂等，防止重复罚没）
                if (slashingService != null && slashedOffenders.add(vote.getValidatorAddress())) {
                    slashingService.slash(vote.getValidatorAddress(), SlashingService.Offense.DOUBLE_SIGN);
                }
            }
        }

        // 正常路径保持“投票时权重快照”语义；只有恢复缓存为 0（重启早于
        // StakingService 自举）时，才按当前 voters 重算并自愈。
        BigDecimal weight = stakingService.getStake(vote.getValidatorAddress());
        if (weight == null) {
            weight = BigDecimal.ZERO;
        }
        BigDecimal currentWeight = epochCheckpointWeights.getOrDefault(checkpointKey, BigDecimal.ZERO);
        if (currentWeight.signum() == 0) {
            currentWeight = currentVotedWeight(checkpointKey);
        } else {
            currentWeight = currentWeight.add(weight);
        }
        epochCheckpointWeights.put(checkpointKey, currentWeight);
        epochCheckpointVotes.computeIfAbsent(checkpointKey, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(vote);

        BigDecimal voted = epochCheckpointWeights.get(checkpointKey);
        log.debug("Finality vote counted: epoch={}, validator={}, weight={}, voted={}, total={}, checkpoint={}",
                epoch, vote.getValidatorAddress(), weight, voted, total,
                org.apache.commons.codec.binary.Hex.encodeHexString(vote.getCheckpointHash()));
        if (voted.multiply(BigDecimal.valueOf(3))
                .compareTo(total.multiply(BigDecimal.valueOf(2))) >= 0) {
            tryFinalize(epoch, vote.getCheckpointHash(), checkpointKey, total);
        }
        return record(epoch, vote.getCheckpointHash(), total);
    }

    /**
     * 尝试把已达阈值的检查点标记为最终化。
     *
     * <p>顺序固定为“聚合验签 → 写持久化 → 更新内存”：只有数据库确认最终化后，
     * 内存缓存才对外报告 FINALIZED。写库失败时向上抛出，保持 fail-closed；
     * 同一检查点的重复投票或后续新票会再次触发本方法。</p>
     *
     * @return 是否已最终化（false 表示未达阈值、投票签名缺失或聚合验签失败）
     */
    private boolean tryFinalize(long epoch, byte[] checkpointHash, String checkpointKey, BigDecimal total) {
        if (finalizedCheckpoints.contains(checkpointKey)) {
            return true;
        }
        if (finalizationRejected.contains(checkpointKey)) {
            return false;
        }
        BigDecimal voted = currentVotedWeight(checkpointKey);
        if (voted.multiply(BigDecimal.valueOf(3))
                .compareTo(total.multiply(BigDecimal.valueOf(2))) < 0) {
            return false;
        }

        List<Vote> votes = epochCheckpointVotes.get(checkpointKey);
        if (votes == null || votes.isEmpty()) {
            // 没有可验签的 Vote 对象（例如存储实现不支持签名恢复）。
            // 此时不能仅凭数据库中的权重直接宣布最终化；等待新的投票对象
            // 到达后重新走聚合验签路径。
            log.debug("Finality threshold reached for {} but no vote signatures are loaded; deferring finalization",
                    checkpointKey);
            return false;
        }

        // PLAN-018 的验签覆盖度观测。
        // PLAN-065 起，未终局检查点的 voters / weights / votes 都只由**签名有效**的票
        // 重建，正常情况下 votes.size() == voters.size()；网络提交与收敛路径也都在
        // 计票前验签。这里保留覆盖度日志用于观测旧存储实现（只返回地址行）的残余口径，
        // 不据此拒绝最终化——已终局检查点不会走到本方法。
        Set<String> voters = epochCheckpointVoters.get(checkpointKey);
        if (voters != null && votes.size() < voters.size()) {
            log.debug("Finality aggregate covers {}/{} voters for {}; remaining votes were restored "
                            + "from an address-only store and cannot be re-verified",
                    votes.size(), voters.size(), checkpointKey);
        }

        // M3 架构挂接：最终化前对投票集合做聚合验签（收集式降级或 blst 聚合）
        AggregatedSignature agg = signatureAggregator.aggregate(votes);
        if (agg != null && !signatureAggregator.verifyAggregate(votes, agg)) {
            // fail-closed：聚合验签失败则不最终化，重复票不重复触发验签。
            finalizationRejected.add(checkpointKey);
            log.warn("Finality aggregate verification failed for {}; checkpoint remains unfinalized", checkpointKey);
            countFinalizeRejected(FinalizeReason.AGGREGATE_VERIFICATION_FAILED);
            return false;
        }

        try {
            stateStore.markFinalized(epoch, checkpointHash);
        } catch (RuntimeException e) {
            // 写库失败时不能先把 finalizedCheckpoints 置位，否则内存报告 FINALIZED
            // 而数据库没有最终化事实，重启后会丢失不可逆结算。保留重试机会并抛出。
            log.error("Failed to persist finalization for {}; finality remains uncommitted", checkpointKey, e);
            throw e;
        }
        finalizedCheckpoints.add(checkpointKey);
        finalizationRejected.remove(checkpointKey);
        return true;
    }

    /**
     * 补最终化扫描（PLAN-018）。
     *
     * <p>对"权重已达 2/3 阈值但尚未标记最终化"的检查点重新走一次
     * {@link #tryFinalize}：验证签名覆盖度 → 聚合验签 → 写库标记。</p>
     *
     * <p>触发时机：① 启动恢复后（此时聚合器可能还没注入，失败不阻断启动）；
     * ② 定时任务（{@code @Scheduled}，聚合器已就绪）。</p>
     *
     * <p>边界：单轮最多处理 {@link #SWEEP_BATCH} 个检查点，避免历史积压时
     * 一轮做太多聚合验签（CPU 尖峰）。</p>
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10_000L)
    public void scheduledFinalizationSweep() {
        try {
            sweepFinalization("scheduled");
        } catch (RuntimeException e) {
            log.warn("Finality finalization sweep failed: {}", e.getMessage());
        }
    }

    private void sweepFinalization(String trigger) {
        int processed = 0;
        for (Map.Entry<String, BigDecimal> entry : epochCheckpointWeights.entrySet()) {
            if (processed >= SWEEP_BATCH) {
                break;
            }
            String key = entry.getKey();
            if (finalizedCheckpoints.contains(key) || finalizationRejected.contains(key)) {
                continue;
            }
            if (epochCheckpointVotes.get(key) == null || epochCheckpointVotes.get(key).isEmpty()) {
                continue;   // 没有可验签的 Vote 对象，补不了
            }
            String[] parts = key.split("\\|", 2);
            if (parts.length != 2) {
                continue;
            }
            long epoch;
            try {
                epoch = Long.parseLong(parts[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            BigDecimal total = totalWeightForEpoch(epoch);
            BigDecimal voted = currentVotedWeight(key);
            if (total.signum() <= 0
                    || voted.multiply(BigDecimal.valueOf(3)).compareTo(total.multiply(BigDecimal.valueOf(2))) < 0) {
                continue;   // 未达阈值
            }
            try {
                if (tryFinalize(epoch, parseCheckpoint(parts[1]), key, total)) {
                    processed++;
                    log.info("Finality checkpoint finalized by {} sweep: epoch={}, checkpoint={}",
                            trigger, epoch, parts[1]);
                }
            } catch (RuntimeException e) {
                // 写库失败不阻断其它检查点：下一轮再试。
                log.warn("Finality sweep failed for {} ({}): {}", key, trigger, e.getMessage());
            }
        }
    }

    /**
     * 查询检查点当前最终化状态。
     */
    public FinalityRecord getFinality(long epoch, byte[] checkpointHash) {
        return record(epoch, checkpointHash, totalWeightForEpoch(epoch));
    }

    /**
     * 检查点是否已最终化。
     */
    public boolean isFinalized(long epoch, byte[] checkpointHash) {
        // PLAN-067：只读查询也不能让超长哈希在 epochKey() 里分配 2 倍体积字符串。
        if (!hasValidCheckpointHash(checkpointHash)) {
            return false;
        }
        return finalizedCheckpoints.contains(epochKey(epoch, checkpointHash));
    }

    /**
     * 已最终化的检查点数量（进程内累计，观测用；PLAN-044 的
     * {@code GET /consensus/finality} 用它表达"本节点最终性视图已确认的历史规模"）。
     *
     * <p>只读 {@link #finalizedCheckpoints} 集合大小，无副作用；与持久化
     * {@code finality_checkpoints} 表的区别：这里是**内存视图**（重启后从库恢复，
     * 冷恢复节点需经补票重建）。</p>
     */
    public int finalizedCheckpointCount() {
        return finalizedCheckpoints.size();
    }

    /**
     * 已检测到的双签证据（供 slashing 联动消费）。
     */
    public List<EquivocationEvidence> getDetectedEquivocations() {
        return Collections.unmodifiableList(detectedEquivocations);
    }

    /**
     * 查询某 epoch 的最终性进度（供 RPC 对外暴露权重进度）。
     *
     * <p>返回该 epoch 内已收票检查点中投票权重最大的记录——
     * 若该 epoch 已最终化（任一检查点达 2/3），则 {@link FinalityRecord#isFinalized()} 为 true。</p>
     *
     * @return 该 epoch 的权重进度；无任何投票时返回 null
     */
    public FinalityRecord getEpochProgress(long epoch) {
        // 快照被指纹失效清空时（治理变更），回退按当前验证者集重算总权重，而非返回 0
        BigDecimal total = totalWeightForEpoch(epoch);
        FinalityRecord best = null;
        for (Map.Entry<String, BigDecimal> e : epochCheckpointWeights.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            if (parts.length != 2 || !parts[0].equals(String.valueOf(epoch))) {
                continue;
            }
            byte[] checkpoint = parseCheckpoint(parts[1]);
            FinalityRecord rec = new FinalityRecord(epoch, checkpoint, currentVotedWeight(e.getKey()), total,
                    finalizedCheckpoints.contains(e.getKey()));
            if (best == null || rec.getVotedWeight().compareTo(best.getVotedWeight()) > 0) {
                best = rec;
            }
        }
        return best;
    }

    private FinalityRecord record(long epoch, byte[] checkpointHash, BigDecimal total) {
        // PLAN-067：畸形/超长哈希不得进入 epochKey()（会 hex 成 2 倍体积的字符串）。
        // 查询路径与拒绝路径都走这里，所以护栏放在唯一出口上。
        if (!hasValidCheckpointHash(checkpointHash)) {
            return new FinalityRecord(epoch, checkpointHash, BigDecimal.ZERO, total, false);
        }
        String key = epochKey(epoch, checkpointHash);
        return new FinalityRecord(epoch, checkpointHash, currentVotedWeight(key), total,
                finalizedCheckpoints.contains(key));
    }

    /**
     * 按当前验证人质押重算投票权重。
     *
     * <p>持久化层只保存投票者地址（权重是衍生值）。节点重启时
     * {@link FinalityGadget} 可能在 StakingService 完成自举之前构造，启动恢复
     * 缓存的权重会是 0；查询/下一票时用本方法自愈，避免 RPC 显示
     * {@code FINALIZED} 但 {@code voted_weight=0} 的矛盾状态。</p>
     */
    private BigDecimal currentVotedWeight(String checkpointKey) {
        BigDecimal cached = epochCheckpointWeights.get(checkpointKey);
        if (cached != null && cached.signum() > 0) {
            return cached;
        }
        Set<String> voters = epochCheckpointVoters.get(checkpointKey);
        if (voters == null || voters.isEmpty()) {
            return cached == null ? BigDecimal.ZERO : cached;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (String address : voters) {
            Validator validator = validatorRegistry == null ? null : validatorRegistry.getValidator(address);
            if (validator == null || validator.getStatus() != ValidatorStatus.ACTIVE) {
                continue;
            }
            BigDecimal stake = stakingService == null ? null : stakingService.getStake(address);
            if (stake != null) {
                sum = sum.add(stake);
            }
        }
        if (sum.signum() > 0) {
            epochCheckpointWeights.put(checkpointKey, sum);
        }
        return sum;
    }

    /**
     * 取 epoch 总权重快照；0 权重不留在快照里，避免启动早期的一次误判
     * 把后续合法投票永久锁死。
     */
    private BigDecimal totalWeightForEpoch(long epoch) {
        String key = String.valueOf(epoch);
        BigDecimal total = epochTotalWeight.compute(key, (k, v) ->
                (v == null || v.signum() <= 0) ? computeTotalWeight() : v);
        if (total != null && total.signum() <= 0) {
            epochTotalWeight.remove(key, total);
        }
        return total == null ? BigDecimal.ZERO : total;
    }

    private BigDecimal computeTotalWeight() {
        BigDecimal total = BigDecimal.ZERO;
        for (Validator v : validatorRegistry.getActiveValidators()) {
            if (v.getStatus() == ValidatorStatus.ACTIVE) {
                total = total.add(stakingService.getStake(v.getAddress()));
            }
        }
        return total;
    }

    /**
     * 计算验证者集规范化指纹（地址+质押），用于检测治理变更。
     * 地址排序后逐个累加，保证顺序无关的稳定指纹。
     */
    private int computeValidatorSetFingerprint() {
        List<String> addrs = new ArrayList<>();
        for (Validator v : validatorRegistry.getActiveValidators()) {
            if (v.getStatus() == ValidatorStatus.ACTIVE) {
                addrs.add(v.getAddress());
            }
        }
        Collections.sort(addrs);
        int fp = 17;
        for (String a : addrs) {
            fp = 31 * fp + a.hashCode();
            BigDecimal s = stakingService.getStake(a);
            fp = 31 * fp + (s == null ? 0 : s.hashCode());
        }
        return fp;
    }

    /** 检查点哈希必须是协议规定的 32 字节（{@link Block#HASH_SIZE}）。 */
    private static boolean hasValidCheckpointHash(byte[] checkpointHash) {
        return checkpointHash != null && checkpointHash.length == Block.HASH_SIZE;
    }

    /** 检查点键里的 hex 部分是否对应 32 字节（用于恢复历史行时过滤短哈希键）。 */
    private static boolean hasValidCheckpointKey(String key) {
        return key != null && hexOfKey(key).length() == Block.HASH_SIZE * 2;
    }

    /** 记录 epoch → 检查点键（幂等；只对通过绑定校验的键调用）。 */
    private void indexCheckpointKey(long epoch, String key) {
        epochCheckpointKeys.computeIfAbsent(epoch, k -> ConcurrentHashMap.newKeySet()).add(key);
    }

    /**
     * 单个验证人在该 epoch 是否已经**新引入**了上限数量的检查点键。
     *
     * <p>只统计"该验证人投过票的键"；对已存在键投票不会被本方法拦截（调用方
     * 只在键尚不存在时调用）。这样上限约束的是"造键"，不是"投票"。</p>
     */
    private boolean validatorEpochKeyLimitReached(long epoch, String validatorAddress) {
        Set<String> keys = epochCheckpointKeys.get(epoch);
        if (keys == null) {
            return false;
        }
        int count = 0;
        for (String key : keys) {
            Set<String> voters = epochCheckpointVoters.get(key);
            if (voters != null && voters.contains(validatorAddress)) {
                if (++count >= MAX_CHECKPOINTS_PER_VALIDATOR_PER_EPOCH) {
                    return true;
                }
            }
        }
        return false;
    }

    private String epochKey(long epoch, byte[] checkpointHash) {
        // 与 FinalityStateStore 的 key 约定一致：checkpoint 使用十六进制，
        // 而不是 Arrays.toString（后者长度可达 160+，曾撑爆 VARCHAR(128)）。
        return epoch + "|" + Hex.encodeHexString(checkpointHash == null ? new byte[0] : checkpointHash);
    }

    private byte[] parseCheckpoint(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}
