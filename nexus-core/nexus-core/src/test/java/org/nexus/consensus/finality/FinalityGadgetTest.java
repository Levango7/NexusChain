package org.nexus.consensus.finality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.pos.StakingService;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.consensus.pos.Validator;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class FinalityGadgetTest {

    private ValidatorRegistry registry;
    private StakingService staking;
    private FinalityGadget gadget;

    private static final byte[] CP1 = new byte[]{1, 2, 3};
    private static final byte[] CP2 = new byte[]{9, 9, 9};

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
        // 三个活跃验证者，各质押 300 → 总权重 900，2/3 阈值 = 600
        addValidator("v1", 300);
        addValidator("v2", 300);
        addValidator("v3", 300);
        gadget = new FinalityGadget(registry, staking);
    }

    private void addValidator(String addr, int stake) {
        registry.register(addr, "pubkey-" + addr, new BigDecimal(stake), 0.1);
        Validator v = registry.getValidator(addr);
        v.setStatus(ValidatorStatus.ACTIVE);
        staking.stake(addr, new BigDecimal(stake));
    }

    private Vote vote(String validator, long epoch, byte[] checkpoint) {
        return new Vote(epoch, checkpoint, validator, new byte[]{0x01});
    }

    @Test
    void singleVoteNotFinalized() {
        FinalityRecord r = gadget.submitVote(vote("v1", 1, CP1));
        assertFalse(r.isFinalized());
        assertEquals(33, r.progressPercent());
    }

    @Test
    void twoThirdsFinalized() {
        gadget.submitVote(vote("v1", 1, CP1));
        FinalityRecord r = gadget.submitVote(vote("v2", 1, CP1));
        assertTrue(r.isFinalized());
        assertTrue(gadget.isFinalized(1, CP1));
        assertEquals(67, r.progressPercent());
    }

    @Test
    void fullVotesFinalized100Percent() {
        gadget.submitVote(vote("v1", 1, CP1));
        gadget.submitVote(vote("v2", 1, CP1));
        FinalityRecord r = gadget.submitVote(vote("v3", 1, CP1));
        assertTrue(r.isFinalized());
        assertEquals(100, r.progressPercent());
    }

    @Test
    void duplicateVoteIdempotent() {
        gadget.submitVote(vote("v1", 1, CP1));
        FinalityRecord r = gadget.submitVote(vote("v1", 1, CP1));
        assertEquals(new BigDecimal("300"), r.getVotedWeight());
    }

    @Test
    void equivocationDetectedOnDoubleVote() {
        gadget.submitVote(vote("v1", 1, CP1));
        gadget.submitVote(vote("v1", 1, CP2));
        assertEquals(1, gadget.getDetectedEquivocations().size());
        assertEquals("v1", gadget.getDetectedEquivocations().get(0).getOffender());
    }

    @Test
    void unknownValidatorVoteIgnored() {
        FinalityRecord r = gadget.submitVote(vote("ghost", 1, CP1));
        assertEquals(0, r.getVotedWeight().compareTo(BigDecimal.ZERO));
        assertFalse(r.isFinalized());
    }

    @Test
    void separateEpochsIndependent() {
        gadget.submitVote(vote("v1", 1, CP1));
        gadget.submitVote(vote("v2", 1, CP1));
        FinalityRecord r2 = gadget.submitVote(vote("v1", 2, CP1));
        assertFalse(r2.isFinalized());
        assertTrue(gadget.isFinalized(1, CP1));
        assertFalse(gadget.isFinalized(2, CP1));
    }
}
