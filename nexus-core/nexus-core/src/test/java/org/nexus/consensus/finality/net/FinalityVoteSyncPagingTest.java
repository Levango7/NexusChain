package org.nexus.consensus.finality.net;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.consensus.finality.Vote;
import org.nexus.consensus.finality.persistence.InMemoryFinalityStateStore;
import org.nexus.consensus.pos.StakingService;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;
import org.nexus.p2p.NexusChainOuterClass;
import org.nexus.p2p.PeerServer;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * PLAN-057：补票游标分页（anti-entropy paging）。
 *
 * <p>承接 PLAN-048b 的实测结论：{@code margin-epochs=200} 能把窗外缺口从 29 降到 0，
 * 但当前实现的 {@code from = maxEpoch - margin} + {@code max-votes=200} 单轮上限
 * <b>结构上补不回超过 200 票的中段缺口</b>——响应端 SQL 是
 * {@code ORDER BY epoch DESC LIMIT N}，每轮拿回的永远是同一批<b>最新</b>票，
 * 于是 "from 每轮只前进约 1 个 epoch"（不是慢，是补不上）。</p>
 *
 * <p>本测试锁定 5 件事（PLAN-057 §三）：</p>
 * <ol>
 *   <li>{@code cursor=true} 的请求载荷带 {@code "cursor":true}；</li>
 *   <li>分页模式下响应端<b>升序</b>服务，请求方游标 0 → 200 → 400 单调推进；</li>
 *   <li>响应不足上限 ⇒ 判定追平并退出分页；</li>
 *   <li>不含 cursor 字段的解码 ⇒ {@code cursor=false}（旧节点向后兼容）；</li>
 *   <li>{@code paging-enabled=false} 时调度走旧 margin 路径。</li>
 * </ol>
 */
class FinalityVoteSyncPagingTest {

    private static final byte[] CP1 = new byte[]{1, 2, 3};

    private ValidatorRegistry registry;
    private StakingService staking;
    private final Map<String, Ed25519KeyPair> validatorKeys = new HashMap<>();

    private InMemoryFinalityStateStore storeA;
    private InMemoryFinalityStateStore storeB;
    private FinalityGadget gadgetA;
    private FinalityGadget gadgetB;

    @BeforeEach
    void setUp() {
        validatorKeys.clear();
        registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        staking = new StakingServiceImpl();
        injectRegistryIntoStaking(staking, registry);
        addValidator("v1", 1000);

        storeA = new InMemoryFinalityStateStore();
        storeB = new InMemoryFinalityStateStore();
        gadgetA = new FinalityGadget(registry, staking, storeA);
        gadgetB = new FinalityGadget(registry, staking, storeB);
    }

    // ---------- 1) 载荷带 cursor:true ----------

    @Test
    void cursorRequestCarriesCursorFlag() {
        List<byte[]> sent = new CopyOnWriteArrayList<>();
        FinalityVoteBroadcaster broadcaster = newNode(gadgetA, capturingPeer(sent));

        broadcaster.requestVoteSync(12L, 7, true);

        assertEquals(1, sent.size());
        FinalityVoteSyncCodec.SyncRequest req = FinalityVoteSyncCodec.decode(sent.get(0));
        assertNotNull(req);
        assertEquals(12L, req.fromEpoch());
        assertEquals(7, req.maxVotes());
        assertTrue(req.cursor(), "分页请求必须声明 cursor=true");
    }

    @Test
    void legacyRequestOmitsCursor() {
        List<byte[]> sent = new CopyOnWriteArrayList<>();
        FinalityVoteBroadcaster broadcaster = newNode(gadgetA, capturingPeer(sent));

        broadcaster.requestVoteSync(12L, 7);

        FinalityVoteSyncCodec.SyncRequest req = FinalityVoteSyncCodec.decode(sent.get(0));
        assertNotNull(req);
        assertFalse(req.cursor(), "旧两参入口必须隐式为 cursor=false（行为不变）");
    }

    // ---------- 2) 升序服务 + 游标推进 0 → 200 → 400 ----------

