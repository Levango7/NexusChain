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

    private static final byte[] CP1 = new byte[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};

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

        // PLAN-057 第 8 处：响应端在回推票之后会额外发一条 0x5C 响应标记，
        // 携带实际服务范围。计数时必须把它排除，否则会把标记当成"第 4 张票"。
        List<byte[]> votesSent = sent.stream()
                .filter(p -> !FinalityVoteSyncCodec.isResponsePayload(p))
                .collect(java.util.stream.Collectors.toList());
        // 第 11 处（精确语义）：页 = "epoch <= 第 max 行所在 epoch"。
        // 库内 5 票（epoch 1..5 各 1 票）、max=3 ⇒ 第 3 行就是 epoch 3 ⇒ 页=1..3。
        // 旧纯 LIMIT 写法同样回 3，但边界 epoch 有多票时会把那个 epoch 切成两半；
        // 本实现的页边界始终与 epoch 对齐，因此不会丢票。
        assertEquals(3, votesSent.size(),
                "第 11 处：页=1..3（不超限、不与 epoch 边界错位）");
        long[] epochs = new long[votesSent.size()];
        for (int i = 0; i < votesSent.size(); i++) {
            Vote v = FinalityVoteCodec.decode(votesSent.get(i));
            assertNotNull(v);
            epochs[i] = v.getEpoch();
        }
        // 标记必须回带实际范围（3 张：1..3），请求方据此精确推进游标。
        FinalityVoteSyncCodec.SyncResponse marker = sent.stream()
                .filter(FinalityVoteSyncCodec::isResponsePayload)
                .findFirst()
                .map(FinalityVoteSyncCodec::decodeResponse)
                .orElse(null);
        assertNotNull(marker, "响应端必须回带 0x5C 响应标记");
        assertEquals(0L, marker.requestFrom());
        assertEquals(1L, marker.servedFrom());
        assertEquals(3L, marker.servedTo(), "第 11 处：页=1..3 ⇒ servedTo=3（下一轮从 4 起）");
        assertEquals(3, marker.count());
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
        // 第 11 处（精确语义）：页 = epoch <= 第 200 行所在 epoch = 200 ⇒ 1..200。
        // 页边界与 epoch 对齐；旧纯 LIMIT 也回 200，但边界 epoch 有多票时会截断丢票。
        assertEquals(200, allVotes(storeB).size(),
                "第 11 处：页=1..200（第 200 行的 epoch 就是 200）");

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
                "第 11 处：第 1 页=1..200 ⇒ 第 2 轮从 201 起。"
                        + "不变式是页边界与 epoch 对齐：旧纯 LIMIT 在边界 epoch 有多票时会把票截在页外，"
                        + "而游标按 servedTo+1 跳过 ⇒ 永久丢票");
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

        long voteCount = sent.stream()
                .filter(p -> !FinalityVoteSyncCodec.isResponsePayload(p))
                .count();
        assertEquals(3, voteCount, "只有 3 张可服务");
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
        // 第 11 处（精确语义）：页 = epoch <= 第 200 行所在 epoch = 200 ⇒ 1..200。
        assertEquals(200, allVotes(storeB).size(),
                "第 11 处：页=1..200（第 200 行的 epoch 就是 200，不超限）");

        // 关键：模拟在线集群的正常广播涓流——链头附近来一张 epoch=900 的票。
        nodes[1].onVoteReceived(FinalityVoteCodec.encode(vote("v1", 900, CP1)));

        // 第 2 轮：游标必须仍停在本轮窗口（[0,200)）的终点之后，即 201；
        // 不得被涓流拉到 901（那样 A 无货，中段 201..250 永远补不上）。
        nodes[1].scheduledVoteAntiEntropy();
        assertEquals(2, bRequests.size(), "第 2 轮必须发出请求");
        assertEquals(201L, FinalityVoteSyncCodec.decode(bRequests.get(1)).fromEpoch(),
                "第 11 处：第 1 页=1..200 ⇒ 游标 201；广播涓流（epoch=900）不得把它推到链头");

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

    /**
     * PLAN-057 第 8 处回归（第 12 例）：**链头广播票不得让分页跳过中段**。
     *
     * <p>第五轮测试床的真实形态：E 有 epoch 1..170 和链头 371，缺 171..370。
     * 请求 {@code from=171}、max-votes=200 ⇒ 第 6 处的"本轮请求窗口"
     * 上界是 {@code 171+200=371}，而链头正好是 371 ⇒ 链头的正常广播票
     * 落在窗口内、被当成响应票 ⇒ 游标跳到 372，{@code 171..370} 一次都没被请求
     * （对端日志：{@code servedMaxEpoch=237}，而游标却到了 372）。</p>
     *
     * <p>修法：响应端在回推票后广播 0x5C 标记（{@code requestFrom/servedFrom/
     * servedTo/count}），请求方据此**精确**推进游标，不再靠"收到的最大 epoch"猜。</p>
     *
     * <p>本测试在请求方第二轮之前注入一张链头票（epoch=371），旧实现会因此
     * 把游标推到 372 并永久跳过 171..370。</p>
     */
    @Test
    void responseMarkerPreventsHeadVoteFromSkippingGap() {
        for (long epoch = 1; epoch <= 371; epoch++) {
            gadgetA.submitVote(vote("v1", epoch, CP1));
        }
        // B：有 1..170 和链头 371，缺 171..370（正好 200 个 epoch = 单轮上限）。
        for (long epoch = 1; epoch <= 170; epoch++) {
            gadgetB.submitVote(vote("v1", epoch, CP1));
        }
        gadgetB.submitVote(vote("v1", 371, CP1));

        FinalityVoteBroadcaster[] nodes = new FinalityVoteBroadcaster[2];
        final List<byte[]> bRequests = new CopyOnWriteArrayList<>();
        nodes[0] = newNode(gadgetA, relayingPeer(p -> nodes[1].onVoteReceived(p)));
        nodes[1] = newNode(gadgetB, relayingPeer(p -> {
            if (FinalityVoteSyncCodec.isSyncPayload(p)) {
                bRequests.add(p);
            }
            nodes[0].onVoteReceived(p);
        }));
        nodes[1].setAntiEntropyPagingEnabled(true);
        nodes[1].setAntiEntropyPagingGraceMs(60L);
        nodes[1].setAntiEntropyPagingCooldownMs(0L);
        // 与测试床一致：margin=200 ⇒ 地板 = 371-200 = 171（缺 171..370）。
        nodes[1].setAntiEntropyMarginEpochs(200L);

        // 第 1 轮：地板 = 371 - 200 = 171；A 升序服务 171..370。
        nodes[1].scheduledVoteAntiEntropy();
        // 关键注入：链头的正常广播票。旧实现（无 0x5C）会把它当响应票 ⇒ 游标 372。
        nodes[0].broadcast(vote("v1", 371, CP1));
        // 第 2 轮（应从 371 起，而不是 372）、第 3 轮（372，对端无货）、第 4 轮（终止）。
        nodes[1].scheduledVoteAntiEntropy();
        nodes[1].scheduledVoteAntiEntropy();
        nodes[1].scheduledVoteAntiEntropy();

        long[] cursorFroms = bRequests.stream()
                .map(FinalityVoteSyncCodec::decode)
                .filter(java.util.Objects::nonNull)
                .filter(FinalityVoteSyncCodec.SyncRequest::cursor)
                .mapToLong(FinalityVoteSyncCodec.SyncRequest::fromEpoch)
                .toArray();
        assertTrue(cursorFroms.length >= 2, "应至少有两轮分页请求，实际 " + cursorFroms.length);
        assertEquals(171L, cursorFroms[0], "首轮从缺口地板 171 起");
        // 第 11 处：页按 epoch 完整交付——A 的库有 1..371 全票，第 1 页
        // 从 171 起取 200 票（171..370）+ 哨兵 epoch 371 整体 ⇒ servedTo=371。
        assertEquals(371L, cursorFroms[1],
                // 第 11 处（精确语义）：页 = epoch <= 第 200 行所在 epoch = 370
                // ⇒ 页=171..370，servedTo=370，下一轮从 371 起。
                // 不变式：页边界与 epoch 对齐——不超限、不截断半个 epoch。
                "第 11 处：页=171..370 ⇒ 第 2 轮从 371 起（链头票 371 已在 B 库）");
        assertEquals(371, allVotes(storeB).size(),
                "171..370 必须被补齐：链头票不能让它被整段跳过");
    }

    /**
     * PLAN-057 第 8 处回归（第 13 例）：**标记尚在途中时，链头票不得触发老启发式**。
     *
     * <p>第 12 例在测试里是同步投递，所以标记总是先到。生产里标记与广播票
     * 走同一条 P2P 通道、到达顺序不保证：若链头票先到、标记后到，
     * 而 {@code pagingResponseSeen} 只表示"本轮标记到了"，则下一轮发请求后
     * {@code !pagingResponseSeen} 为真 ⇒ 老启发式重新生效 ⇒ 游标被拖到链头。</p>
     *
     * <p>本测试把标记**扣住不投递**，先注入一张链头票（epoch=10），
     * 再跑一轮：游标必须仍是 4（响应实际服务到 3），不能是 11。
     * 随后放行标记，游标仍应精确前进。</p>
     */
    @Test
    void inFlightMarkerMustNotLetHeadVoteTriggerHeuristic() {
        for (long epoch = 1; epoch <= 3; epoch++) {
            gadgetA.submitVote(vote("v1", epoch, CP1));
        }

        FinalityVoteBroadcaster[] nodes = new FinalityVoteBroadcaster[2];
        final List<byte[]> heldMarkers = new CopyOnWriteArrayList<>();
        // A 的出站：标记扣住（模拟"还在路上"），票照常投给 B。
        nodes[0] = newNode(gadgetA, relayingPeer(p -> {
            if (FinalityVoteSyncCodec.isResponsePayload(p)) {
                heldMarkers.add(p);
                return;
            }
            nodes[1].onVoteReceived(p);
        }));
        final List<byte[]> bRequests = new CopyOnWriteArrayList<>();
        nodes[1] = newNode(gadgetB, relayingPeer(p -> {
            if (FinalityVoteSyncCodec.isSyncPayload(p)) {
                bRequests.add(p);
            }
            nodes[0].onVoteReceived(p);
        }));
        nodes[1].setAntiEntropyPagingEnabled(true);
        nodes[1].setAntiEntropyPagingGraceMs(5000L);   // 宽限期未过

        // 第 1 轮：0 -> A 服务 1..3（标记被扣住）。
        nodes[1].scheduledVoteAntiEntropy();
        assertEquals(3, allVotes(storeB).size());

        // 链头广播票先到（epoch=10，远高于实际服务范围）。
        nodes[1].onVoteReceived(FinalityVoteCodec.encode(vote("v1", 10, CP1)));

        // 宽限期内再跑一轮：不得按 lastResponded=10 把游标推到 11。
        nodes[1].scheduledVoteAntiEntropy();
        long[] froms = bRequests.stream()
                .map(FinalityVoteSyncCodec::decode)
                .filter(java.util.Objects::nonNull)
                .filter(FinalityVoteSyncCodec.SyncRequest::cursor)
                .mapToLong(FinalityVoteSyncCodec.SyncRequest::fromEpoch)
                .toArray();
        assertTrue(froms.length >= 2, "应有两轮分页请求，实际 " + froms.length);
        assertEquals(0L, froms[froms.length - 1],
                "标记未到时不得按链头票推进游标（老启发式必须等宽限期）");

        // 放行标记：游标精确前进到 4。
        for (byte[] marker : heldMarkers) {
            nodes[1].onVoteSyncResponse(marker);
        }
        nodes[1].scheduledVoteAntiEntropy();
        long last = bRequests.stream()
                .map(FinalityVoteSyncCodec::decode)
                .filter(java.util.Objects::nonNull)
                .filter(FinalityVoteSyncCodec.SyncRequest::cursor)
                .mapToLong(FinalityVoteSyncCodec.SyncRequest::fromEpoch)
                .reduce((a, b) -> b)
                .orElse(-1L);
        assertEquals(4L, last, "标记到达后游标必须是 servedTo+1 = 4");
    }

    /**
     * PLAN-057 第 9 处回归（第 14 例）：**margin（cursor=false）响应不得携带 0x5C 标记**。
     *
     * <p>第六轮测试床实证：margin 响应服务的是"最新 N 条"，响应端曾对所有请求
     * 一律回带标记，而请求方仅按 {@code requestFrom} 匹配、无法区分这条标记
     * 来自游标响应还是 margin 响应。对端（冷却期）发起 margin 请求 {@code from=0}
     * ⇒ 响应 latest-200（实际 49..200）附带标记 ⇒ 相邻节点从 0 起的游标轮
     * {@code requestFrom=0} 直接命中 ⇒ 游标被拽到 201 ⇒ 51..200 中段永远不再请求。
     * 恢复后的节点最终 <b>未追平</b>（670s 差 358 票）。</p>
     */
    @Test
    void legacyResponseMustNotEmitMarker() {
        for (long epoch = 1; epoch <= 5; epoch++) {
            gadgetA.submitVote(vote("v1", epoch, CP1));
        }
        List<byte[]> sent = new CopyOnWriteArrayList<>();
        FinalityVoteBroadcaster responder = newNode(gadgetA, capturingPeer(sent));

        // margin 模式（cursor=false）请求：只应回票，不应回标记。
        responder.onVoteReceived(FinalityVoteSyncCodec.encode(0L, 3, false));

        assertEquals(3, sent.stream()
                        .filter(p -> !FinalityVoteSyncCodec.isResponsePayload(p)).count(),
                "margin 响应应按既有行为回 3 张票");
        assertTrue(sent.stream().noneMatch(FinalityVoteSyncCodec::isResponsePayload),
                "margin（cursor=false）响应不得携带 0x5C 标记——请求方按 requestFrom "
                        + "匹配时无法区分标记来自游标还是 margin 响应，会把游标拽到链头");
    }

    /**
     * PLAN-057 第 9 处回归（第 15 例）：**游标轮只响应自己那一轮的标记**，
     * 且同来自其他节点（broadcast 全网可见）但 requestFrom 相同的游标标记，
     * 在响应数据一致时不会带来回退（单调守卫）。
     */
    @Test
    void foreignMarkersMustNotPoisonCursorRound() {
        for (long epoch = 1; epoch <= 5; epoch++) {
            gadgetA.submitVote(vote("v1", epoch, CP1));
        }

        FinalityVoteBroadcaster[] nodes = new FinalityVoteBroadcaster[2];
        final List<byte[]> bRequests = new CopyOnWriteArrayList<>();
        nodes[0] = newNode(gadgetA, relayingPeer(p -> nodes[1].onVoteReceived(p)));
        nodes[1] = newNode(gadgetB, relayingPeer(p -> {
            if (FinalityVoteSyncCodec.isSyncPayload(p)) {
                bRequests.add(p);
            }
            nodes[0].onVoteReceived(p);
        }));
        nodes[1].setAntiEntropyPagingEnabled(true);
        nodes[1].setAntiEntropyPagingGraceMs(5000L);

        // B 第一轮：从 0 起。A 升序服务 1..5 + 游标标记。
        nodes[1].scheduledVoteAntiEntropy();
        assertEquals(5, allVotes(storeB).size(), "第一轮应补齐 1..5");

        // 直击要害：伪造一条"margin 响应形态"的标记（served 49..200-style 的
        // 大幅越界范围），对 requestFrom=0 的当前轮尝试拽游标。
        // 注：修法是让响应端不再为 margin 请求发标记；
        // 处理端必须对 requestFrom 之外的标记严格单调（不退、不跳）。
        nodes[1].onVoteSyncResponse(
                FinalityVoteSyncCodec.encodeResponse(0L, 49L, 200L, 200));

        // 真实标记已经来自 A（1..5），所以下一轮必须从 6 开始，而不是 201。
        nodes[1].scheduledVoteAntiEntropy();
        long lastFrom = bRequests.stream()
                .map(FinalityVoteSyncCodec::decode)
                .filter(java.util.Objects::nonNull)
                .filter(FinalityVoteSyncCodec.SyncRequest::cursor)
                .mapToLong(FinalityVoteSyncCodec.SyncRequest::fromEpoch)
                .reduce((a, b) -> b)
                .orElse(-1L);
        assertEquals(6L, lastFrom,
                "伪造标记不得把游标拽出服务范围：real response only covered 1..5");
    }

    /**
     * PLAN-057 第 10 处回归（第 16 例）：<b>老对端（不发标记）的启发式回退仍需可用</b>。
     *
     * <p>第 13 处给启发式加了"本轮必须收到页级别量（>= maxVotes/2）"门榢，
     * 因此本例的头材必须模拟“对端回了一页大约 N 票、但没标记”——
     * 老节点不识 cursor 键，按旧路径服务（大约 N 票）。</p>
     */
    @Test
    void heuristicFallbackStillServesLegacyPeerWithPageSizedBatch() throws Exception {
        // A 有 150 票（远大于 maxVotes/2 = 100）。
        for (long epoch = 1; epoch <= 150; epoch++) {
            gadgetA.submitVote(vote("v1", epoch, CP1));
        }

        FinalityVoteBroadcaster[] nodes = new FinalityVoteBroadcaster[2];
        final List<byte[]> heldMarkers = new CopyOnWriteArrayList<>();
        nodes[0] = newNode(gadgetA, relayingPeer(p -> {
            if (FinalityVoteSyncCodec.isResponsePayload(p)) {
                heldMarkers.add(p);   // 老对端：只回页、不回标记
                return;
            }
            nodes[1].onVoteReceived(p);
        }));
        final List<byte[]> bRequests = new CopyOnWriteArrayList<>();
        nodes[1] = newNode(gadgetB, relayingPeer(p -> {
            if (FinalityVoteSyncCodec.isSyncPayload(p)) {
                bRequests.add(p);
            }
            nodes[0].onVoteReceived(p);
        }));
        nodes[1].setAntiEntropyPagingEnabled(true);
        nodes[1].setAntiEntropyMarginEpochs(0L);
        nodes[1].setAntiEntropyPagingGraceMs(80L);

        // 第 1 轮：from=0，A 回 1..67（201 票大小的页）——超过 maxVotes/2。
        nodes[1].scheduledVoteAntiEntropy();
        // 前两个"无标记宽限 tick"：游标必须仍在 0（重传在途）。
        for (int i = 0; i < 2; i++) {
            Thread.sleep(120);
            nodes[1].scheduledVoteAntiEntropy();
            long from = lastCursorFrom(bRequests);
            assertEquals(0L, from, "还没累计到 3 个无标记 tick，不得推进");
        }
        // 第三次后：老启发式必须生效（否则老对穲节点永远补不上票）。
        Thread.sleep(120);
        nodes[1].scheduledVoteAntiEntropy();
        long from = lastCursorFrom(bRequests);
        assertTrue(from > 0L,
                "老对端（页级批量、无标记）必须在 3 个无标记 tick 后仍能推进，实际 from=" + from);
    }

    /**
     * PLAN-057 第 13 处回归（第 18 例）：<b>本轮什么都没收到时，老启发式不得拉走游标</b>。
     *
     * <p>第五轮/第十轮实测：重启后 P2P 连接未建好，前几轮只收到在线涓流
     * （2~6 票）、一页都没到；老启发式却按 {@code lastResponded+1} 把游标从 0
     * 拉到 147，1..146 整段跳过（空漏 30 多票）。</p>
     *
     * <p>不变式：无页级别量 ⇒ 游标必须坐在原地（重传），不得前进。</p>
     */
    @Test
    void noPageReceivedMustNeverDragTheCursor() throws Exception {
        for (long epoch = 1; epoch <= 30; epoch++) {
            gadgetA.submitVote(vote("v1", epoch, CP1));
        }

        FinalityVoteBroadcaster[] nodes = new FinalityVoteBroadcaster[2];
        nodes[0] = newNode(gadgetA, relayingPeer(p -> {
            if (FinalityVoteSyncCodec.isResponsePayload(p)) {
                return;   // 连接尚未建好：页与标记都不投递
            }
            nodes[1].onVoteReceived(p);
        }));
        final List<byte[]> bRequests = new CopyOnWriteArrayList<>();
        nodes[1] = newNode(gadgetB, relayingPeer(p -> {
            if (FinalityVoteSyncCodec.isSyncPayload(p)) {
                bRequests.add(p);
            }
            nodes[0].onVoteReceived(p);
        }));
        nodes[1].setAntiEntropyPagingEnabled(true);
        nodes[1].setAntiEntropyMarginEpochs(200L);
        nodes[1].setAntiEntropyPagingGraceMs(80L);

        // 第 1 轮：B 空库 ⇒ 首轮从地板 0 起。
        nodes[1].scheduledVoteAntiEntropy();
        assertEquals(0L, lastCursorFrom(bRequests), "首轮应从地板 0 起");

        // 模拟在线涓流：链头附近零星票（远小于 maxVotes/2）。
        for (int round = 2; round <= 5; round++) {
            nodes[1].onVoteReceived(FinalityVoteCodec.encode(vote("v1", 200 + round, CP1)));
            Thread.sleep(120);
            nodes[1].scheduledVoteAntiEntropy();
            assertEquals(0L, lastCursorFrom(bRequests),
                "第 13 处：未收到页级批量时游标必须留在原地（第 " + round + " 轮）");
        }
    }

    /**
     * PLAN-057 第 15 处回归（第 20 例）：**"没收到响应"不等于"对端没货"**。
     *
     * <p>第十三轮实测：E 重启后首轮 from=0 连续两个 tick 未收到任何页/标记
     * （P2P 连接尚在重建），legacy 宽限判据立刻判"对端无货"并终止爬升
     * ⇒ 整条分页退化成 margin，只能补回 < max-votes 的小缺口。</p>
     *
     * <p>修法：legacy 宽限终止与老启发式一样，必须累计满
     * {@value #MARKERLESS_GRACE_TICKS_NEEDED} 个无标记 tick 才可用；
     * 若期间响应到达，则由 0x5C 标记精确推进。</p>
     */
    @Test
    void noResponseYetMustNotBeReadAsPeerExhausted() throws Exception {
        for (long epoch = 1; epoch <= 30; epoch++) {
            gadgetA.submitVote(vote("v1", epoch, CP1));
        }

        FinalityVoteBroadcaster[] nodes = new FinalityVoteBroadcaster[2];
        // 连接重建中：A→B 方向整条不通（页与标记都不到），
        // 只有 B→A 的请求能到（否则 A 根本不会服务、也谈不上"重传"）。
        nodes[0] = newNode(gadgetA, relayingPeer(p -> { }));
        final List<byte[]> bRequests = new CopyOnWriteArrayList<>();
        nodes[1] = newNode(gadgetB, relayingPeer(p -> {
            if (FinalityVoteSyncCodec.isSyncPayload(p)) {
                bRequests.add(p);
            }
            nodes[0].onVoteReceived(p);
        }));
        nodes[1].setAntiEntropyPagingEnabled(true);
        nodes[1].setAntiEntropyMarginEpochs(0L);
        nodes[1].setAntiEntropyPagingGraceMs(80L);

        // 第 1 轮：从 0 起；对端无响应（模拟连接重建）。
        nodes[1].scheduledVoteAntiEntropy();
        assertEquals(0L, lastCursorFrom(bRequests), "首轮应从 0 起");
        // 第 2 轮（第二个宽限 tick）：仍不得终止——第二个请求必须是**原地重传的
        // 游标请求**（cursor=true, from=0）。若提前终止，冷却期会改发 margin 请求
        // （cursor=false），第二条约为此形态。
        Thread.sleep(120);
        nodes[1].scheduledVoteAntiEntropy();
        StringBuilder dbg = new StringBuilder("REQS: ");
        for (byte[] r : bRequests) {
            FinalityVoteSyncCodec.SyncRequest q = FinalityVoteSyncCodec.decode(r);
            dbg.append(q == null ? "null" : ("[from=" + q.fromEpoch() + " cursor=" + q.cursor() + "]")).append(" ");
        }
        System.out.println(dbg);
        assertTrue(bRequests.size() >= 2, "应已发出第二个请求，实际 " + bRequests.size());
        FinalityVoteSyncCodec.SyncRequest second =
                FinalityVoteSyncCodec.decode(bRequests.get(1));
        assertNotNull(second, "第二个请求必须可解码");
        assertTrue(second.cursor(),
                "第 15 处：第二个请求必须仍是游标请求（原地重传），实际 = " + second);
        assertEquals(0L, second.fromEpoch(),
                "第 15 处：第二个请求必须仍从 0 起（未终止 ⇒ 不换起点）");

        // 第 3 轮起：才允许 legacy 宽限终止（老对端兼容仍在）。
        for (int i = 0; i < 4; i++) {
            Thread.sleep(120);
            nodes[1].scheduledVoteAntiEntropy();
        }
    }


    // ---------- helpers ----------

    /**
     * PLAN-057 第 11 处回归（第 17 例）：**页满时必须原地重问，不能按 epoch 跳过**。
     *
     * <p>第八轮测试床实证（1/1026 票缺口）：A 有 68..134 共 201 票，
     * 响应 {@code LIMIT 200} ⇒ 第 201 票（validator nv3gC5.. 的 epoch 134）
     * 被截断在页外；而标记只报 {@code servedTo=134}（页内最大 epoch），
     * 请求方按 {@code servedTo+1=135} 前进 ⇒ 那一票永远不再被请求。</p>
     *
     * <p>修法：页满（count == maxVotes）时下一轮仍从 {@code servedTo} 起，
     * 让对端把该 epoch 剩下的行吐出来（重复票由接收端幂等去重）；只有短页
     * 才越过。本测试把 max-votes 压到 2，A 有 3 票（epoch1 x2 + epoch2 x1）：</p>
     *
     * <ul>
     *   <li>第 1 轮 from=0：页=[e1,e1] 满 ⇒ 下一轮必须仍是 1（不是 2）；</li>
     *   <li>第 2 轮 from=1：页=[e1,e2] 满 ⇒ 下一轮必须仍是 2；</li>
     *   <li>第 3 轮 from=2：页=[e2] 短页 ⇒ 越过到 3；</li>
     * </ul>
     */
    @Test
    void fullPageMustResumeInclusivelyAtSameEpoch() {
        // 注：同 (validator, epoch, checkpoint) 会被库里去重，要造出"epoch1 三张"
        // 必须换不同检查点。
        gadgetA.submitVote(vote("v1", 1, new byte[]{17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17, 17}));
        gadgetA.submitVote(vote("v1", 1, new byte[]{18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18}));
        gadgetA.submitVote(vote("v1", 1, new byte[]{19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19}));
        gadgetA.submitVote(vote("v1", 2, CP1));

        FinalityVoteBroadcaster[] nodes = new FinalityVoteBroadcaster[2];
        final List<byte[]> bRequests = new CopyOnWriteArrayList<>();
        nodes[0] = newNode(gadgetA, relayingPeer(p -> nodes[1].onVoteReceived(p)));
        nodes[1] = newNode(gadgetB, relayingPeer(p -> {
            if (FinalityVoteSyncCodec.isSyncPayload(p)) {
                bRequests.add(p);
            }
            nodes[0].onVoteReceived(p);
        }));
        nodes[1].setAntiEntropyPagingEnabled(true);
        nodes[1].setAntiEntropyMarginEpochs(0L);   // 地板 = 0

        // 上限 2，A 有 epoch1 x3 + epoch2 x1：旧 LIMIT 截断会把 epoch1 的第 3 张
        // 切在页外；新实现按 epoch 完整交付 ⇒ 第 1 页=epoch1 全 3 张（虽超上限），
        // 第 2 页=epoch2。
        for (int r = 1; r <= 2; r++) {
            nodes[1].scheduledVoteAntiEntropy();
        }

        long[] froms = bRequests.stream()
                .map(FinalityVoteSyncCodec::decode)
                .filter(java.util.Objects::nonNull)
                .filter(FinalityVoteSyncCodec.SyncRequest::cursor)
                .mapToLong(FinalityVoteSyncCodec.SyncRequest::fromEpoch)
                .toArray();
        assertEquals(2, froms.length, "两轮即应补完全部 4 张（按 epoch 完整交付）");
        assertEquals(0L, froms[0], "首轮从地板 0 起");
        // 第 11 处：哨兵行（epoch 2）所在 epoch 也整体带上 ⇒ 第 1 页
        // = epoch1 全 3 张 + epoch2 共 4 张，servedTo=2 ⇒ 下一轮从 3 起。
        // 旧行为（纯 LIMIT）只回 2 张并把 e1c 永久留在库里。
        assertEquals(3L, froms[1],
                "第 11 处：第 1 页必须整体覆盖哨兵 epoch(2) ⇒ 第 2 轮从 3 起；若只回 2 张并跳到 2，第 3 张已永久丢失");
        List<Vote> got = allVotes(storeB);
        assertEquals(3, got.stream().filter(v -> v.getEpoch() == 1).count(),
                "epoch 1 的三张票必须全部补齐（第 11 处的收益点）");
        assertEquals(1, got.stream().filter(v -> v.getEpoch() == 2).count());
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

    /** \u6700\u540e\u4e00\u6761\u6e38\u6807\uff08cursor=true\uff09\u8bf7\u6c42\u7684 from\uff1b\u65e0\u5219 -1\u3002 */
    /**
     * PLAN-057 第 13 处回归（第 19 例）：<b>0x5C 响应标记必须与页同消息</b>。
     *
     * <p>第 9/10 轮实测豁山：页与标记曾是两条广播，可各走各的连接
     * ⇒ "页到、标记没到" 结构上无法避免；请求方因无标记而走老启发式，
     * 把游标从 0 拉到 147，1..146 整段跳过。</p>
     *
     * <p>本例直接抓 P2P 消息：响应必须只发<b>一条</b> Transactions，
     * 且末条交星就是那个 0x5C 魔数的标记。</p>
     */
    @Test
    void markerMustTravelInTheSameMessageAsThePage() {
        for (long epoch = 1; epoch <= 3; epoch++) {
            gadgetA.submitVote(vote("v1", epoch, CP1));
        }

        final List<NexusChainOuterClass.Transactions> msgs = new CopyOnWriteArrayList<>();
        PeerServer peerServer = mock(PeerServer.class);
        doAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            if (arg instanceof NexusChainOuterClass.Transactions txs) {
                msgs.add(txs);
            }
            return null;
        }).when(peerServer).broadcast(any());

        FinalityVoteBroadcaster responder = new FinalityVoteBroadcaster(
                gadgetA, event -> { }, peerServer);

        responder.onVoteReceived(FinalityVoteSyncCodec.encode(0L, 3, true));

        assertEquals(1, msgs.size(),
                "第 13 处：页与响应标记必须同消息（只发 1 条），实际 " + msgs.size());
        NexusChainOuterClass.Transactions msg = msgs.get(0);
        assertEquals(4, msg.getTransactionsCount(), "3 票 + 1 标记");
        byte[] last = msg.getTransactions(msg.getTransactionsCount() - 1).getPayload().toByteArray();
        assertTrue(FinalityVoteSyncCodec.isResponsePayload(last),
                "第 13 处：末条交星必须是 0x5C 响应标记");
        FinalityVoteSyncCodec.SyncResponse marker = FinalityVoteSyncCodec.decodeResponse(last);
        assertNotNull(marker);
        assertEquals(3, marker.count(), "标记必须报实服务 3 票");
    }

    private static long lastCursorFrom(List<byte[]> requests) {
        return requests.stream()
                .map(FinalityVoteSyncCodec::decode)
                .filter(java.util.Objects::nonNull)
                .filter(FinalityVoteSyncCodec.SyncRequest::cursor)
                .mapToLong(FinalityVoteSyncCodec.SyncRequest::fromEpoch)
                .reduce((a, b) -> b)
                .orElse(-1L);
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
