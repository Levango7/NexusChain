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
 * PLAN-025：独立存储拓扑下的补票（anti-entropy）。
 *
 * <p>要修的缺口：投票传播是<b>广播</b>语义，只保证在线节点收到。共享存储时
 * 掉线节点能靠 PLAN-021 的"库拉取"收敛；<b>独立存储</b>时对端的票从来不在
 * 本节点库里 ⇒ 广播错过的票永久缺失，最终性视图永远不收敛。</p>
 *
 * <p>本测试用两个<b>各自独立的内存库</b>模拟独立存储节点，中间用 mock
 * {@link PeerServer} 搭一条回环总线（把 broadcast 出来的载荷投递给对端），
 * 验证三件事：①请求能发出去；②对端能把已签名的票回推；③冷启动节点补到票后
 * <b>本地补最终化</b>，且重复补票不会让本地票列表膨胀。</p>
 */
class FinalityVoteBroadcasterAntiEntropyTest {

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
        addValidator("v2", 1000);
        addValidator("v3", 1000);

        storeA = new InMemoryFinalityStateStore();
        storeB = new InMemoryFinalityStateStore();
        gadgetA = new FinalityGadget(registry, staking, storeA);
        gadgetB = new FinalityGadget(registry, staking, storeB);
    }

    @Test
    void requestCarriesSyncMagicAndRequestedWindow() {
        List<byte[]> sent = new CopyOnWriteArrayList<>();
        FinalityVoteBroadcaster broadcaster = newNode(gadgetA, capturingPeer(sent));

        broadcaster.requestVoteSync(17L, 5);

        assertEquals(1, sent.size(), "一次请求应只发一条 P2P 载荷");
        assertTrue(FinalityVoteSyncCodec.isSyncPayload(sent.get(0)),
                "补票请求必须带 0x5B 魔数，接收端据此与投票分流");
        FinalityVoteSyncCodec.SyncRequest req = FinalityVoteSyncCodec.decode(sent.get(0));
        assertNotNull(req);
        assertEquals(17L, req.fromEpoch());
        assertEquals(5, req.maxVotes());
    }

    @Test
    void responderServesStoredSignedVotes() {
        gadgetA.submitVote(vote("v1", 1, CP1));
        gadgetA.submitVote(vote("v2", 1, CP1));
        gadgetA.submitVote(vote("v3", 1, CP1));

        List<byte[]> sent = new CopyOnWriteArrayList<>();
        FinalityVoteBroadcaster broadcaster = newNode(gadgetA, capturingPeer(sent));

        broadcaster.onVoteReceived(FinalityVoteSyncCodec.encode(0L, 10));

        // PLAN-057 第 8 处：响应端在回推票之后会额外发一条 0x5C 响应标记，
        // 计数与解码都必须把它排除（它不是票）。
        List<byte[]> votesSent = sent.stream()
                .filter(p -> !FinalityVoteSyncCodec.isResponsePayload(p))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(3, votesSent.size(), "应答应把 epoch>=0 的 3 张票全部回推");
        votesSent.forEach(p -> {
            Vote v = FinalityVoteCodec.decode(p);
            assertNotNull(v, "回推的载荷必须能被投票 codec 解码");
            assertTrue(v.getSignature() != null && v.getSignature().length > 0,
                    "回推的票必须带签名（否则接收端无法做聚合验签）");
        });
    }

    @Test
    void requesterIsCappedByMaxVotes() {
        for (long epoch = 1; epoch <= 5; epoch++) {
            gadgetA.submitVote(vote("v1", epoch, CP1));
        }
        List<byte[]> sent = new CopyOnWriteArrayList<>();
        FinalityVoteBroadcaster broadcaster = newNode(gadgetA, capturingPeer(sent));

        broadcaster.onVoteReceived(FinalityVoteSyncCodec.encode(0L, 2));

        long voteCount = sent.stream()
                .filter(p -> !FinalityVoteSyncCodec.isResponsePayload(p))
                .count();
        assertEquals(2, voteCount, "max=2 时只回推 2 张票（防止一次拉回全量历史）");
    }

    @Test
    void coldStartNodeCatchesUpAndFinalizesLocally() {
        // A 收到三票并已本地最终化（独立库）。
        gadgetA.submitVote(vote("v1", 1, CP1));
        gadgetA.submitVote(vote("v2", 1, CP1));
        gadgetA.submitVote(vote("v3", 1, CP1));
        assertTrue(gadgetA.isFinalized(1, CP1));

        // B 是完全空的冷启动节点：独立库，广播期的票一张都没有。
        assertFalse(gadgetB.isFinalized(1, CP1));
        assertEquals(0, storeB.loadAllVotes().size());

        FinalityVoteBroadcaster[] nodes = new FinalityVoteBroadcaster[2];
        nodes[0] = newNode(gadgetA, relayingPeer(payload -> nodes[1].onVoteReceived(payload)));
        nodes[1] = newNode(gadgetB, relayingPeer(payload -> nodes[0].onVoteReceived(payload)));

        // B 发起补票（冷启动 from=0）→ A 回推 → B 落自己的库并补最终化。
        nodes[1].requestVoteSync(0L, 10);

        assertEquals(new BigDecimal("3000"), gadgetB.getFinality(1, CP1).getVotedWeight(),
                "补票后 B 的权重口径应包含对端三票");
        assertTrue(gadgetB.isFinalized(1, CP1), "补到票后 B 必须能本地补最终化");
        assertTrue(storeB.isFinalized(1, CP1), "最终化事实必须落进 B 自己的库");
    }

    @Test
    void repeatedSyncDoesNotGrowLocalVoteList() {
        gadgetA.submitVote(vote("v1", 1, CP1));
        gadgetA.submitVote(vote("v2", 1, CP1));

        FinalityVoteBroadcaster[] nodes = new FinalityVoteBroadcaster[2];
        nodes[0] = newNode(gadgetA, relayingPeer(payload -> nodes[1].onVoteReceived(payload)));
        nodes[1] = newNode(gadgetB, relayingPeer(payload -> nodes[0].onVoteReceived(payload)));

        nodes[1].requestVoteSync(0L, 10);
        int afterFirst = allVotes(storeB).size();
        assertEquals(2, afterFirst);

        nodes[1].requestVoteSync(0L, 10);
        nodes[1].requestVoteSync(0L, 10);

        assertEquals(afterFirst, allVotes(storeB).size(),
                "重复补票必须按验证人去重，不得让本地 Vote 列表增长");
    }

    @Test
    void schedulerLooksBackByMarginFromLocalMaxEpoch() {
        gadgetA.submitVote(vote("v1", 100, CP1));
        gadgetA.submitVote(vote("v1", 101, CP1));

        List<byte[]> sent = new CopyOnWriteArrayList<>();
        FinalityVoteBroadcaster broadcaster = newNode(gadgetA, capturingPeer(sent));

        broadcaster.scheduledVoteAntiEntropy();

        assertEquals(1, sent.size());
        FinalityVoteSyncCodec.SyncRequest req = FinalityVoteSyncCodec.decode(sent.get(0));
        assertNotNull(req);
        assertEquals(51L, req.fromEpoch(),
                "本地最大 epoch=101、margin=50 ⇒ 从 51 起回看（101-50）");
    }

    // ---------- helpers ----------

    private FinalityVoteBroadcaster newNode(FinalityGadget gadget, PeerServer peerServer) {
        return new FinalityVoteBroadcaster(gadget, event -> { }, peerServer);
    }

    /** 记录每次 broadcast 展开后的每条载荷。 */
    private PeerServer capturingPeer(List<byte[]> sink) {
        return relayingPeer(sink::add);
    }

    /** mock PeerServer：把 Transactions 里的每个交易载荷交给 deliver（模拟网络投递）。 */
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
