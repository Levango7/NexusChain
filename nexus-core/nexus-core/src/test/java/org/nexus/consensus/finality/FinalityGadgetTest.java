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

    @Test
    void equivocationTriggersSlashing_whenSlashingServiceInjected() {
        // M4 连接轴：注入 SlashingService 后，双签证据自动没收质押
        org.nexus.consensus.pos.SlashingService slashingService = new org.nexus.consensus.pos.SlashingService();
        try {
            var f1 = org.nexus.consensus.pos.SlashingService.class.getDeclaredField("validatorRegistry");
            f1.setAccessible(true);
            f1.set(slashingService, registry);
            var f2 = org.nexus.consensus.pos.SlashingService.class.getDeclaredField("stakingService");
            f2.setAccessible(true);
            f2.set(slashingService, staking);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        gadget.setSlashingService(slashingService);

        gadget.submitVote(vote("v1", 1, CP1));
        gadget.submitVote(vote("v1", 1, CP2));  // 双签

        assertEquals(1, gadget.getDetectedEquivocations().size());
        // v1 质押被全额没收(DOUBLE_SIGN=100%)，且验证人状态置为 SLASHED
        assertEquals(0, staking.getStake("v1").compareTo(java.math.BigDecimal.ZERO));
        assertEquals(ValidatorStatus.SLASHED, registry.getValidator("v1").getStatus());
    }

    @Test
    void equivocationNotSlashedTwice() {
        // M4 幂等：同一作恶者重复双签不重复罚没（质押已归零，二次 slash 不应改变状态/报警）
        org.nexus.consensus.pos.SlashingService slashingService = new org.nexus.consensus.pos.SlashingService();
        try {
            var f1 = org.nexus.consensus.pos.SlashingService.class.getDeclaredField("validatorRegistry");
            f1.setAccessible(true);
            f1.set(slashingService, registry);
            var f2 = org.nexus.consensus.pos.SlashingService.class.getDeclaredField("stakingService");
            f2.setAccessible(true);
            f2.set(slashingService, staking);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        gadget.setSlashingService(slashingService);

        gadget.submitVote(vote("v1", 1, CP1));
        gadget.submitVote(vote("v1", 1, CP2));
        gadget.submitVote(vote("v1", 2, CP1));  // 不同 epoch，不构成新证据
        gadget.submitVote(vote("v1", 2, CP2));

        // 注入罚没服务后，v1 在第一次双签后已被 SLASHED，后续投票因状态非 ACTIVE 不再累积权重
        assertEquals(0, staking.getStake("v1").compareTo(java.math.BigDecimal.ZERO));
        assertEquals(ValidatorStatus.SLASHED, registry.getValidator("v1").getStatus());
    }

    @Test
    void aggregateVerificationPassed_thenFinalized() {
        // M3 挂接：聚合验签通过（默认收集式降级）→ 正常最终化
        FinalityRecord r1 = gadget.submitVote(vote("v1", 1, CP1));
        FinalityRecord r2 = gadget.submitVote(vote("v2", 1, CP1));
        assertTrue(r2.isFinalized());
        assertTrue(gadget.isFinalized(1, CP1));
    }

    @Test
    void aggregateVerificationFailed_failClosedNotFinalized() {
        // M3 挂接：注入一个永远验签失败的聚合器 → 即使权重达阈值也不最终化（fail-closed）
        FinalityGadget g = new FinalityGadget(registry, staking);
        g.setSignatureAggregator(new SignatureAggregator() {
            @Override
            public AggregatedSignature aggregate(java.util.List<Vote> votes) {
                return new AggregatedSignature() {
                    @Override public byte[] compressed() { return new byte[0]; }
                    @Override public int signerCount() { return votes.size(); }
                };
            }
            @Override
            public boolean verifyAggregate(java.util.List<Vote> votes, AggregatedSignature aggregated) {
                return false; // 模拟验签失败
            }
        });
        g.submitVote(vote("v1", 1, CP1));
        FinalityRecord r = g.submitVote(vote("v2", 1, CP1));
        assertFalse(r.isFinalized());
        assertFalse(g.isFinalized(1, CP1));
        assertEquals(67, r.progressPercent()); // 权重仍累积，但最终化被验签失败否决
    }
}
