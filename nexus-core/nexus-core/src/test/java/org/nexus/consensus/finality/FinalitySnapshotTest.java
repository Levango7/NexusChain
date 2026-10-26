package org.nexus.consensus.finality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.persistence.InMemoryFinalityStateStore;
import org.nexus.consensus.pos.StakingService;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code FinalityCoordinator.finalitySnapshot} 测试（PLAN-044）。
 *
 * <p>锁定：① 无任何投票时 {@code latestVotedEpoch/progress} 为 null（不冒充 0%）；
 * ② 只投自己 → {@code finalized=false, progressPercent=50}；③ 收齐两票 →
 * {@code finalized=true,100%}（证明快照随对端票增长，即 PLAN-043 实测的
 * "提交时快照顺序浮动"可被该端点观测）；④ <b>链高不在检查点时仍报最近有票
 * 的 epoch</b>（PLAN-044 首轮实测修正点：只看当前 epoch 会连续 no-progress）；
 * ⑤ {@code finalityLag} 表达最终性落后几个 epoch；⑥ {@code finalizedCheckpoints}
 * 随最终化递增；⑦ 快照不触碰持久化 store。</p>
 */
class FinalitySnapshotTest {

    private static final byte[] CP = new byte[]{7, 7, 7, 7};

    private ValidatorRegistry registry;
    private StakingService staking;
    private FinalityGadget gadget;
    private FinalityCoordinator coordinator;
    private final Map<String, Ed25519KeyPair> keys = new HashMap<>();