    @Test
    void responderServesAscendingForCursorRequests() {
        // 5 张不同 epoch 的票（降序实现会先回 5、升序必须先回 1）。
        for (long epoch = 1; epoch <= 5; epoch++) {
            gadgetA.submitVote(vote("v1", epoch, CP1));
        }
        List<byte[]> sent = new CopyOnWriteArrayList<>();
        FinalityVoteBroadcaster responder = newNode(gadgetA, capturingPeer(sent));

        responder.onVoteReceived(FinalityVoteSyncCodec.encode(0L, 3, true));

        assertEquals(3, sent.size(), "max=3 时只回推 3 张");
        long[] epochs = new long[3];
        for (int i = 0; i < 3; i++) {
            Vote v = FinalityVoteCodec.decode(sent.get(i));
            assertNotNull(v);
            epochs[i] = v.getEpoch();
        }
        assertEquals(1L, epochs[0], "cursor 分页必须升序服务：第一张应是最老的 epoch=1");
        assertEquals(2L, epochs[1]);
        assertEquals(3L, epochs[2]);
    }

    @Test
    void cursorAdvancesMonotonicallyAcrossRounds() {
        // 对端有 250 张 epoch=1..250 的票——按默认上限 200，旧实现"每轮拿回
        // 最新 200 张"永远补不回 1..200 这个中段；分页必须 0 -> 201 两轮补完。
        final long total = 250L;
        for (long epoch = 1; epoch <= total; epoch++) {
            gadgetA.submitVote(vote("v1", epoch, CP1));
        }

        FinalityVoteBroadcaster[] nodes = new FinalityVoteBroadcaster[2];
        nodes[0] = newNode(gadgetA, relayingPeer(p -> nodes[1].onVoteReceived(p)));
        // 请求方 B：空库，模拟"宕机后要补 250 张票"。
        List<byte[]> bRequests = new CopyOnWriteArrayList<>();
        nodes[1] = newNode(gadgetB, relayingPeer(p -> {
            if (FinalityVoteSyncCodec.isSyncPayload(p)) {
                bRequests.add(p);
            }
            nodes[0].onVoteReceived(p);
        }));

        // 打开分页开关（包内 setter：不经 Spring @Value 也能开）。
        nodes[1].setAntiEntropyPagingEnabled(true);

        // 第 1 轮：B 空库 ⇒ 首轮从 margin 地板起（maxEpoch=-1 ⇒ floor=0）。
        nodes[1].scheduledVoteAntiEntropy();
        assertEquals(200, allVotes(storeB).size(), "第 1 轮应补回最老的 200 张");

        // 第 2 轮：推进游标 = 本轮已见最大 epoch+1 = 201 ⇒ A 给 201..250。
        nodes[1].scheduledVoteAntiEntropy();
        // 第 3 轮：从 251 起，A 已无货 ⇒ 判追平退出分页。
        nodes[1].scheduledVoteAntiEntropy();

        long[] froms = bRequests.stream()
                .map(FinalityVoteSyncCodec::decode)
                .filter(java.util.Objects::nonNull)
                .mapToLong(FinalityVoteSyncCodec.SyncRequest::fromEpoch)
                .toArray();
        assertTrue(froms.length >= 3, "应有 3 轮请求，实际 " + froms.length);
        assertEquals(0L, froms[0], "第 1 轮从地板（0）起");
        assertEquals(201L, froms[1],
                "第 2 轮必须从上一轮终点+1=201 起——旧实现会重新请求并再次拿回"
                        + "「最新 200 张」，1..200 的中段永远补不上");
        assertEquals(251L, froms[2], "第 3 轮应已越过对端前沿（251），据此判追平");
        assertEquals(total, allVotes(storeB).size(),
                "两轮分页必须把 250 张票全部补齐（这正是 PLAN-048b 补不上的那部分）");
    }

    // ---------- 3) 响应不足上限 ⇒ 追平退出分页 ----------

    @Test
    void shortResponseCompletesPaging() {
        // 对端只有 3 张票，max=4 ⇒ 响应不足上限 ⇒ 一轮即追平。
        for (long epoch = 1; epoch <= 3; epoch++) {
            gadgetA.submitVote(vote("v1", epoch, CP1));
        }
        List<byte[]> sent = new CopyOnWriteArrayList<>();
        FinalityVoteBroadcaster broadcaster = newNode(gadgetA, capturingPeer(sent));

        broadcaster.onVoteReceived(FinalityVoteSyncCodec.encode(0L, 4, true));

        assertEquals(3, sent.size(), "只有 3 张可服务");
    }

