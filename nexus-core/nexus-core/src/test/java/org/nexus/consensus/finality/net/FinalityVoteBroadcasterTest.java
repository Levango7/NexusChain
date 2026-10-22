package org.nexus.consensus.finality.net;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.consensus.finality.Vote;
import org.nexus.consensus.finality.persistence.FinalityStateStore;
import org.nexus.consensus.finality.persistence.InMemoryFinalityStateStore;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.consensus.pos.StakingService;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FinalityVoteBroadcasterTest {

    private ValidatorRegistry registry;
    private StakingService staking;
    private FinalityGadget gadget;
    private FinalityVoteBroadcaster broadcaster;
    private List<FinalityVoteBroadcaster.FinalityVoteBroadcastEvent> events;
    private ApplicationEventPublisher publisher;

    private static final byte[] CP1 = new byte[]{1, 2, 3};

    /** P0-1 修复后：v1 注册真实 Ed25519 公钥，绑定校验要求投票公钥与之一致。 */
    private org.nexus.crypto.ed25519.Ed25519KeyPair v1Keys;

    @BeforeEach
    void setUp() {
        registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        staking = new StakingServiceImpl();
        try {
            var f = StakingServiceImpl.class.getDeclaredField("validatorRegistry");
            f.setAccessible(true);
            f.set(staking, registry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        v1Keys = org.nexus.crypto.ed25519.Ed25519.generateKeyPair();
        registry.register("v1", org.apache.commons.codec.binary.Hex.encodeHexString(
                v1Keys.getPublicKey().getEncoded()), new BigDecimal("300"), 0.1);
        Validator v = registry.getValidator("v1");
        v.setStatus(ValidatorStatus.ACTIVE);
        staking.stake("v1", new BigDecimal("300"));

        gadget = new FinalityGadget(registry, staking);
        events = new ArrayList<>();
        publisher = new ApplicationEventPublisher() {
            @Override
            public void publishEvent(Object event) {
                if (event instanceof FinalityVoteBroadcaster.FinalityVoteBroadcastEvent) {
                    events.add((FinalityVoteBroadcaster.FinalityVoteBroadcastEvent) event);
                }
            }
            @Override
            public void publishEvent(org.springframework.context.ApplicationEvent event) {
                publishEvent((Object) event);
            }
        };
        broadcaster = new FinalityVoteBroadcaster(gadget, publisher);
    }

    /** 构造携带 v1 真实公钥与 Ed25519 签名的投票（进入 gadget 计票路径时必需）。 */
    private Vote signedVote(long epoch, byte[] checkpoint) {
        byte[] pub = v1Keys.getPublicKey().getEncoded();
        Vote unsigned = new Vote(epoch, checkpoint, "v1", new byte[0], pub);
        try {
            byte[] sig = v1Keys.getPrivateKey().sign(unsigned.signingPayload());
            return new Vote(epoch, checkpoint, "v1", sig, pub);
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 signing failed", e);
        }
    }

    @Test
    void codecRoundTrip() {
        Vote vote = new Vote(7, CP1, "validator-A", new byte[]{0x01, 0x02});
        byte[] payload = FinalityVoteCodec.encode(vote);
        assertEquals(FinalityVoteCodec.MAGIC, payload[0]);

        Vote decoded = FinalityVoteCodec.decode(payload);
        assertNotNull(decoded);
        assertEquals(vote.getEpoch(), decoded.getEpoch());
        assertArrayEquals(vote.getCheckpointHash(), decoded.getCheckpointHash());
        assertEquals(vote.getValidatorAddress(), decoded.getValidatorAddress());
        assertArrayEquals(vote.getSignature(), decoded.getSignature());
    }

    @Test
    void codecRejectsMalformed() {
        assertNull(FinalityVoteCodec.decode(null));
        assertNull(FinalityVoteCodec.decode(new byte[0]));
        assertNull(FinalityVoteCodec.decode(new byte[]{0x00, 0x01}));
    }

    @Test
    void broadcastEmitsEvent() {
        broadcaster.broadcast(new Vote(1, CP1, "v1", new byte[]{0x55}));
        assertEquals(1, events.size());
        assertEquals(1, events.get(0).getVote().getEpoch());
    }

    @Test
    void onVoteReceivedSubmitsToGadget() {
        // 外部收到一票后应进入 FinalityGadget（权重累积）
        // P0-1 修复后：投票需通过公钥绑定校验，使用 v1 的真实公钥与签名
        Vote external = signedVote(1, CP1);
        broadcaster.onVoteReceived(FinalityVoteCodec.encode(external));

        org.nexus.consensus.finality.FinalityRecord rec = gadget.getFinality(1, CP1);
        assertEquals(new BigDecimal("300"), rec.getVotedWeight());
    }

    @Test
    void broadcasterListenerInterfaceIsInvoked() {
        AtomicInteger outgoing = new AtomicInteger();
        broadcaster.addListener(new FinalityVoteBroadcaster.VoteListener() {
            @Override
            public void onOutgoingVote(Vote vote, byte[] payload) {
                outgoing.incrementAndGet();
            }
        });
        broadcaster.broadcast(new Vote(1, CP1, "v1", new byte[]{0x01}));
        assertEquals(1, outgoing.get());
    }

    @Test
    void receivedVotePersistenceFailureDoesNotThrow() {
        FailingStore store = new FailingStore();
        FinalityGadget failingGadget = new FinalityGadget(registry, staking, store);
        FinalityVoteBroadcaster failingBroadcaster = new FinalityVoteBroadcaster(failingGadget, publisher);

        assertDoesNotThrow(() -> failingBroadcaster.onVoteReceived(
                        FinalityVoteCodec.encode(signedVote(1, CP1))),
                "接收侧写库失败不能拖垮 P2P 消息处理线程");
        assertEquals(0, failingGadget.getFinality(1, CP1).getVotedWeight().signum());
    }

    @Test
    void receivedVoteIsQueuedForLocalRetryInsteadOfBeingDropped() {
        // PLAN-018：接收侧写库失败时，票必须进入本地的补写队列，
        // 而不是"记一条 WARN 就蒸发"（旧行为下发送方并不会重发，这张票会永久丢失）。
        FailingStore store = new FailingStore();
        FinalityGadget failingGadget = new FinalityGadget(registry, staking, store);
        org.nexus.consensus.finality.FinalityOutboxManager outbox =
                new org.nexus.consensus.finality.FinalityOutboxManager(failingGadget);
        FinalityVoteBroadcaster broadcasterWithOutbox =
                new FinalityVoteBroadcaster(failingGadget, publisher, null, outbox);

        broadcasterWithOutbox.onVoteReceived(FinalityVoteCodec.encode(signedVote(1, CP1)));

        assertEquals(1, outbox.pendingCount(
                        org.nexus.consensus.finality.persistence.FinalityVoteOrigin.RECEIVED),
                "接收侧写库失败的票必须排队等待本地补写");
    }

    /** 仅让 recordVote 失败，其余语义委托内存实现。 */
    private static final class FailingStore implements FinalityStateStore {
        private final FinalityStateStore delegate = new InMemoryFinalityStateStore();

        @Override
        public void recordVote(long epoch, byte[] checkpointHash, String validatorAddress) {
            throw new IllegalStateException("simulated JDBC outage");
        }

        @Override
        public void markFinalized(long epoch, byte[] checkpointHash) {
            delegate.markFinalized(epoch, checkpointHash);
        }

        @Override
        public boolean isFinalized(long epoch, byte[] checkpointHash) {
            return delegate.isFinalized(epoch, checkpointHash);
        }

        @Override
        public Set<String> loadVoters(long epoch, byte[] checkpointHash) {
            return delegate.loadVoters(epoch, checkpointHash);
        }

        @Override
        public Map<String, Boolean> loadAllFinalized() {
            return delegate.loadAllFinalized();
        }

        @Override
        public Map<String, Set<String>> loadAllVotes() {
            return delegate.loadAllVotes();
        }
    }
}
