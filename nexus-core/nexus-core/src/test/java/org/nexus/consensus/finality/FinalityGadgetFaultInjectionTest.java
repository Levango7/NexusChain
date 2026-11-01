package org.nexus.consensus.finality;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.persistence.FinalityStateStore;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-016 Phase 6B：最终性投票的故障注入测试。
 *
 * <p>覆盖生产持久化路径最容易出现的四类故障：写投票失败、写最终化标记失败、
 * 重复/延迟/乱序投票、双签顺序反转。测试目标不是模拟完整网络分区，而是先把
 * “数据库短时不可用会不会污染内存状态/丢失不可逆结算”这个安全边界钉死。</p>
 */
class FinalityGadgetFaultInjectionTest {

    private static final byte[] CP1 = new byte[]{1, 2, 3};
    private static final byte[] CP2 = new byte[]{9, 9, 9};

    private ValidatorRegistry registry;
    private StakingService staking;
    private FaultInjectingStore store;
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
        store = new FaultInjectingStore();
        gadget = newGadget(store);
    }

    private FinalityGadget newGadget(FinalityStateStore stateStore) {
        FinalityGadget g = new FinalityGadget(registry, staking, stateStore);
        // 本测试聚焦持久化顺序/重试语义，签名与公钥绑定仍由真实 Ed25519 投票覆盖；
        // 聚合验签用恒通过实现隔离，避免把两件事混在一个断言里。
        g.setSignatureAggregator(passThroughAggregator());
        return g;
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
    void recordVoteOutageDoesNotLeaveUnpersistedVoterInMemory() {
        store.failRecordVote = true;

        assertThrows(IllegalStateException.class,
                () -> gadget.submitVote(vote("v1", 1, CP1)),
                "数据库写投票失败时必须向上抛出，由调用方感知最终性未提交");

        // 旧实现先写 voters 再落库：这里会留下幽灵投票者，重启后与数据库不一致。
        assertFalse(gadget.isFinalized(1, CP1));
        assertEquals(0, gadget.getFinality(1, CP1).getVotedWeight().signum());
        assertTrue(store.loadVoters(1, CP1).isEmpty(), "失败的投票不能进入持久化层");

        // 数据库恢复后，同一张票必须能正常重试且只计一次权重。
        store.failRecordVote = false;
        FinalityRecord accepted = gadget.submitVote(vote("v1", 1, CP1));
        assertEquals(new BigDecimal("300"), accepted.getVotedWeight());
        assertEquals(Set.of("v1"), store.loadVoters(1, CP1));

        FinalityRecord duplicate = gadget.submitVote(vote("v1", 1, CP1));
        assertEquals(new BigDecimal("300"), duplicate.getVotedWeight());
        assertEquals(Set.of("v1"), store.loadVoters(1, CP1));
    }

    @Test
    void finalizationWriteOutageIsRetriedByDuplicateVote() {
        store.failMarkFinalized = true;
        gadget.submitVote(vote("v1", 1, CP1));

        assertThrows(IllegalStateException.class,
                () -> gadget.submitVote(vote("v2", 1, CP1)),
                "最终化标记写库失败必须 fail-closed");

        // 旧实现先置 finalizedCheckpoints 再写库：这里会错误地报告 FINALIZED。
        assertFalse(gadget.isFinalized(1, CP1), "数据库未确认前不能对外报告最终化");
        assertFalse(store.isFinalized(1, CP1));
        assertEquals(new BigDecimal("600"), gadget.getFinality(1, CP1).getVotedWeight(),
                "投票本身已持久化，权重仍应保留");

        // 重复票是天然重试入口：数据库恢复后应补写最终化标记并转为 FINALIZED。
        store.failMarkFinalized = false;
        FinalityRecord retried = gadget.submitVote(vote("v2", 1, CP1));
        assertTrue(retried.isFinalized());
        assertTrue(gadget.isFinalized(1, CP1));
        assertTrue(store.isFinalized(1, CP1));
        assertEquals(Set.of("v1", "v2"), store.loadVoters(1, CP1));
    }

    @Test
    void aggregateRejectionIsNotRetriedByEveryDuplicateVote() {
        AtomicInteger aggregateCalls = new AtomicInteger();
        SignatureAggregator rejecting = new SignatureAggregator() {
            @Override
            public AggregatedSignature aggregate(java.util.List<Vote> votes) {
                aggregateCalls.incrementAndGet();
                return new AggregatedSignature() {
                    @Override public byte[] compressed() { return new byte[0]; }
                    @Override public int signerCount() { return votes.size(); }
                };
            }

            @Override
            public boolean verifyAggregate(java.util.List<Vote> votes, AggregatedSignature aggregated) {
                return false;
            }
        };
        gadget.setSignatureAggregator(rejecting);

        gadget.submitVote(vote("v1", 1, CP1));
        gadget.submitVote(vote("v2", 1, CP1));
        assertEquals(1, aggregateCalls.get());
        assertFalse(gadget.isFinalized(1, CP1));

        // 重复票不改变投票集合，不能重复触发聚合验签（避免 P2P 重复消息放大 CPU）。
        gadget.submitVote(vote("v2", 1, CP1));
        assertEquals(1, aggregateCalls.get());

        // 新验证人加入后集合发生变化，允许重新尝试一次。
        gadget.submitVote(vote("v3", 1, CP1));
        assertEquals(2, aggregateCalls.get());
        assertFalse(gadget.isFinalized(1, CP1));
    }

    @Test
    void outOfOrderAndDelayedVotesFinalizeIndependently() {
        // epoch2 的 CP2 先到，epoch1 的 CP1 后到，再交错补齐，不要求按 epoch 单调到达。
        assertEquals(new BigDecimal("300"), gadget.submitVote(vote("v3", 2, CP2)).getVotedWeight());
        assertEquals(new BigDecimal("300"), gadget.submitVote(vote("v1", 1, CP1)).getVotedWeight());
        FinalityRecord epoch2 = gadget.submitVote(vote("v1", 2, CP2));
        FinalityRecord epoch1 = gadget.submitVote(vote("v2", 1, CP1));

        assertTrue(epoch2.isFinalized());
        assertTrue(epoch1.isFinalized());
        assertTrue(gadget.isFinalized(2, CP2));
        assertTrue(gadget.isFinalized(1, CP1));

        // 延迟到达的重复票不能使已最终化状态回退，也不能重复计权重。
        FinalityRecord delayedDuplicate = gadget.submitVote(vote("v1", 2, CP2));
        assertTrue(delayedDuplicate.isFinalized());
        assertEquals(new BigDecimal("600"), delayedDuplicate.getVotedWeight());
    }

    @Test
    void equivocationDetectedWhenDoubleVoteOrderIsReversed() {
        // 先 CP2 再 CP1，双签检测必须与到达顺序无关。
        gadget.submitVote(vote("v1", 1, CP2));
        gadget.submitVote(vote("v1", 1, CP1));

        assertEquals(1, gadget.getDetectedEquivocations().size());
        assertEquals("v1", gadget.getDetectedEquivocations().get(0).getOffender());
    }

    @Test
    void restartFromAddressOnlyStoreDropsUnsignedVotesAndReaccumulates() {
        // 老库（PLAN-018 之前）只存投票者地址、不存签名：模拟最终化标记写失败后重启。
        store.failMarkFinalized = true;
        gadget.submitVote(vote("v1", 1, CP1));
        assertThrows(IllegalStateException.class, () -> gadget.submitVote(vote("v2", 1, CP1)));

        store.failMarkFinalized = false;
        FinalityGadget restarted = newGadget(store);
        assertFalse(restarted.isFinalized(1, CP1));
        // PLAN-065：无法验签的历史行不能被信任，重启后不得据此恢复权重/占位；
        // 否则伪造票（或迁移前脏行）会永久挡住合法票。
        assertEquals(0, restarted.getFinality(1, CP1).getVotedWeight().signum(),
                "无签名的历史行不得在重启后恢复权重（无法验签即不可信）");

        // 合法票重新到达后正常累计并最终化，证明被丢弃的历史行不会占位。
        restarted.submitVote(vote("v1", 1, CP1));
        FinalityRecord finished = restarted.submitVote(vote("v2", 1, CP1));
        assertTrue(finished.isFinalized());
        assertTrue(restarted.isFinalized(1, CP1));
        assertTrue(store.isFinalized(1, CP1));
    }

    /** 可切换故障的持久化存储；委托真实 InMemory 实现保持其余语义。 */
    private static final class FaultInjectingStore implements FinalityStateStore {
        private final FinalityStateStore delegate = new InMemoryFinalityStateStore();
        private boolean failRecordVote;
        private boolean failMarkFinalized;

        @Override
        public void recordVote(long epoch, byte[] checkpointHash, String validatorAddress) {
            if (failRecordVote) {
                throw new IllegalStateException("simulated JDBC outage while recording vote");
            }
            delegate.recordVote(epoch, checkpointHash, validatorAddress);
        }

        @Override
        public void markFinalized(long epoch, byte[] checkpointHash) {
            if (failMarkFinalized) {
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
    }
}
