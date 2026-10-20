package org.nexus.consensus.finality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.persistence.FinalityStateStore;
import org.nexus.consensus.finality.persistence.InMemoryFinalityStateStore;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.core.Block;
import org.nexus.crypto.ed25519.Ed25519;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FinalityCoordinatorTest {

    private ValidatorRegistry registry;
    private StakingServiceImpl staking;
    private FinalityGadget gadget;
    private FinalityCoordinator coordinator;
    private org.nexus.crypto.ed25519.Ed25519KeyPair keyPair;
    private static final long EPOCH = 4;

    @BeforeEach
    void setUp() {
        registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        staking = newStaking(registry);
        staking.stake("v1", new BigDecimal("300"));
        staking.stake("v2", new BigDecimal("300"));
        staking.stake("v3", new BigDecimal("300"));
        // P0-1 绑定校验：v1 必须注册与投票签名密钥一致的 Ed25519 公钥
        keyPair = Ed25519.generateKeyPair();
        registry.register("v1", org.apache.commons.codec.binary.Hex.encodeHexString(
                keyPair.getPublicKey().getEncoded()), new BigDecimal("300"), 0.1);
        registry.register("v2", "pub2", new BigDecimal("300"), 0.1);
        registry.register("v3", "pub3", new BigDecimal("300"), 0.1);
        registry.getValidator("v1").setStatus(ValidatorStatus.ACTIVE);
        registry.getValidator("v2").setStatus(ValidatorStatus.ACTIVE);
        registry.getValidator("v3").setStatus(ValidatorStatus.ACTIVE);

        gadget = new FinalityGadget(registry, staking);
        // P0-1 审计修复后：必须注入 Ed25519 密钥对才能投票（fail-closed 防伪造）
        coordinator = new FinalityCoordinator(gadget, registry, EPOCH, null);
        coordinator.setVoteSigningKeyPair(keyPair.getPrivateKey(), keyPair.getPublicKey());
        coordinator.setSelfValidatorAddress("v1");
    }

    private StakingServiceImpl newStaking(ValidatorRegistry reg) {
        StakingServiceImpl s = new StakingServiceImpl();
        try {
            var f = StakingServiceImpl.class.getDeclaredField("validatorRegistry");
            f.setAccessible(true);
            f.set(s, reg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return s;
    }

    private Block mockBlock(long height) {
        Block b = new Block();
        b.nHeight = height;
        return b;
    }

    @Test
    void checkpointBoundaryDetection() {
        assertTrue(coordinator.isCheckpoint(4));
        assertTrue(coordinator.isCheckpoint(8));
        assertFalse(coordinator.isCheckpoint(5));
        assertFalse(coordinator.isCheckpoint(0));
        assertEquals(1, coordinator.epochOf(4));
        assertEquals(2, coordinator.epochOf(5));
        assertEquals(2, coordinator.epochOf(8));
    }

    @Test
    void votesOnCheckpointBlock() {
        FinalityRecord rec = coordinator.onBlock(mockBlock(4));
        assertNotNull(rec);
        assertFalse(rec.isFinalized());  // 单票 1/3 不足 2/3
        assertEquals(33, rec.progressPercent());
    }

    @Test
    void noVoteOnNonCheckpoint() {
        assertNull(coordinator.onBlock(mockBlock(3)));
        assertNull(coordinator.onBlock(mockBlock(5)));
    }

    @Test
    void inactiveValidatorSkipsVote() {
        // 未注册地址：协调器 fail-closed 不投票
        FinalityCoordinator bystander = new FinalityCoordinator(gadget, registry, EPOCH);
        bystander.setSelfValidatorAddress("not-a-validator");
        assertNull(bystander.onBlock(mockBlock(4)));
        // 且无任何投票被记录（epoch 1 无权重）
        FinalityRecord anyRecord = gadget.getFinality(1, new byte[0]);
        assertEquals(0, anyRecord.getVotedWeight().compareTo(BigDecimal.ZERO));
    }

    @Test
    void nullSelfAddressNeverVotes() {
        FinalityCoordinator none = new FinalityCoordinator(gadget, registry, EPOCH);
        assertNull(none.onBlock(mockBlock(4)));
        FinalityRecord anyRecord = gadget.getFinality(1, new byte[0]);
        assertEquals(0, anyRecord.getVotedWeight().compareTo(BigDecimal.ZERO));
    }

    @Test
    void slashedValidatorStopsVoting() {
        // 先确认活跃验证人可投票
        assertNotNull(coordinator.onBlock(mockBlock(4)));
        // 罚没后：同验证人对下一个检查点不再投票
        registry.getValidator("v1").setStatus(ValidatorStatus.SLASHED);
        assertNull(coordinator.onBlock(mockBlock(8)));
    }

    @Test
    void persistenceFailureIsQueuedAndRetriedOnNextBlockWithoutThrowing() {
        FlakyStore flakyStore = new FlakyStore();
        FinalityGadget retryingGadget = new FinalityGadget(registry, staking, flakyStore);
        FinalityCoordinator retryingCoordinator =
                new FinalityCoordinator(retryingGadget, registry, EPOCH, null);
        retryingCoordinator.setVoteSigningKeyPair(keyPair.getPrivateKey(), keyPair.getPublicKey());
        retryingCoordinator.setSelfValidatorAddress("v1");

        flakyStore.failRecordVote = true;
        Block checkpoint = mockBlock(4);
        byte[] checkpointHash = checkpoint.getHash();
        // Phase 6B 回归：写库失败不得从 onBlock 抛出——否则 StateDB 的
        // NewBlockEvent 监听器异常会让 pendingBlock 等待 60 秒。
        assertDoesNotThrow(() -> retryingCoordinator.onBlock(checkpoint));
        assertEquals(1, retryingCoordinator.pendingVoteCount());
        assertEquals(0, retryingGadget.getFinality(1, checkpointHash).getVotedWeight().signum());

        // 同一检查点因区块重放再次进入 onBlock：不得在队列中堆积重复票。
        assertDoesNotThrow(() -> retryingCoordinator.onBlock(checkpoint));
        assertEquals(1, retryingCoordinator.pendingVoteCount());

        // 数据库恢复后，下一个普通区块事件就应触发重试，无需等到下个 epoch。
        flakyStore.failRecordVote = false;
        assertNull(retryingCoordinator.onBlock(mockBlock(5)));
        assertEquals(0, retryingCoordinator.pendingVoteCount());
        assertEquals(new BigDecimal("300"),
                retryingGadget.getFinality(1, checkpointHash).getVotedWeight());
    }

    /** 可切换 recordVote 故障的存储包装；其余语义委托内存实现。 */
    private static final class FlakyStore implements FinalityStateStore {
        private final FinalityStateStore delegate = new InMemoryFinalityStateStore();
        private boolean failRecordVote;

        @Override
        public void recordVote(long epoch, byte[] checkpointHash, String validatorAddress) {
            if (failRecordVote) {
                throw new IllegalStateException("simulated JDBC outage");
            }
            delegate.recordVote(epoch, checkpointHash, validatorAddress);
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