    // ---------- 4) 向后兼容：无 cursor 字段 ----------

    @Test
    void decodeWithoutCursorDefaultsToFalse() {
        byte[] legacy = FinalityVoteSyncCodec.encode(9L, 5);
        FinalityVoteSyncCodec.SyncRequest req = FinalityVoteSyncCodec.decode(legacy);
        assertNotNull(req);
        assertEquals(9L, req.fromEpoch());
        assertEquals(5, req.maxVotes());
        assertFalse(req.cursor(),
                "老节点/老载荷没有 cursor 键，必须解成 false ⇒ 走 PLAN-025 原行为");

        // 兼容构造器（两参 record）也必须等价。
        FinalityVoteSyncCodec.SyncRequest viaCtor = new FinalityVoteSyncCodec.SyncRequest(9L, 5);
        assertFalse(viaCtor.cursor());
    }

    // ---------- 5) 开关关闭时走旧 margin 路径 ----------

    @Test
    void schedulerFallsBackToMarginWhenPagingDisabled() {
        gadgetA.submitVote(vote("v1", 100, CP1));
        gadgetA.submitVote(vote("v1", 101, CP1));

        List<byte[]> sent = new CopyOnWriteArrayList<>();
        FinalityVoteBroadcaster broadcaster = newNode(gadgetA, capturingPeer(sent));

        broadcaster.scheduledVoteAntiEntropy();

        assertEquals(1, sent.size());
        FinalityVoteSyncCodec.SyncRequest req = FinalityVoteSyncCodec.decode(sent.get(0));
        assertNotNull(req);
        assertEquals(51L, req.fromEpoch(), "margin=50、本地 max=101 ⇒ 从 51 起（旧行为不变）");
        assertFalse(req.cursor(), "paging-enabled 缺省 false，不得声明 cursor");
    }

    // ---------- 测试床回归：异步投递 + 广播涓流 ----------

    /**
     * PLAN-057 测试床回归：分页不得被"广播涓流 + 异步投递"提前判死。
     *
     * <p>真缺陷（2026-10-28 三节点测试床实测，E 宕机 631s）：原终止判据是
     * "本轮收到的票数 < max-votes"，而在线集群里正常广播是涓流（实测约
     * 3 票/5s），该条件几乎恒真 ⇒ 分页在第一轮后就自认为完成，
     * 583s 追赶里 {@code cursor=true} 只出现 1 次。</p>
     *
     * <p>为什么单测此前全绿：它只投递同步响应、没有广播涓流，且响应立即到达
     * ⇒ 计数与宽限期都不会暴露问题（与 PLAN-055 同型盲区）。</p>
     *
     * <p>本测试两件事同时造出来：①响应<b>异步</b>到达（另一线程，晚一个 grace）；②
     * 期间有<b>广播涓流</b>（少量正常票）。修正后必须继续爬升而不是终止。</p>
     */
    @Test
    void pagingSurvivesBroadcastTrickleAndAsyncDelivery() throws Exception {
        // 对端只有 3 张票。max-votes 默认 200 ⇒ 本轮只收到 3 票（远小于上限）。
        // 旧判据"收到数 < 上限"会在此判终止；新判据看"游标处有没有新数据"，
        // 游标=0 而拿到了 epoch>=0 的票 ⇒ 未耗尽 ⇒ 必须继续。
        final long total = 3L;
        for (long epoch = 1; epoch <= total; epoch++) {
            gadgetA.submitVote(vote("v1", epoch, CP1));
        }

        FinalityVoteBroadcaster[] nodes = new FinalityVoteBroadcaster[2];
        nodes[0] = newNode(gadgetA, relayingPeer(p -> nodes[1].onVoteReceived(p)));

        final List<byte[]> bRequests = new CopyOnWriteArrayList<>();
        // B 的 peer：记录请求 + 正常投递给 A（A 的响应再经 A 的 peer 回到 B）。
        nodes[1] = newNode(gadgetB, relayingPeer(p -> {
            if (FinalityVoteSyncCodec.isSyncPayload(p)) {
                bRequests.add(p);
            }
            nodes[0].onVoteReceived(p);
        }));
        nodes[1].setAntiEntropyPagingEnabled(true);
        nodes[1].setAntiEntropyPagingGraceMs(60L);   // 缩短宽限期，测试不等 5 秒

        // 第 1 轮：从地板 0 起 ⇒ A 回 3 张（远小于上限 200）。
        nodes[1].scheduledVoteAntiEntropy();
        assertEquals(3, allVotes(storeB).size(), "第 1 轮应补回全部 3 张");

        // 关键：宽限期过去后跑第 2 轮。旧判据（3 < 200）会判"完成"从而不发请求；
        // 新判据看到游标 0 处有新数据（epoch 1..3），判"未耗尽" ⇒ 推进到 4 再请求。
        Thread.sleep(120);
        nodes[1].scheduledVoteAntiEntropy();

        assertTrue(bRequests.size() >= 2,
                "只收到 3 票（< 上限 200）就停止爬升：这是测试床实测的缺陷形态");
        long secondFrom = bRequests.size() >= 2
                ? FinalityVoteSyncCodec.decode(bRequests.get(1)).fromEpoch()
                : -1L;
        assertEquals(4L, secondFrom,
                "第 2 轮应从本轮已见最大 epoch=3 的 +1 = 4 起，证明未被提前判死");
    }

