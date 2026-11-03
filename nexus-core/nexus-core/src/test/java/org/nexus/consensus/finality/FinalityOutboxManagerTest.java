package org.nexus.consensus.finality;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.persistence.FinalityOutboxEntry;
import org.nexus.consensus.finality.persistence.FinalityStateStore;
import org.nexus.consensus.finality.persistence.FinalityVoteOrigin;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PLAN-018 Phase 6C 第二切片：最终性投票的持久化 outbox 与接收侧独立重试。
 *
 * <p>覆盖三条承诺：</p>
 * <ol>
 *   <li>写库失败的票进队列，库恢复后自动补写（本地票）；</li>
 *   <li>写库失败的**远端**票由接收方自己补写（不再等发送方重发）；</li>
 *   <li>进程重启后，持久化 outbox 里的"写库意图"仍能被捞回来补写。</li>
 * </ol>
 */
class FinalityOutboxManagerTest {

    private static final byte[] CP1 = new byte[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};

    private ValidatorRegistry registry;
    private StakingService staking;
    private FaultyStore store;
    private FinalityGadget gadget;
    private final Map<String, Ed25519KeyPair> validatorKeys = new HashMap<>();

    @BeforeEach
    void setUp() {
        validatorKeys.clear();
        registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        staking = new StakingServiceImpl();
        injectRegistryIntoStaking(staking, registry);
        addValidator("v1", 300);
        addValidator("v2", 300);
        addValidator("v3", 300);
        store = new FaultyStore();
        gadget = newGadget(store);
    }

    private FinalityGadget newGadget(FinalityStateStore stateStore) {
        FinalityGadget g = new FinalityGadget(registry, staking, stateStore);
        g.setSignatureAggregator(passThroughAggregator());
        return g;
    }

