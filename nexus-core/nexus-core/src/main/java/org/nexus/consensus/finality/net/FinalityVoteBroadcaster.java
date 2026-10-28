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
        Vote vote = FinalityVoteCodec.decode(payload);
        if (vote == null) {
            log.warn("Received malformed finality vote payload (dropped)");
            return;
        }
        // PLAN-057：记录本节点"本轮已见到的最大 epoch"。
        // 分页爬升的游标推进由此驱动：cursor = lastRespondedEpoch + 1。
        // 单调不回退：正常投票仍在继续，本地已见更大的 epoch 时游标不动。
        lastRespondedEpoch = Math.max(lastRespondedEpoch, vote.getEpoch());
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
                                + "peerExhausted={} graceMs={}",
                        maxEpoch, nextSyncCursorEpoch, roundAskedFrom, lastRespondedEpoch,
                        votesSinceRound.get(), graceElapsed, peerExhaustedAtCursor,
                        antiEntropyPagingGraceMs);
                if (pagingRoundIssued && graceElapsed && peerExhaustedAtCursor) {
                    log.info("Anti-entropy paging complete: no vote at or after epoch {}"
                            + " after {}ms grace; resuming margin-based gap repair"
                            + " (local max epoch={})",
                            roundAskedFrom, antiEntropyPagingGraceMs, maxEpoch);
                    nextSyncCursorEpoch = -1L;
                    lastRespondedEpoch = -1L;
                    pagingRoundIssued = false;
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
                        nextSyncCursorEpoch = floor;
                        log.info("Anti-entropy paging: starting cursor crawl from epoch {}"
                                + " (local max epoch={}, margin={})",
                                nextSyncCursorEpoch, maxEpoch, antiEntropyMarginEpochs);
                    } else if (lastRespondedEpoch >= 0 && lastRespondedEpoch + 1 > nextSyncCursorEpoch) {
                        // 续轮：推进游标 = 本轮已见最大 epoch + 1（单调不回退）。
                        nextSyncCursorEpoch = lastRespondedEpoch + 1;
                    }
                    long cursor = nextSyncCursorEpoch < 0 ? 0L : nextSyncCursorEpoch;
                    roundAskedFrom = cursor;
                    roundIssuedAtNanos = System.nanoTime();
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
                log.debug("Vote-sync request (from epoch{}, cursor={}) has nothing to serve",
                        req.fromEpoch(), req.cursor());
                return;
            }
            sendVotesOverP2P(page);
            // PLAN-057：记录本端"刚服务出去的最大 epoch"，供对端推进游标时
            // 与本端 own-crawl 取 max 用（多对端场景下不回退）。
            long servedMax = Long.MIN_VALUE;
            for (Vote v : page) {
                if (v.getEpoch() > servedMax) {
                    servedMax = v.getEpoch();
                }
            }
            lastRespondedEpoch = Math.max(lastRespondedEpoch, servedMax);
            log.info("Vote-sync request (from epoch{}, cursor={}) served with {} votes"
                    + " (servedMaxEpoch={})",
                    req.fromEpoch(), req.cursor(), page.size(), servedMax);
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

    /** 一次性回推多条投票（单条 P2P 消息承载多个 VOTE 交易，减少消息数）。 */
    void sendVotesOverP2P(java.util.List<Vote> votes) {
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