    @BeforeEach
    void setUp() {
        registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        staking = new StakingServiceImpl();
        try {
            var field = StakingServiceImpl.class.getDeclaredField("validatorRegistry");
            field.setAccessible(true);
            field.set(staking, registry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        addValidator("v1", 300);
        addValidator("v2", 300);
        gadget = new FinalityGadget(registry, staking, new InMemoryFinalityStateStore());
        gadget.setSignatureAggregator(passThroughAggregator());
        coordinator = new FinalityCoordinator(gadget, registry, 2L);
        coordinator.setSelfValidatorAddress("v1");
    }

    private SignatureAggregator passThroughAggregator() {
        return new SignatureAggregator() {
            @Override
            public AggregatedSignature aggregate(java.util.List<Vote> votes) {
                final int count = votes == null ? 0 : votes.size();
                return new AggregatedSignature() {
                    @Override public byte[] compressed() { return new byte[0]; }
                    @Override public int signerCount() { return count; }
                };
            }

            @Override
            public boolean verifyAggregate(java.util.List<Vote> votes, AggregatedSignature aggregated) {
                return true;
            }
        };
    }

    private void addValidator(String address, int stake) {
        Ed25519KeyPair keyPair = Ed25519.generateKeyPair();
        keys.put(address, keyPair);
        registry.register(address, org.apache.commons.codec.binary.Hex.encodeHexString(
                keyPair.getPublicKey().getEncoded()), new BigDecimal(stake), 0.1);
        Validator validator = registry.getValidator(address);
        validator.setStatus(ValidatorStatus.ACTIVE);
        staking.stake(address, new BigDecimal(stake));
    }

    private Vote vote(String validator, long epoch) {
        Ed25519KeyPair keyPair = keys.get(validator);
        byte[] publicKey = keyPair.getPublicKey().getEncoded();
        Vote unsigned = new Vote(epoch, CP, validator, new byte[0], publicKey);
        byte[] signature = keyPair.getPrivateKey().sign(unsigned.signingPayload());
        return new Vote(epoch, CP, validator, signature, publicKey);
    }

    @Test
    void snapshotWithoutAnyVoteHasNullProgressNotZero() {
        Map<String, Object> atCheckpoint = coordinator.finalitySnapshot(4L);
        assertEquals(4L, atCheckpoint.get("height"));
        assertEquals(2L, atCheckpoint.get("epochLength"));
        assertEquals(true, atCheckpoint.get("checkpoint"));
        assertEquals(2L, atCheckpoint.get("currentEpoch"));
        assertNull(atCheckpoint.get("latestVotedEpoch"), "无任何投票时不得报出 epoch");
        assertNull(atCheckpoint.get("progress"), "无投票时 progress 必须为 null（不冒充 0%）");
        assertNull(atCheckpoint.get("finalityLag"));
    }

    @Test
    void nonCheckpointHeightStillReportsMetadata() {
        Map<String, Object> snapshot = coordinator.finalitySnapshot(5L);
        assertEquals(false, snapshot.get("checkpoint"));
        assertEquals(3L, snapshot.get("currentEpoch"));
        assertNull(snapshot.get("progress"));
    }

    @Test
    void ownVoteShowsFiftyPercentThenPeerVoteFinalizesAtCheckpoint() {
        gadget.submitVote(vote("v1", 2L));

        Map<String, Object> afterSelf = coordinator.finalitySnapshot(4L);
        Map<?, ?> self = (Map<?, ?>) afterSelf.get("progress");
        assertNotNull(self);
        assertEquals(2L, afterSelf.get("latestVotedEpoch"));
        assertEquals(0L, afterSelf.get("finalityLag"));
        assertFalse((Boolean) self.get("finalized"));
        assertEquals(50, self.get("progressPercent"));

        gadget.submitVote(vote("v2", 2L));

        Map<String, Object> afterPeer = coordinator.finalitySnapshot(4L);
        Map<?, ?> peer = (Map<?, ?>) afterPeer.get("progress");
        assertTrue((Boolean) peer.get("finalized"));
        assertEquals(100, peer.get("progressPercent"));
        assertNotNull(peer.get("checkpointHash"));
        assertEquals(1, afterPeer.get("finalizedCheckpoints"));
    }

    @Test
    void offCheckpointHeightStillShowsLatestVotedEpochWithLag() {
        // PLAN-044 首轮实测的修正点：链高 5（epoch 3）不在检查点上，但 epoch 2 已有票
        gadget.submitVote(vote("v1", 2L));
        gadget.submitVote(vote("v2", 2L));

        Map<String, Object> snapshot = coordinator.finalitySnapshot(5L);
        assertEquals(3L, snapshot.get("currentEpoch"));
        assertEquals(false, snapshot.get("checkpoint"));
        assertEquals(2L, snapshot.get("latestVotedEpoch"));
        assertEquals(1L, snapshot.get("finalityLag"), "最终性落后 1 个 epoch");
        Map<?, ?> progress = (Map<?, ?>) snapshot.get("progress");
        assertTrue((Boolean) progress.get("finalized"));
        assertEquals(100, progress.get("progressPercent"));
    }

    @Test
    void finalizedCheckpointsGrowWithEpochs() {
        gadget.submitVote(vote("v1", 2L));
        gadget.submitVote(vote("v2", 2L));
        // height 8 ⇒ currentEpoch 4；给 epoch 4 投齐两票后快照应指向 epoch 4
        gadget.submitVote(vote("v1", 4L));
        gadget.submitVote(vote("v2", 4L));
        Map<String, Object> snapshot = coordinator.finalitySnapshot(8L);
        assertEquals(4L, snapshot.get("currentEpoch"));
        assertEquals(4L, snapshot.get("latestVotedEpoch"));
        assertEquals(0L, snapshot.get("finalityLag"));
        assertEquals(2, snapshot.get("finalizedCheckpoints"));
        Map<?, ?> progress = (Map<?, ?>) snapshot.get("progress");
        assertNotNull(progress);
        assertTrue((Boolean) progress.get("finalized"));
        assertEquals(100, progress.get("progressPercent"));
    }

    @Test
    void snapshotDoesNotTouchPersistentStore() {
        InMemoryFinalityStateStore store = new InMemoryFinalityStateStore();
        FinalityGadget observed = new FinalityGadget(registry, staking, store);
        observed.setSignatureAggregator(passThroughAggregator());
        FinalityCoordinator local = new FinalityCoordinator(observed, registry, 2L);
        local.setSelfValidatorAddress("v1");
        observed.submitVote(vote("v1", 2L));
        int before = store.loadFinalizedKeysSince(0).size() + store.loadVoteObjectsSince(0).size();

        local.finalitySnapshot(5L);

        assertEquals(before, store.loadFinalizedKeysSince(0).size() + store.loadVoteObjectsSince(0).size(),
                "finalitySnapshot 必须是纯内存读");
    }
}