    private SignatureAggregator passThroughAggregator() {
        return new SignatureAggregator() {
            @Override
            public AggregatedSignature aggregate(List<Vote> votes) {
                final int count = votes == null ? 0 : votes.size();
                return new AggregatedSignature() {
                    @Override public byte[] compressed() { return new byte[0]; }
                    @Override public int signerCount() { return count; }
                };
            }

            @Override
            public boolean verifyAggregate(List<Vote> votes, AggregatedSignature aggregated) {
                return true;
            }
        };
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

    @Test
    void localVoteIsQueuedWhileDatabaseFailsAndFlushedAfterRecovery() {
        FinalityOutboxManager manager = new FinalityOutboxManager(gadget);
        Vote vote = vote("v1", 1, CP1);

        store.failRecordVote = true;
        manager.enqueueLocal(vote, new IllegalStateException("simulated JDBC outage"));

        assertEquals(1, manager.pendingCount(FinalityVoteOrigin.LOCAL));
        // 写库意图已落进持久化 outbox（数据库此刻只挂"写投票"这一路，outbox 写入本身可用）
        assertEquals(1, store.loadPendingVotes(10).size());

        store.failRecordVote = false;
        manager.flush();

        assertEquals(0, manager.pendingCount(FinalityVoteOrigin.LOCAL));
        assertEquals(0, store.loadPendingVotes(10).size(), "补写成功后必须清掉 outbox 行");
        assertTrue(store.loadVoters(1, CP1).contains("v1"), "补写后投票必须真的落进 finality_votes");
    }

    @Test
    void receivedVoteIsRetriedLocallyWithoutSenderResending() {
        FinalityOutboxManager manager = new FinalityOutboxManager(gadget);
        Vote remote = vote("v2", 1, CP1);

        // 接收侧：写库失败
        store.failRecordVote = true;
        manager.enqueueReceived(remote, new IllegalStateException("simulated JDBC outage on receiver"));
        assertEquals(1, manager.pendingCount(FinalityVoteOrigin.RECEIVED));
        assertFalse(store.loadVoters(1, CP1).contains("v2"), "此刻还没落库");

        // 接收方自己补写（不依赖发送方重发）
        store.failRecordVote = false;
        manager.flush();

        assertEquals(0, manager.pendingCount(FinalityVoteOrigin.RECEIVED));
        assertTrue(store.loadVoters(1, CP1).contains("v2"), "接收侧独立重试后远端票必须落库");
    }

    @Test
    void outboxRowSurvivesProcessRestartAndIsFlushedByFreshManager() {
        Vote vote = vote("v3", 1, CP1);
        // 模拟"进程在入队后崩溃"：只留下持久化 outbox 行，内存队列为空。
        store.enqueuePendingVote(new FinalityOutboxEntry(
                vote.getEpoch(), vote.getCheckpointHash(), vote.getValidatorAddress(),
                vote.getSignature(), vote.getPublicKeyBytes(), FinalityVoteOrigin.LOCAL,
                System.currentTimeMillis(), 0, "simulated crash"));
        assertEquals(1, store.loadPendingVotes(10).size());

        // 新进程：新 manager（内存队列为空），靠库里的行恢复。
        FinalityOutboxManager restarted = new FinalityOutboxManager(newGadget(store));
        assertEquals(0, restarted.pendingCount(), "新进程的内存队列应当是空的");

        restarted.flush();

        assertEquals(0, store.loadPendingVotes(10).size(), "重启后必须把 outbox 行补写掉");
        assertTrue(store.loadVoters(1, CP1).contains("v3"), "重启后必须真的补写进 finality_votes");
    }

    @Test
    void enqueueAndFlushNeverThrowWhenDatabaseIsCompletelyDown() {
        FinalityOutboxManager manager = new FinalityOutboxManager(gadget);
        store.failEverything = true;
        try {
            manager.enqueueLocal(vote("v1", 1, CP1), new IllegalStateException("db down"));
            manager.enqueueReceived(vote("v2", 1, CP1), new IllegalStateException("db down"));
            manager.flush();
            assertEquals(2, manager.pendingCount(), "数据库全挂时票必须留在内存队列里");
            FinalityOutboxStatus status = manager.status();
            assertTrue(status.isDegraded());
            assertFalse(status.isDatabaseReadable(),
                    "PLAN-073：库读不到必须显式标记，不能显示成「0 积压」");
            assertEquals(0L, status.getDatabasePending(),
                    "读不到时数值字段只是占位，语义由 databaseReadable 表达");
            assertNotNull(status.snapshot().get("lastError"));
        } finally {
            store.failEverything = false;
        }
    }

    /**
     * PLAN-073：{@code status()} 必须用精确计数，不能把 {@code LIMIT 64} 的
     * 第一批结果当积压数（旧缺陷：真实积压 100 行只报 64）。
     */
    @Test
    void statusUsesExactCountNotTheFetchBatch() {
        FinalityGadget mockGadget = mock(FinalityGadget.class);
        FinalityStateStore mockStore = mock(FinalityStateStore.class);
        when(mockGadget.getStateStore()).thenReturn(mockStore);
        when(mockStore.countPendingVotes()).thenReturn(100L);
        when(mockStore.loadPendingVotes(FinalityOutboxManager.DB_FETCH_BATCH))
                .thenReturn(java.util.Collections.nCopies(64, (FinalityOutboxEntry) null));

        FinalityOutboxManager manager = new FinalityOutboxManager(mockGadget);
        FinalityOutboxStatus status = manager.status();

        assertEquals(100L, status.getDatabasePending(),
                "必须用精确计数，不能把 LIMIT 64 当积压数（旧实现会报 64）");
        assertTrue(status.isDatabaseReadable());
    }

    /**
     * PLAN-073：队列满丢票是**永久丢失**，必须与「重试失败（还会再试）」分开计数。
     *
     * <p>旧实现把两者都加进 {@code totalFailed}，丢一张票会被成千上万次重试失败
     * 平均掉，既不可告警也不可复盘。</p>
     */
    @Test
    void queueOverflowCountsDroppedSeparatelyFromRetryFailures() {
        FinalityOutboxManager manager = new FinalityOutboxManager(gadget);
        for (int i = 0; i < FinalityOutboxManager.MAX_PENDING_PER_ORIGIN + 1; i++) {
            manager.enqueueLocal(vote("v1", i + 1L, CP1), new IllegalStateException("db down"));
        }

        assertEquals(FinalityOutboxManager.MAX_PENDING_PER_ORIGIN,
                manager.pendingCount(FinalityVoteOrigin.LOCAL), "队列必须保持上限");
        assertEquals(1L, manager.totalDropped(), "第 257 张票必须计入永久丢弃");
        assertEquals(0L, manager.totalFailed(), "丢票不是重试失败，不能混进 totalFailed");
        assertEquals(1L, manager.status().getTotalDropped(), "快照必须暴露丢票数");
    }

    @Test
    void pendingVoteIsRebroadcastAfterSuccessfulRetry() {
        FinalityOutboxManager manager = new FinalityOutboxManager(gadget);
        final java.util.List<Vote> rebroadcast = new java.util.ArrayList<>();
        manager.setLocalVotePersistedListener(rebroadcast::add);

        store.failRecordVote = true;
        manager.enqueueLocal(vote("v1", 1, CP1), new IllegalStateException("db down"));
        store.failRecordVote = false;
        manager.flush();

        assertEquals(1, rebroadcast.size(), "本地票补写成功后必须补发广播（否则对端收不到）");
    }

    /** 可切换故障的存储；委托 InMemory 实现（它已支持签名与 outbox）。 */
    private static final class FaultyStore implements FinalityStateStore {
        private final FinalityStateStore delegate = new InMemoryFinalityStateStore();
        private boolean failRecordVote;
        private boolean failEverything;

        @Override
        public void recordVote(long epoch, byte[] checkpointHash, String validatorAddress) {
            recordVote(epoch, checkpointHash, validatorAddress, null, null);
        }

        @Override
        public void recordVote(long epoch, byte[] checkpointHash, String validatorAddress,
                               byte[] signature, byte[] publicKey) {
            if (failRecordVote || failEverything) {
                throw new IllegalStateException("simulated JDBC outage while recording vote");
            }
            delegate.recordVote(epoch, checkpointHash, validatorAddress, signature, publicKey);
        }

        @Override
        public void markFinalized(long epoch, byte[] checkpointHash) {
            if (failEverything) {
                throw new IllegalStateException("simulated JDBC outage while marking finalized");
            }
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

        @Override
        public Map<String, List<Vote>> loadAllVoteObjects() {
            return delegate.loadAllVoteObjects();
        }

        @Override
        public void enqueuePendingVote(FinalityOutboxEntry entry) {
            if (failEverything) {
                throw new IllegalStateException("simulated JDBC outage while queueing vote");
            }
            delegate.enqueuePendingVote(entry);
        }

        @Override
        public List<FinalityOutboxEntry> loadPendingVotes(int limit) {
            if (failEverything) {
                throw new IllegalStateException("simulated JDBC outage while loading outbox");
            }
            return delegate.loadPendingVotes(limit);
        }

        @Override
        public void removePendingVote(FinalityOutboxEntry entry) {
            if (failEverything) {
                throw new IllegalStateException("simulated JDBC outage while cleaning outbox");
            }
            delegate.removePendingVote(entry);
        }

        @Override
        public void markPendingVoteFailure(FinalityOutboxEntry entry, String error) {
            if (failEverything) {
                throw new IllegalStateException("simulated JDBC outage while marking outbox");
            }
            delegate.markPendingVoteFailure(entry, error);
        }
    }
}
