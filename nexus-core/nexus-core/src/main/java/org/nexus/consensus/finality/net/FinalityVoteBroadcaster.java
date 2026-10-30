package org.nexus.consensus.finality.net;

import com.google.protobuf.ByteString;
import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.consensus.finality.Vote;
import org.nexus.p2p.NexusChainOuterClass;
import org.nexus.p2p.PeerServer;
import org.nexus.sync.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最终性投票广播器（ADR-030 M_net）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>把 {@link Vote} 序列化为可被 P2P 消费的载荷（{@link FinalityVoteCodec}）</li>
 *   <li>节点内分发：事件总线（{@link FinalityVoteBroadcastEvent}）+ {@link VoteListener}</li>
 *   <li>跨节点分发：{@code sendOverP2P()} 经 {@code PeerServer.broadcast} 推送</li>
 *   <li>接收端（{@code onVoteReceived(byte[])}）反序列化并提交 {@link FinalityGadget}</li>
 * </ul>
 *
 * <p><b>更正（2026-10-07 复核）</b>：本类原注释写"当前实现使用进程内事件总线作为中继，
 * 真正接入 P2P gossip 需等待 proto 工具链（protoc 3.22.2）就位，见
 * {@code docs/adr/ADR-031-finality-p2p-integration.md}"——两处都已过期：
 * <ol>
 *   <li>P2P 投递<b>两侧都已接线</b>：发送侧 {@code broadcast() → sendOverP2P()}
 *       把投票封装成 {@code Transactions(TransactionType.VOTE)} 复用交易通道
 *       （见本类 {@code sendOverP2P()}）；接收侧 {@code SyncManager.onTransactions()}
 *       先分流验证人集合载荷（{@code ValidatorSetCodec.isValidatorSetPayload}），
 *       再按 {@code TransactionType.VOTE} +
 *       {@link FinalityVoteP2PCodec#isVotePayload(byte[])} 判定后回调
 *       {@code onVoteReceived(byte[])}。"等 protoc 就位"这个前提本身也不成立：
 *       生成物 {@code NexusChainOuterClass.java} 已直接入库
 *       （{@code src/main/java/org/nexus/p2p/}），构建侧只有 {@code protobuf-java} 运行时依赖
 *       （{@code nexus-core/nexus-core/build.gradle:204}），**没有 protobuf 代码生成插件**，
 *       protoc 从不在构建路径上。</li>
 *   <li>被引用的那份 ADR 文件名不存在；实际记录在同一编号下：
 *       {@code docs/adr/ADR-031-nexfinality-engineering-decisions.md}
 *       （其中"复用 TRANSACTIONS 通道、靠魔数区分投票"即本类的语义代价）。</li>
 * </ol>
 *
 * <p><b>仍然属实的那半句，别读错</b>：{@code peerServer} 为空时 {@code sendOverP2P()} 静默跳过，
 * 只剩进程内事件总线——而两个测试（{@code FinalityVoteBroadcasterTest}、
 * {@code FinalityEndToEndIntegrationTest}）用的都是不带 {@code PeerServer} 的两参构造器，
 * 全仓也没有任何测试触达 {@code onTransactions}/{@code sendOverP2P}。
 * 也就是说：<b>跨节点真实投递目前无测试覆盖</b>，"已接线"不等于"已验证"。</p>
 */
@Component
@ConditionalOnProperty(name = "nexus.consensus.mode", havingValue = "pos")
public class FinalityVoteBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(FinalityVoteBroadcaster.class);

    private final FinalityGadget gadget;
    private final ApplicationEventPublisher eventPublisher;
    private final PeerServer peerServer;  // P2P 发送侧（可选；单进程/测试为 null）
    private final List<VoteListener> externalListeners = new CopyOnWriteArrayList<>();
    /** 接收侧落库失败的补写队列（PLAN-018；可为 null —— 单进程/测试不装配时退化为"丢弃 + 告警"）。 */
    private final org.nexus.consensus.finality.FinalityOutboxManager outboxManager;

    // === PLAN-025：独立存储拓扑下的补票（anti-entropy）===
    // 这些字段用 @Value 注入但保留字面量默认值 —— 测试用手工构造器时不经过 Spring，
    // 字段停在默认值上，行为可预期。
    @org.springframework.beans.factory.annotation.Value("${nexus.finality.anti-entropy.enabled:true}")
    private boolean antiEntropyEnabled = true;
    /** 每次补票请求回看多少个 epoch（覆盖"短暂掉线"窗口；更大的缺口靠 from=0 冷启动补齐）。 */
    @org.springframework.beans.factory.annotation.Value("${nexus.finality.anti-entropy.margin-epochs:50}")
    private long antiEntropyMarginEpochs = 50L;
    /** 单次请求/响应携带的票数上限（防止一次请求拉回全部历史）。 */
    @org.springframework.beans.factory.annotation.Value("${nexus.finality.anti-entropy.max-votes:200}")
    private int antiEntropyMaxVotes = FinalityVoteSyncCodec.DEFAULT_MAX_VOTES;

    /**
     * PLAN-057：补票游标分页开关（缺省 false = 维持 PLAN-025 的
     * "margin 缝隙修补"行为；开启后大缺口走多轮爬升）。
     */
    @org.springframework.beans.factory.annotation.Value("${nexus.finality.anti-entropy.paging-enabled:false}")
    private boolean antiEntropyPagingEnabled = false;

    /**
     * PLAN-057：分页终止前的宽限期（毫秒）。
     *
     * <p>补票响应走 P2P 异步投递，发出请求后同一线程的下一个 tick 就来判"对端
     * 是否无货"会读到还没到货的数据 ⇒ 假终止。默认 5000ms 与测试床
     * {@code interval-ms=5000} 对齐（生产缺省 interval 30s，宽限期远小于它）。</p>
     */
    @org.springframework.beans.factory.annotation.Value("${nexus.finality.anti-entropy.paging-grace-ms:5000}")
    private long antiEntropyPagingGraceMs = 5000L;
    /** 分页终止后的冷却时长：避免在"已无货"的位置每 interval 空转一次。 */
    private volatile long antiEntropyPagingCooldownMs = 60_000L;
    /** 冷却截止时刻（{@link System#nanoTime()} 口径）；0 表示不在冷却。 */
    private volatile long pagingCooldownUntilNanos = 0L;
    /** 本轮是否已收到对端的 0x5C 响应标记（收到即以此为准推进游标）。 */
    private volatile boolean pagingResponseSeen = false;
    /** 响应标记报告"该起点之后无货"（count=0）⇒ 立即终止，不等宽限期。 */
    private volatile boolean pagingPeerExhausted = false;
    /**
     * 对端**曾经**发过 0x5C 标记（粘性，跨轮保持）。
     *
     * <p>为什么需要它：{@code pagingResponseSeen} 只表示"本轮标记到了"。
     * 若只用它做闸门，则下一轮发请求后、标记还没回来之前，
     * {@code !pagingResponseSeen} 为真 ⇒ 老启发式重新生效 ⇒ 链头广播票
     * 仍可能把游标拖走。只要见过一次标记，就说明对端支持精确范围，
     * 此后不应再退回启发式。</p>
     */
    private volatile boolean pagingMarkerCapable = false;
    /**
     * 连续"宽限期已过且本轮未见标记"的 tick 数（PLAN-057 第 10 处）。
     *
     * <p>第 10 处（第七轮实测发现）：进程刚重启、`pagingMarkerCapable` 尚为
     * false，而第一轮的响应页（及标记）在 block-sync 高峰里丢失时，
     * 老启发式在 graceElapsed 一成立就按 {@code lastResponded+1} 推进游标
     * ⇒ 游标 0→184，68..135 中段从此不再被请求（实测漏 123 票）。</p>
     *
     * <p>修法：老启发式必须攒够 {@value #MARKERLESS_GRACE_TICKS_NEEDED} 个
     * "无标记且宽限已过"的 tick 才可用——给 P2P 重传留出时间窗口；
     * 响应页可能丢，多轮重传总归会到。对真正的老对端（永远不发标记），
     * 仅是把推进延后约 3 个宽限期，行为不变。</p>
     */
    /**
     * 连续"宽限期已过且本轮未见标记"的 tick 数（PLAN-057 第 10 处）。
     *
     * <p>非原子递增会让 SpotBugs 报 VO_VOLATILE_INCREMENT ⇒ 用 AtomicInteger，
     * 与 {@code votesSinceRound} 同型。</p>
     */
    private final java.util.concurrent.atomic.AtomicInteger markerlessGraceTicks =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** 老启发式启用前必须见到的"无标记宽限 tick"数。 */
    static final int MARKERLESS_GRACE_TICKS_NEEDED = 3;

    /**
     * 下一轮分页请求的起点（含）；-1 = 未启用/已完成（回退 margin 模式）。
     *
     * <p>单调不回退：本地投票仍在继续时游标不回退。</p>
     */
    private volatile long nextSyncCursorEpoch = -1L;

    /** 本轮已响应的最大 epoch（由 {@link #onVoteReceived} 记录，-1 = 无）。 */
    private volatile long lastRespondedEpoch = -1L;

    /**
     * 本轮分页请求发出后收到的票数（含补回来的与正常广播来的）。
     *
     * <p>终止判据用"少于上限"而不是"游标越过本地 max"：追赶期本地 max
     * <b>天然落后于对端</b>，用 maxEpoch 比游标会过早判完成。补票响应的
     * 条数是 min(可服务数, 上限) ⇒ 收到数 < 上限 即可推断对端已无货。</p>
     */
    private final AtomicInteger votesSinceRound = new AtomicInteger();

    /** 本进程是否已发出过分页请求（区分"首轮"与"续轮"）。 */
    private volatile boolean pagingRoundIssued = false;

    /**
     * 本轮向对端请求的起点（含）。终止判据用它而不是"收到票数"——见下方注释。
     *
     * <p>PLAN-057 测试床修正：原判据是"本轮收到的票数 < max-votes"，在<b>在线集群</b>
     * 里几乎永远成立——正常广播是涓流（实测约 3 票/5s），到不了 200 ⇒ 分页在第一轮
     * 后就自认为完成。单测绿是因为测试只投递同步响应、没有广播涓流（PLAN-055
     * 同型盲区）。改为<b>判据对齐"游标处有没有新数据"</b>：请求起点为 C，
     * 宽限期后仍未见过 epoch >= C 的票 ⇒ 对端在 C 处已无货 ⇒ 终止。</p>
     */
    private volatile long roundAskedFrom = -1L;

    /**
     * 本轮请求发出的时刻（nanoTime；0 = 未发过）。宽限期存在的理由：补票响应走
     * P2P 是<b>异步</b>投递，同一线程下一 tick 就来判终止会读到"还没到货"。
     */
    private volatile long roundIssuedAtNanos = 0L;

    public FinalityVoteBroadcaster(FinalityGadget gadget, ApplicationEventPublisher eventPublisher) {
        this(gadget, eventPublisher, null, null);
    }

    /** 兼容构造器（单进程/测试：有 P2P 发送侧，但无 outbox 管理器）。 */
    public FinalityVoteBroadcaster(FinalityGadget gadget,
                                   ApplicationEventPublisher eventPublisher,
                                   PeerServer peerServer) {
        this(gadget, eventPublisher, peerServer, null);
    }

    @Autowired
    public FinalityVoteBroadcaster(FinalityGadget gadget,
                                   ApplicationEventPublisher eventPublisher,
                                   @Autowired(required = false) PeerServer peerServer,
                                   @Autowired(required = false)
                                   org.nexus.consensus.finality.FinalityOutboxManager outboxManager) {
        this.gadget = Objects.requireNonNull(gadget, "gadget must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.peerServer = peerServer;
        this.outboxManager = outboxManager;
    }

    /**
     * 广播投票（本节点产生后向 P2P 网络推送）。
     */
    public void broadcast(Vote vote) {
        byte[] payload = FinalityVoteCodec.encode(vote);
        log.debug("Broadcasting finality vote: epoch={}, validator={}, payloadBytes={}",
                vote.getEpoch(), vote.getValidatorAddress(), payload.length);
        // 事件发布（节点内订阅者可收到）
        eventPublisher.publishEvent(new FinalityVoteBroadcastEvent(vote, payload));
        // P2P 发送侧：构造 TRANSACTIONS 消息（VOTE 交易 + 载荷）广播给对端
        sendOverP2P(vote, payload);
        // 外部监听者
        for (VoteListener l : externalListeners) {
            try {
                l.onOutgoingVote(vote, payload);
            } catch (RuntimeException e) {
                log.warn("Vote listener failed during broadcast: {}", e.getMessage());
            }
        }
    }

    /**
     * P2P 发送侧（跨节点汇聚关键件）：把投票封装为 {@code TransactionType.VOTE}
     * 交易 + payload 载荷，经 {@link PeerServer#broadcast} 广播给对端节点。
     */
    private void sendOverP2P(Vote vote, byte[] payload) {
        if (peerServer == null) {
            return;  // 单进程/测试：无 P2P，静默跳过
        }
        try {
            NexusChainOuterClass.Transaction tx = NexusChainOuterClass.Transaction.newBuilder()
                    .setTransactionType(NexusChainOuterClass.TransactionType.VOTE)
                    .setPayload(ByteString.copyFrom(payload))
                    .build();
            NexusChainOuterClass.Transactions msg = NexusChainOuterClass.Transactions.newBuilder()
                    .addTransactions(tx)
                    .build();
            peerServer.broadcast(msg);
            log.info("Finality vote broadcast over P2P: epoch={}, validator={}",
                    vote.getEpoch(), vote.getValidatorAddress());
        } catch (RuntimeException e) {
            log.warn("P2P vote broadcast failed: {}", e.getMessage());
        }
    }

    /**
     * 处理来自网络的投票（接收端）。
     * 反序列化后投递至 {@link FinalityGadget#submitVote(Vote)}。
     *
     * <p>PLAN-025：同一入口也承载<b>补票请求</b>（{@link FinalityVoteSyncCodec}，魔数 0x5B）——
     * 按载荷魔数分流，投票格式不变（向后兼容：老节点收到 0x5B 只会当作非投票载荷丢弃）。</p>
     */
    public void onVoteReceived(byte[] payload) {
        if (FinalityVoteSyncCodec.isSyncPayload(payload)) {
            handleVoteSyncRequest(payload);
            return;
        }
        // PLAN-057 第 8 处：响应标记（0x5C）不是投票。必须在这里也挡一道——
        // 有的调用方（测试夹具、老路由）不区分魔数，直接把它投给 onVoteReceived，
        // 而 FinalityVoteCodec 不校验魔数，会把 JSON 体解成一张"垃圾票"落库。
        if (FinalityVoteSyncCodec.isResponsePayload(payload)) {
            onVoteSyncResponse(payload);
            return;
        }
        Vote vote = FinalityVoteCodec.decode(payload);
        if (vote == null) {
            log.warn("Received malformed finality vote payload (dropped)");
            return;
        }
        // PLAN-057：记录本节点"本轮已见到的最大 epoch"。
        // 分页爬升的游标推进由此驱动：cursor = lastRespondedEpoch + 1。
        // 单调不回退：正常投票仍在继续，本地已见更大的 epoch 时游标不动。
        noteEpochForCursor(vote.getEpoch());
        votesSinceRound.incrementAndGet();
        log.debug("Received finality vote: epoch={}, validator={}, checkpoint={}",
                vote.getEpoch(), vote.getValidatorAddress(),
                org.apache.commons.codec.binary.Hex.encodeHexString(vote.getCheckpointHash()));
        try {
            gadget.submitVote(vote);
        } catch (RuntimeException e) {
            // PLAN-018：接收侧写库失败**不再把票丢掉**。
            //
            // 旧行为（PLAN-016 遗留缺口）：catch 之后只打一条 WARN，票直接蒸发；
            // 而注释里"发送方会重新广播"并不成立 —— 发送方只在**它自己**写库失败时
            // 才会重发，对端库故障时没有任何一方会重投，远端票静默消失。
            //
            // 现在：接收方自己把**完整 Vote（含签名）**投进 outbox，由本地定时补写；
            // 数据库整体不可用时先落内存队列，恢复后补写。
            if (outboxManager != null) {
                outboxManager.enqueueReceived(vote, e);
                log.warn("Received finality vote could not be persisted; queued for local retry: "
                                + "epoch={}, validator={}: {}",
                        vote.getEpoch(), vote.getValidatorAddress(), e.getMessage());
            } else {
                log.warn("Received finality vote could not be persisted and no outbox manager is "
                                + "configured; vote dropped: epoch={}, validator={}: {}",
                        vote.getEpoch(), vote.getValidatorAddress(), e.getMessage());
            }
        }
    }

    // ===================== PLAN-025：补票（anti-entropy） =====================

    /**
     * 广播一条补票请求：请对端把 {@code epoch >= fromEpoch} 的**已签名**投票重发一遍。
     *
     * <p>为什么用"请求最近窗口"而不是"每轮全量 gossip"：全量 gossip 的流量随
     * 节点数×票数平方增长，且会让每个节点重复处理大量已知票；补票请求只在对端
     * 有缺口时才有实际数据传输，代价可预期。</p>
     */
    public void requestVoteSync(long fromEpoch, int maxVotes) {
        requestVoteSync(fromEpoch, maxVotes, false);
    }

    /**
     * 带游标声明的补票请求（PLAN-057）。
     *
     * @param cursor true 表示请求方在做分页爬升；载荷含 {@code "cursor":true}
     *                （老节点忽略未知键，行为不变）
     */
    public void requestVoteSync(long fromEpoch, int maxVotes, boolean cursor) {
        if (peerServer == null) {
            return;   // 单进程/测试：无 P2P
        }
        byte[] payload = FinalityVoteSyncCodec.encode(fromEpoch, maxVotes, cursor);
        sendRawVotePayloadOverP2P(payload);
        log.debug("Vote-sync request broadcast: fromEpoch={}, maxVotes={}, cursor={}",
                fromEpoch, maxVotes, cursor);
    }

    /**
     * 记录"可供游标推进"的最大 epoch（PLAN-057 第 6 处修正）。
     *
     * <p>原实现用<b>所有</b>入站票的 max epoch 驱动 {@code cursor = lastRespondedEpoch + 1}。
     * 在线集群里正常广播是贴着链头的涓流，于是游标被拉到链头，下一轮
     * {@code from = 链头 + 1} 永远取不到货，中段缺口一次都不会被请求。</p>
     *
     * <p>判据：分页进行中时，只有落在<b>本轮请求窗口</b>
     * {@code [roundAskedFrom, roundAskedFrom + max-votes]}（两端闭合）内的 epoch
     * 才可推进游标。依据是响应端按升序服务：{@code from} 之后的前 {@code max-votes}
     * 张票，最后一张的 epoch 最多是 {@code from + max-votes}；再往后的票不可能是
     * 本轮分页响应，只能是贴着链头的广播涓流。未开启分页时行为不变。</p>
     */
    private void noteEpochForCursor(long epoch) {
        if (!pagingRoundIssued || roundAskedFrom < 0) {
            lastRespondedEpoch = Math.max(lastRespondedEpoch, epoch);
            return;
        }
        // 窗口上界 = from + maxVotes，再 +1 是 PLAN-057 第 11 处的哨兵余量：
        // 响应页按 epoch 完整交付，可能多带"哨兵 epoch"的票，其 epoch 恰好
        // 可能超过 from + maxVotes 一个。若这里不放宽，那些票不会计入
        // lastRespondedEpoch，随后响应标记里同值的 servedTo 会被一致性护栏
        // 误判为"标记超出实收范围"而丢弃 ⇒ 游标停住、同一 from 反复重问。
        long windowEnd = roundAskedFrom + Math.max(1, antiEntropyMaxVotes) + 1;
        if (epoch >= roundAskedFrom && epoch <= windowEnd) {
            lastRespondedEpoch = Math.max(lastRespondedEpoch, epoch);
        }
    }

    /**
     * 周期性补票（默认 30 秒）。首次执行发生在容器启动后（{@code fixedDelay} 无 initialDelay）。
     *
     * <p>取本地最大 epoch 回看 {@code margin-epochs} 作为起点：</p>
     * <ul>
     *   <li>本地为空 ⇒ 从 0 起请求，对端把**最新** N 条票回推（冷启动追赶）；</li>
     *   <li>本地已有票 ⇒ 只回看一个小窗口，用于修补"广播时恰好掉线/写库失败"的缝隙。</li>
     * </ul>
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${nexus.finality.anti-entropy.interval-ms:30000}")
    public void scheduledVoteAntiEntropy() {
        if (!antiEntropyEnabled || peerServer == null) {
            return;
        }
        try {
            long maxEpoch = localMaxVoteEpoch();
            if (antiEntropyPagingEnabled) {
                if (pagingCooldownUntilNanos > 0
                        && System.nanoTime() < pagingCooldownUntilNanos) {
                    // 分页刚判完成：冷却期内只做 margin 修补，不在"已无货"处空转。
                    long cooldownFrom = maxEpoch < 0
                            ? 0L : Math.max(0L, maxEpoch - antiEntropyMarginEpochs);
                    requestVoteSync(cooldownFrom, antiEntropyMaxVotes);
                    return;
                }
                pagingCooldownUntilNanos = 0L;
                // PLAN-057：游标分页爬升。
                //
                // 终止判据（测试床修正后）：本轮请求起点为 C，宽限期后仍没见过
                // epoch >= C 的票 ⇒ 对端在 C 处已无货 ⇒ 终止并回 margin 缝隙修补。
                //
                // 为什么<b>不</b>用"收到票数 < 上限"：在线集群里正常广播是涓流
                // （实测 ~3 票/5s），该条件几乎恒真 ⇒ 分页一轮后就自认为完成——
                // 测试床 583s 追赶里 cursor=true 只出现 1 次即此。
                //
                // 宽限期：响应走 P2P 异步投递，同一线程下一 tick 立即判会读到
                // "还没到货" ⇒ 假终止。
                boolean graceElapsed = roundIssuedAtNanos != 0L
                        && (System.nanoTime() - roundIssuedAtNanos)
                        >= antiEntropyPagingGraceMs * 1_000_000L;
                boolean peerExhaustedAtCursor = roundAskedFrom >= 0
                        && lastRespondedEpoch < roundAskedFrom;
                boolean markerSaysExhausted = pagingRoundIssued && pagingPeerExhausted;
                // PLAN-057 第 10 处：统计"无标记且宽限已过"的连续 tick。
                if (pagingRoundIssued && !pagingResponseSeen && graceElapsed) {
                    markerlessGraceTicks.incrementAndGet();
                }
                // PLAN-057 §5.5 观测点：分页开启时每轮 tick 记一行状态。
                // 动机：第三轮测试床出现"首轮之后整条 anti-entropy 调度静默"
                // （cursor=true 仅 1 次、cursor=false 也是 0 次），而现有日志
                // 只在"首轮开始"和"判完成"时打点，中间静默无法区分
                // "没被调度"与"调度了但没发请求"
                // （原猜"broadcast 阻塞饿死 @Scheduled 单线程调度器"已排除：
                // ScheduleConfig 的池大小是 20）。
                // 缺省 paging-enabled=false，开启后每 interval-ms 一行，量可控。
                log.info("Anti-entropy paging tick: maxEpoch={} cursor={} askedFrom={} "
                                + "lastResponded={} votesSinceRound={} graceElapsed={} "
                                + "peerExhausted={} marker={} markerExhausted={} "
                                + "markerlessGrace={} graceMs={}",
                        maxEpoch, nextSyncCursorEpoch, roundAskedFrom, lastRespondedEpoch,
                        votesSinceRound.get(), graceElapsed, peerExhaustedAtCursor,
                        pagingResponseSeen, markerSaysExhausted,
                        markerlessGraceTicks.get(),
                        antiEntropyPagingGraceMs);
                if (pagingRoundIssued
                        && (markerSaysExhausted
                        || (graceElapsed && peerExhaustedAtCursor
                        // PLAN-057 第 15 处（第 13 轮实测）：legacy 宽限期终止
                        // 必须同样累计夕到 MARKERLESS_GRACE_TICKS_NEEDED 个 tick 才可俥用。
                        // 否则：重启后前几轮若请求/响应丢失（连接尚未建穔），会被误判为
                        // “对端无货”而提前终止，整条爬升退化成 margin——第五轮实测
                        // from=0 两个 tick 无响应即终止，最终只能靠 margin 搩小缺口。
                        && markerlessGraceTicks.get() >= MARKERLESS_GRACE_TICKS_NEEDED))) {
                    log.info("Anti-entropy paging complete: no vote at or after epoch {}"
                            + " after {}ms grace; resuming margin-based gap repair"
                            + " (local max epoch={})",
                            roundAskedFrom, antiEntropyPagingGraceMs, maxEpoch);
                    // 不回到地板：保留续点，否则下一轮又按 localMax-margin 重算地板、
                    // 再问一遍同一个起点（第五轮实测 paging-start=37 / complete=36，
                    // 全在原地打转，一票没多拿）。冷却期后从续点继续。
                    nextSyncCursorEpoch = roundAskedFrom;
                    pagingCooldownUntilNanos = System.nanoTime()
                            + antiEntropyPagingCooldownMs * 1_000_000L;
                    pagingRoundIssued = false;
                    pagingResponseSeen = false;
                    pagingPeerExhausted = false;
                    roundAskedFrom = -1L;
                    roundIssuedAtNanos = 0L;
                } else {
                    // PLAN-057（复测第 5 处修正）：首轮起点不能取进程内的
                    // lastRespondedEpoch——它记录的是"本进程见过哪些票"，
                    // 而宕机节点重启后 lastRespondedEpoch 停在**宕机前最后
                    // 一条本地票**（实测 196），于是首轮 cursor=197，只问到
                    // 边界 1~2 票就判"无货"退出，中段完全没搬。
                    //
                    // 正确起点 = 与 margin 模式同源的地板：
                    //   max(0, localMaxVoteEpoch - margin-epochs)
                    // 该地板 <= 任何真实缺口起点 ⇒ 不会漏中段。
                    long floor = maxEpoch < 0 ? 0L : Math.max(0L, maxEpoch - antiEntropyMarginEpochs);
                    if (!pagingRoundIssued) {
                        if (nextSyncCursorEpoch < 0) {
                            nextSyncCursorEpoch = floor;
                            log.info("Anti-entropy paging: starting cursor crawl from epoch {}"
                                    + " (local max epoch={}, margin={})",
                                    nextSyncCursorEpoch, maxEpoch, antiEntropyMarginEpochs);
                        } else {
                            log.info("Anti-entropy paging: resuming cursor crawl from epoch {}"
                                    + " (resume point kept from previous round)",
                                    nextSyncCursorEpoch);
                        }
                    } else if (!pagingMarkerCapable && graceElapsed
                            && markerlessGraceTicks.get() >= MARKERLESS_GRACE_TICKS_NEEDED
                            // 第 13 处：只有收到页级批量（老节点倒序页）才认为对端不发标记。
                            // 重启后连接未建、本轮一页都没到时，禁止按 lastResponded+1 拉走游标。
                            && votesSinceRound.get() >= Math.max(1, antiEntropyMaxVotes / 2)
                            && lastRespondedEpoch >= 0
                            && lastRespondedEpoch + 1 > nextSyncCursorEpoch) {
                        // 老对端（从未发过 0x5C）：退回第 6 处的窗口过滤 + 宽限期路径。
                        //
                        // 三道闸门都是必需的：
                        //   - pagingMarkerCapable：一旦对端证明会发标记，就不再退回启发式；
                        //   - graceElapsed：得等宽限期，给标记到达留时间；
                        //   - markerlessGraceTicks：首轮的响应页/标记可能在
                        //     block-sync 高峰里丢失 ⇒ 必须再多忍几轮重传（第 10 处）。
                        nextSyncCursorEpoch = lastRespondedEpoch + 1;
                    }
                    long cursor = nextSyncCursorEpoch < 0 ? 0L : nextSyncCursorEpoch;
                    // PLAN-057 第 7 处修正（终止分支不可达）：
                    // 宽限期必须从"上次有进展"起算，不能每轮无条件刷新。
                    //
                    // 原实现无条件 `roundIssuedAtNanos = now()`，而每轮间隔就是
                    // `interval-ms` ⇒ "距上次发请求的时间"恒等于 interval ⇒ 只要
                    // `paging-grace-ms >= interval-ms`，`graceElapsed` 永远为假，
                    // 终止分支成为死代码：分页永不退出、也永不回退 margin 缝隙修补。
                    // 第四轮测试床实证（grace=6000 > interval=5000）：
                    // 128 个 tick 中 `graceElapsed=true` 出现 **0 次**，
                    // 而 `peerExhausted=true` 出现 25 次。
                    //
                    // 现在：只有游标真的前进（提出的是"新问题"）才重置计时；
                    // 同一游标上的重发只是重传，不重置，让等待时间得以累积。
                    boolean cursorAdvanced = !pagingRoundIssued || cursor != roundAskedFrom;
                    pagingResponseSeen = false;
                    pagingPeerExhausted = false;
                    roundAskedFrom = cursor;
                    if (cursorAdvanced) {
                        roundIssuedAtNanos = System.nanoTime();
                    }
                    votesSinceRound.set(0);
                    pagingRoundIssued = true;
                    requestVoteSync(cursor, antiEntropyMaxVotes, true);
                    return;
                }
            }
            long from = maxEpoch < 0 ? 0L : Math.max(0L, maxEpoch - antiEntropyMarginEpochs);
            requestVoteSync(from, antiEntropyMaxVotes);
        } catch (RuntimeException e) {
            log.warn("Scheduled vote anti-entropy failed: {}", e.getMessage());
        }
    }

    /**
     * 处理对端的补票**响应标记**（0x5C，PLAN-057 第 8 处）。
     *
     * <p>这是请求方推进游标的**唯一权威依据**：它携带对端实际服务的范围，
     * 因此不会被链头广播票误导。老节点不发此标记，请求方退回
     * "窗口过滤 + 宽限期"的启发式路径（见 {@code scheduledVoteAntiEntropy}）。</p>
     */
    public void onVoteSyncResponse(byte[] payload) {
        FinalityVoteSyncCodec.SyncResponse resp = FinalityVoteSyncCodec.decodeResponse(payload);
        if (resp == null) {
            return;
        }
        if (!pagingRoundIssued || resp.requestFrom() != roundAskedFrom) {
            // 不是本轮的回答：可能是上一轮的迟到响应，或别的节点发出的请求被广播到全网。
            // 第 12 处诊断：从 debug 提级为 info。
            // 第九轮实测出现"页收到了（votesSinceRound≈404）、0x5C 标记一条都没打"，
            // 需要能区分"标记根本没到"与"到了但被这里忽略"。
            log.info("Vote-sync response marker ignored: requestFrom={} currentRound={}"
                    + " roundIssued={}",
                    resp.requestFrom(), roundAskedFrom, pagingRoundIssued);
            return;
        }
        pagingResponseSeen = true;
        pagingMarkerCapable = true;
        markerlessGraceTicks.set(0);
        if (resp.count() <= 0) {
            pagingPeerExhausted = true;
            log.info("Vote-sync response marker: peer has nothing at or after epoch {}"
                    + " (requestFrom={})", resp.servedTo() + 1, resp.requestFrom());
            return;
        }
        // PLAN-057 第 9 处（第六轮实测发现）：标记必须与本端**实际收到**的响应票一致。
        // requestFrom 只有"起点"一个维度：若对端（冷却期）margin 请求与本端
        // 游标请求使用了同一 from epoch，margin 响应（latest-first，served 头部切片）
        // 的标记会命中本轮 requestFrom ⇒ 游标被拽到链头、中段永久跳过。
        // 修法 §1（响应端）：margin 请求一律不回标记（见 handleVoteSyncRequest）。
        // 修法 §2（请求方，本处）：标记的 servedTo 不得超出本轮**已实收**的最大 epoch
        // （lastRespondedEpoch 已含窗口过滤，只计 [roundAskedFrom, +maxVotes] 内的票）。
        // 每个连接的广播是"先页后标记"的 FIFO，所以诚实响应必有 servedTo <= 实收。
        if (resp.servedTo() > lastRespondedEpoch) {
            log.warn("Vote-sync response marker dropped: claims servedTo={} but only "
                            + "seen up to {} in round from {} (foreign/margin marker or "
                            + "unordered delivery)",
                    resp.servedTo(), lastRespondedEpoch, roundAskedFrom);
            return;
        }
        // PLAN-057 第 11 处（第八轮实测发现，1/1026 票缺口）：游标推进依赖
        // 响应端**按 epoch 完整交付**这一前提。
        // 旧实现 `ORDER BY epoch ASC LIMIT N` 会在某个 epoch 中间截断（实测
        // 68..134 共 201 票，LIMIT 200 ⇒ validator nv3gC5.. 的 epoch 134
        // 被切在页外），而 servedTo 只报"页内最大 epoch" ⇒ 请求方按 +1
        // 前进 ⇒ 那一票永远不再被请求。
        // 修法在响应端（loadVoteObjectsAscending：多取 1 行做哨兵，
        // 边界 epoch 整体不进本页），因此这里的推进恢复为简单的 servedTo+1。
        long next = resp.servedTo() + 1;
        if (next > nextSyncCursorEpoch) {
            nextSyncCursorEpoch = next;
        }
        log.info("Vote-sync response marker: served {}..{} ({} votes) for request from {}"
                + " -> next cursor {}", resp.servedFrom(), resp.servedTo(),
                resp.count(), resp.requestFrom(), nextSyncCursorEpoch);
    }

    /** 处理对端的补票请求：把本地存的、带签名的票按 epoch **倒序**截断后一次性回推。 */
    void handleVoteSyncRequest(byte[] payload) {
        FinalityVoteSyncCodec.SyncRequest req = FinalityVoteSyncCodec.decode(payload);
        if (req == null) {
            log.warn("Received malformed vote-sync request (dropped)");
            return;
        }
        org.nexus.consensus.finality.persistence.FinalityStateStore store = gadget.getStateStore();
        if (store == null) {
            return;
        }
        try {
            java.util.List<Vote> page;
            if (req.cursor()) {
                // PLAN-057：分页协议必须**升序**（老 -> 新）服务，否则请求方
                // 的"from=游标"永远只拿回最新 N 条、永远补不回中段缺口
                // （原实现 ORDER BY epoch DESC LIMIT N 正是 PLAN-048b 里
                // "from 每轮只前进 1 个 epoch"的根因——不是慢，是补不上）。
                // 老节点不认识 cursor 键，仍走降序分支（渐进降级）。
                page = store.loadVoteObjectsAscending(req.fromEpoch(), req.maxVotes());
            } else {
                // 避免 from=0 时把全部历史票读进内存。
                page = store.loadLatestVoteObjects(req.fromEpoch(), req.maxVotes());
            }
            if (page == null || page.isEmpty()) {
                // PLAN-057 第 8 处：空页也要回标记，请求方据此**立即**终止，
                // 不必再等一个宽限期（老节点不发标记，仍由宽限期兜底）。
                // 第 9 处：仅游标请求回标记——margin 响应附带的标记在第六轮
                // 实测中把别处的游标从 0 直接拽到链头（详见方法内注释）。
                if (req.cursor()) {
                    sendRawVotePayloadOverP2P(FinalityVoteSyncCodec.encodeResponse(
                            req.fromEpoch(), req.fromEpoch(), req.fromEpoch() - 1, 0));
                }
                log.debug("Vote-sync request (from epoch{}, cursor={}) has nothing to serve",
                        req.fromEpoch(), req.cursor());
                return;
            }
            long servedMin = Long.MAX_VALUE;
            long servedMax = Long.MIN_VALUE;
            for (Vote v : page) {
                if (v.getEpoch() < servedMin) {
                    servedMin = v.getEpoch();
                }
                if (v.getEpoch() > servedMax) {
                    servedMax = v.getEpoch();
                }
            }
            noteEpochForCursor(servedMax);
            // PLAN-057 第 13 处（第 9/10 轮实测）：响应标记必须与页同消息。
            // 旧写法先发页、再独立发一条标记，两条广播可各走各的连接
            //            ⇒ "页到、标记丢" 结构上无法保证不发生；实测后枚就是
            //            无标记 → 老启发式把游标从 0 拉到 147，1..146 整段跳过。
            //            现在标记追加为页消息末条交星（接收端按魔数分流 0x5C）。
            //            第 9 处的限制保留：仅 cursor=true 请求才生成标记。
            byte[] d13Marker = req.cursor()
                    ? FinalityVoteSyncCodec.encodeResponse(
                            req.fromEpoch(), servedMin, servedMax, page.size())
                    : null;
            sendVotesOverP2P(page, d13Marker);
            log.info("Vote-sync request (from epoch{}, cursor={}) served with {} votes"
                    + " (servedRange={}..{})",
                    req.fromEpoch(), req.cursor(), page.size(), servedMin, servedMax);
            return;
        } catch (RuntimeException e) {
            log.warn("Vote-sync request (from epoch {}) could not read local store: {}",
                    req.fromEpoch(), e.getMessage());
            return;
        }
    }

    /**
     * 打开/关闭补票游标分页（PLAN-057）。
     *
     * <p>为什么需要包内方法：{@code antiEntropyPagingEnabled} 由 {@code @Value}
     * 注入，而两参构造器不过 Spring——测试（与本类同包）需要一条不经容器的
     * 打开路径。生产侧仍走 {@code nexus.finality.anti-entropy.paging-enabled}。</p>
     *
     * <p>关闭时重置分页状态：避免"上次爬到一半"的游标污染下一次开启。</p>
     */
    void setAntiEntropyPagingEnabled(boolean enabled) {
        this.antiEntropyPagingEnabled = enabled;
        if (!enabled) {
            pagingCooldownUntilNanos = 0L;
            pagingResponseSeen = false;
            pagingPeerExhausted = false;
            pagingMarkerCapable = false;
            markerlessGraceTicks.set(0);
        }
        if (!enabled) {
            nextSyncCursorEpoch = -1L;
            lastRespondedEpoch = -1L;
            pagingRoundIssued = false;
            roundAskedFrom = -1L;
            roundIssuedAtNanos = 0L;
            votesSinceRound.set(0);
        }
    }

    /**
     * 覆盖分页终止宽限期（PLAN-057；包内可见，供测试把 5000ms 缩到毫秒级）。
     *
     * <p>生产侧仍走 {@code nexus.finality.anti-entropy.paging-grace-ms}。</p>
     */
    void setAntiEntropyPagingGraceMs(long millis) {
        this.antiEntropyPagingGraceMs = Math.max(0L, millis);
    }

    void setAntiEntropyPagingCooldownMs(long millis) {
        this.antiEntropyPagingCooldownMs = Math.max(0L, millis);
    }

    void setAntiEntropyMarginEpochs(long epochs) {
        this.antiEntropyMarginEpochs = Math.max(0L, epochs);
    }

    /** 本地已知的最大投票 epoch；无票返回 -1。 */
    long localMaxVoteEpoch() {
        org.nexus.consensus.finality.persistence.FinalityStateStore store = gadget.getStateStore();
        if (store == null) {
            return -1L;
        }
        java.util.Map<String, java.util.Set<String>> all;
        try {
            all = store.loadAllVotes();
        } catch (RuntimeException e) {
            return -1L;
        }
        long max = -1L;
        for (String key : all.keySet()) {
            int sep = key.indexOf('|');
            if (sep <= 0) {
                continue;
            }
            try {
                max = Math.max(max, Long.parseLong(key.substring(0, sep)));
            } catch (NumberFormatException ignored) {
                // 键格式异常：跳过，不影响其它 epoch
            }
        }
        return max;
    }

    /**
     * 一次性回推多条投票（单条 P2P 消息承载多个 VOTE 交易，减少消息数）。
     *
     * <p>PLAN-057 第 13 处（第 9/10 轮实测）：<b>响应标记（0x5C）必须与页同消息一起发</b>。
     * 旧实现先发页、再独立发一条标记＋两条广播可以各走各的连接
     * ⇒ "页到了、标记没到"结构上无法保证不发生。实测后枚：请求方因无标记而走老启发式，
     * 把游标从 0 拉到 147，1..146 整段跳过（空漏 30 多票）。现在标记作为页消息的<b>末条交星</b>追加
     * （接收端 onTransactions 本就按魔数分流 0x5C）⇒ 页到则标记必到。</p>
     */
    void sendVotesOverP2P(java.util.List<Vote> votes) {
        sendVotesOverP2P(votes, null);
    }

    /**
     * 回推页＋可选地把响应标记追加到<b>同一条</b>消息末尾。
     */
    void sendVotesOverP2P(java.util.List<Vote> votes, byte[] markerPayload) {
        if (peerServer == null || votes == null || votes.isEmpty()) {
            return;
        }
        try {
            NexusChainOuterClass.Transactions.Builder msg = NexusChainOuterClass.Transactions.newBuilder();
            for (Vote v : votes) {
                msg.addTransactions(NexusChainOuterClass.Transaction.newBuilder()
                        .setTransactionType(NexusChainOuterClass.TransactionType.VOTE)
                        .setPayload(ByteString.copyFrom(FinalityVoteCodec.encode(v)))
                        .build());
            }
            if (markerPayload != null) {
                msg.addTransactions(NexusChainOuterClass.Transaction.newBuilder()
                        .setTransactionType(NexusChainOuterClass.TransactionType.VOTE)
                        .setPayload(ByteString.copyFrom(markerPayload))
                        .build());
            }
            peerServer.broadcast(msg.build());
        } catch (RuntimeException e) {
            log.warn("P2P vote-sync response failed: {}", e.getMessage());
        }
    }

    /** 把已编码的最终性载荷（投票或补票请求）封成 VOTE 交易广播。 */
    private void sendRawVotePayloadOverP2P(byte[] payload) {
        try {
            NexusChainOuterClass.Transaction tx = NexusChainOuterClass.Transaction.newBuilder()
                    .setTransactionType(NexusChainOuterClass.TransactionType.VOTE)
                    .setPayload(ByteString.copyFrom(payload))
                    .build();
            peerServer.broadcast(NexusChainOuterClass.Transactions.newBuilder().addTransactions(tx).build());
        } catch (RuntimeException e) {
            log.warn("P2P finality payload broadcast failed: {}", e.getMessage());
        }
    }

    /**
     * 注册监听器（用于未来挂接 P2P 真实通道）。
     */
    public void addListener(VoteListener listener) {
        externalListeners.add(Objects.requireNonNull(listener));
    }

    /**
     * 投票广播事件（进程内事件总线信封）。
     */
    public static final class FinalityVoteBroadcastEvent extends ApplicationEvent {
        private final Vote vote;
        private final byte[] payload;

        public FinalityVoteBroadcastEvent(Vote vote, byte[] payload) {
            super(vote);
            this.vote = vote;
            this.payload = payload;
        }

        public Vote getVote() { return vote; }
        public byte[] getPayload() { return payload; }
    }

    /**
     * 外部投票监听器（用于未来桥接真实 P2P 通道）。
     */
    public interface VoteListener {
        void onOutgoingVote(Vote vote, byte[] payload);
        default void onIncomingVote(Vote vote) {}
    }
}