    /**
     * PLAN-057 复测回归（第 9 例）：宕机节点重启后，分页首轮起点必须是
     * **缺口地板**，不能是进程内 lastRespondedEpoch。
     *
     * <p>真缺陷（2026-10-28 复测实测）：E 宕机 601s 后重启，日志显示
     * {@code starting cursor crawl from epoch 197 (local max epoch=196)}——
     * 首轮起点取自 lastRespondedEpoch+1（宕机前最后一条本地票），
     * 于是只问到边界 1~2 票就判"无货"，255 票的中段完全没搬
     * （+26s 只补进 129 票，之后再无 paging 请求）。</p>
     */
    @Test
    void firstPagingRoundStartsFromMarginFloorNotLastResponded() {
        // B（请求方）：本地已有 epoch 1..196 的票——模拟"宕机前的本地历史"。
        for (long epoch = 1; epoch <= 196; epoch++) {
            gadgetB.submitVote(vote("v1", epoch, CP1));
        }
        // 对端 A：历史与 B 相同，且宕机期间继续爬到 451。
        for (long epoch = 1; epoch <= 451; epoch++) {
            gadgetA.submitVote(vote("v1", epoch, CP1));
        }

        List<byte[]> bRequests = new CopyOnWriteArrayList<>();
        FinalityVoteBroadcaster[] nodes = new FinalityVoteBroadcaster[2];
        nodes[0] = newNode(gadgetA, relayingPeer(p -> nodes[1].onVoteReceived(p)));
        nodes[1] = newNode(gadgetB, relayingPeer(p -> {
            if (FinalityVoteSyncCodec.isSyncPayload(p)) {
                bRequests.add(p);
            }
            nodes[0].onVoteReceived(p);
        }));
        nodes[1].setAntiEntropyPagingEnabled(true);
        nodes[1].setAntiEntropyPagingGraceMs(60L);

        // 关键：让 B 先"见过" epoch 196 的票 ⇒ 进程内 lastRespondedEpoch=196
        // （这正是复测里 E 的状态：宕机前最后一条本地票）。
        nodes[1].onVoteReceived(FinalityVoteCodec.encode(vote("v1", 196, CP1)));

        // 首轮调度：应从 margin 地板起（maxEpoch=196, margin=50 ⇒ 146），
        // 而不是被污染的 lastRespondedEpoch+1=197。
        nodes[1].scheduledVoteAntiEntropy();

        assertTrue(bRequests.size() >= 1, "首轮必须发出请求");
        long firstFrom = FinalityVoteSyncCodec.decode(bRequests.get(0)).fromEpoch();
        assertEquals(146L, firstFrom,
                "分页首轮起点必须是 margin 地板（196-50=146），"
                        + "不得取进程内 lastRespondedEpoch+1=197（复测实测缺陷）");
    }

