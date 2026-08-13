package org.nexus.consensus.finality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.core.Block;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class FinalityCoordinatorTest {

    private ValidatorRegistry registry;
    private StakingServiceImpl staking;
    private FinalityGadget gadget;
    private FinalityCoordinator coordinator;
    private static final long EPOCH = 4;

    @BeforeEach
    void setUp() {
        registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        staking = newStaking(registry);
        staking.stake("v1", new BigDecimal("300"));
        staking.stake("v2", new BigDecimal("300"));
        staking.stake("v3", new BigDecimal("300"));
        registry.register("v1", "pub1", new BigDecimal("300"), 0.1);
        registry.register("v2", "pub2", new BigDecimal("300"), 0.1);
        registry.register("v3", "pub3", new BigDecimal("300"), 0.1);
        registry.getValidator("v1").setStatus(ValidatorStatus.ACTIVE);
        registry.getValidator("v2").setStatus(ValidatorStatus.ACTIVE);
        registry.getValidator("v3").setStatus(ValidatorStatus.ACTIVE);

        gadget = new FinalityGadget(registry, staking);
        coordinator = new FinalityCoordinator(gadget, registry, EPOCH);
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
}