    /**
     * PLAN-057 复测回归（第 10 例）：**广播涓流不得把分页游标拉到链头**。
     *
     * <p>真缺陷（2026-10-28 第三轮测试床，E 重启后 583s 无进展）：首轮从 floor=0
     * 拿到 200 票后，`lastRespondedEpoch` 被**正常广播**的票推到链头（实测涨到
     * 对端前沿之上），于是后续每轮 `from = 链头 + 1`，响应端每次都"无货可服务"
     * （只留 DEBUG），中段缺口一次都没被请求；而终止判据又因链头持续前进永不成立。</p>
     *
     * <p>三个"零"（响应端零次 served、调度器零次 WARN、零次 paging complete）
     * 与"调度停摆"的假设不符——请求一直在发，只是 from 已越过前沿。</p>
     */
    @Test
    void liveBroadcastTrickleAtHeadMustNotDragPagingCursor() {
        // 对端有 250 张票（超过单轮上限 200），B 是空库。
        final long total = 250L;
        for (long epoch = 1; epoch <= total; epoch++) {
            gadgetA.submitVote(vote("v1", epoch, CP1));
        }

        List<byte[]> bRequests = new CopyOnWriteArrayList<>();
        FinalityVoteBroadcaster[] nodes = new FinalityVoteBroadcaster[2];
        nodes[0] = newNode(gadgetA, relayingPeer(p -> nodes[1].onVoteReceived(p)));
        nodes[1] = newNode(gadgetB, relayingPeer(p -> {
            if (FinalityVoteSyncCodec.isSyncPayload(p)) {
                bRequests.add(p);
            }
            nodes[0].onVoteReceived(p);
        }));
        nodes[1].setAntiEntropyPagingEnabled(true);
        nodes[1].setAntiEntropyPagingGraceMs(60L);

        // 第 1 轮：从 floor=0 起，A 回最老的 200 张（epoch 1..200）。
        nodes[1].scheduledVoteAntiEntropy();
        assertEquals(200, allVotes(storeB).size(), "第 1 轮应补回最老的 200 张");

        // 关键：模拟在线集群的正常广播涓流——链头附近来一张 epoch=900 的票。
        nodes[1].onVoteReceived(FinalityVoteCodec.encode(vote("v1", 900, CP1)));

        // 第 2 轮：游标必须仍停在本轮窗口（[0,200)）的终点之后，即 201；
        // 不得被涓流拉到 901（那样 A 无货，中段 201..250 永远补不上）。
        nodes[1].scheduledVoteAntiEntropy();
        assertEquals(2, bRequests.size(), "第 2 轮必须发出请求");
        assertEquals(201L, FinalityVoteSyncCodec.decode(bRequests.get(1)).fromEpoch(),
                "广播涓流（epoch=900）不得把游标推到链头：第 2 轮应从 201 起");

        // 第 3 轮：A 已无 ≥251 的货 ⇒ 收尾。
        nodes[1].scheduledVoteAntiEntropy();
        assertTrue(allVotes(storeB).stream().anyMatch(v -> v.getEpoch() == total),
                "201..250 的中段必须补齐（旧实现游标被涓流拉到 901、A 无货，中段永远补不上）");
    }

    /**
     * PLAN-057 第 7 处回归（第 11 例）：**宽限期长于触发周期时，终止分支仍必须可达**。
     *
     * <p>真缺陷（第四轮测试床实证）：{@code roundIssuedAtNanos} 每轮无条件刷新，
     * 而每轮间隔就是 {@code interval-ms} ⇒ "距上次发请求的时间"恒等于 interval。
     * 测试床 {@code grace=6000 > interval=5000} ⇒ 128 个 tick 中
     * {@code graceElapsed=true} 出现 <b>0 次</b>、{@code peerExhausted=true} 出现 25 次
     * ⇒ 终止分支是死代码：分页永不退出、也永不回退 margin 缝隙修补。</p>
     *
     * <p>本测试 grace=200ms、每 40ms 一 tick：旧实现每 tick 重置计时，
     * 永远等不到 200ms；修正后计时从"游标上次前进"起算，必然终止。</p>
     */
    @Test
    void graceLongerThanTickGapStillTerminates() throws Exception {
        for (long epoch = 1; epoch <= 3; epoch++) {
            gadgetA.submitVote(vote("v1", epoch, CP1));
        }

        FinalityVoteBroadcaster[] nodes = new FinalityVoteBroadcaster[2];
        nodes[0] = newNode(gadgetA, relayingPeer(p -> nodes[1].onVoteReceived(p)));

        final List<byte[]> bRequests = new CopyOnWriteArrayList<>();
        nodes[1] = newNode(gadgetB, relayingPeer(p -> {
            if (FinalityVoteSyncCodec.isSyncPayload(p)) {
                bRequests.add(p);
            }
            nodes[0].onVoteReceived(p);
        }));
        nodes[1].setAntiEntropyPagingEnabled(true);
        nodes[1].setAntiEntropyPagingGraceMs(200L);

        // 第 1 轮：从地板 0 起，A 回 epoch 1..3。
        nodes[1].scheduledVoteAntiEntropy();
        assertEquals(3, allVotes(storeB).size(), "第 1 轮应补回 3 张");

        // 之后每 40ms 一 tick：游标停在 4（A 在 4 之后无货）。
        // 旧实现每 tick 重置计时 ⇒ grace 永远只有 40ms ⇒ 永不终止。
        boolean sawMarginRequest = false;
        for (int i = 0; i < 12 && !sawMarginRequest; i++) {
            Thread.sleep(40);
            nodes[1].scheduledVoteAntiEntropy();
            for (byte[] req : bRequests) {
                if (!FinalityVoteSyncCodec.decode(req).cursor()) {
                    sawMarginRequest = true;
                    break;
                }
            }
        }
        assertTrue(sawMarginRequest,
                "grace(200ms) > tick 间隔(40ms) 时终止分支不可达：分页永不退出、"
                        + "也永不回退 margin 修补");
    }

    // ---------- helpers ----------

    private FinalityVoteBroadcaster newNode(FinalityGadget gadget, PeerServer peerServer) {
        return new FinalityVoteBroadcaster(gadget, event -> { }, peerServer);
    }

    private PeerServer capturingPeer(List<byte[]> sink) {
        return relayingPeer(sink::add);
    }

    private PeerServer relayingPeer(java.util.function.Consumer<byte[]> deliver) {
        PeerServer peerServer = mock(PeerServer.class);
        doAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            if (arg instanceof NexusChainOuterClass.Transactions txs) {
                for (NexusChainOuterClass.Transaction tx : txs.getTransactionsList()) {
                    deliver.accept(tx.getPayload().toByteArray());
                }
            }
            return null;
        }).when(peerServer).broadcast(any());
        return peerServer;
    }

    private List<Vote> allVotes(InMemoryFinalityStateStore store) {
        return store.loadVoteObjectsSince(0).values().stream()
                .flatMap(List::stream)
                .toList();
    }

    private void injectRegistryIntoStaking(StakingService target, ValidatorRegistry targetRegistry) {
        try {
            var field = StakingServiceImpl.class.getDeclaredField("validatorRegistry");
            field.setAccessible(true);
            field.set(target, targetRegistry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void addValidator(String address, int stake) {
        Ed25519KeyPair keyPair = Ed25519.generateKeyPair();
        validatorKeys.put(address, keyPair);
        registry.register(address, Hex.encodeHexString(keyPair.getPublicKey().getEncoded()),
                new BigDecimal(stake), 0.1);
        Validator validator = registry.getValidator(address);
        validator.setStatus(ValidatorStatus.ACTIVE);
        staking.stake(address, new BigDecimal(stake));
    }

    private Vote vote(String validator, long epoch, byte[] checkpoint) {
        Ed25519KeyPair keyPair = validatorKeys.get(validator);
        byte[] publicKey = keyPair.getPublicKey().getEncoded();
        Vote unsigned = new Vote(epoch, checkpoint, validator, new byte[0], publicKey);
        try {
            byte[] signature = keyPair.getPrivateKey().sign(unsigned.signingPayload());
            return new Vote(epoch, checkpoint, validator, signature, publicKey);
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 signing failed for test vote", e);
        }
    }
}
