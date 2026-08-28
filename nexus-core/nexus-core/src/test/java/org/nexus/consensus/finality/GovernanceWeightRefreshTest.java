package org.nexus.consensus.finality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.pos.StakingService;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-② 专项：治理变更与最终性权重快照的关联。
 *
 * <p>验证治理新增/移除验证人后，最终化判定采用最新验证者集权重——
 * 新增验证人的票计入总权重分母与分子，移除的验证人不再计入。</p>
 */
class GovernanceWeightRefreshTest {

    private static final byte[] CP1 = new byte[]{1, 2, 3};

    private ValidatorRegistry registry;
    private StakingService staking;

    /** P0-1 修复后：投票必须携带与注册表一致的 Ed25519 公钥+真实签名才能计票。 */
    private final Map<String, org.nexus.crypto.ed25519.Ed25519KeyPair> validatorKeys = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        validatorKeys.clear();
        registry = new ValidatorRegistry(new BigDecimal("100"), 1000);
        staking = new StakingServiceImpl();
        try {
            var f = StakingServiceImpl.class.getDeclaredField("validatorRegistry");
            f.setAccessible(true);
            f.set(staking, registry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void addValidator(String addr, int stake) {
        org.nexus.crypto.ed25519.Ed25519KeyPair kp = org.nexus.crypto.ed25519.Ed25519.generateKeyPair();
        validatorKeys.put(addr, kp);
        registry.register(addr, org.apache.commons.codec.binary.Hex.encodeHexString(
                kp.getPublicKey().getEncoded()), new BigDecimal(stake), 0.1);
        Validator v = registry.getValidator(addr);
        v.setStatus(ValidatorStatus.ACTIVE);
        staking.stake(addr, new BigDecimal(stake));
    }

    /**
     * 构造带真实 Ed25519 签名与公钥的投票（P0-1 审计修复后对齐生产路径）。
     * 载荷格式与 {@link Vote#signingPayload()} 一致：epoch(8B BE) || checkpointHash。
     */
    private Vote vote(String validator, long epoch, byte[] cp) {
        org.nexus.crypto.ed25519.Ed25519KeyPair kp = validatorKeys.get(validator);
        if (kp == null) {
            throw new IllegalStateException("validator not registered: " + validator);
        }
        byte[] payload = ByteBuffer.allocate(8 + cp.length).putLong(epoch).put(cp).array();
        byte[] pub = kp.getPublicKey().getEncoded();
        try {
            byte[] sig = kp.getPrivateKey().sign(payload);
            return new Vote(epoch, cp, validator, sig, pub);
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 signing failed", e);
        }
    }

    @Test
    void newValidatorVoteCountsAfterGovernanceAdd() {
        // 初始 2 验证人各 300：v1+v2 达标 2/3（600/600=100%），单验者阈值不同
        addValidator("v1", 300);
        addValidator("v2", 300);
        FinalityGadget gadget = new FinalityGadget(registry, staking);

        gadget.submitVote(vote("v1", 1, CP1));
        gadget.submitVote(vote("v2", 1, CP1));
        assertTrue(gadget.isFinalized(1, CP1), "v1+v2=600/600 应最终化");

        // 治理新增 v3（300）：总权重 900，阈值 600；v1 单票 300 < 600 不再最终化
        addValidator("v3", 300);

        // 下一 epoch：v1 单独投票应观察新总权重（v1=300/900=33% 不足 2/3）
        FinalityRecord rec = gadget.submitVote(vote("v1", 2, CP1));
        assertFalse(rec.isFinalized(), "治理新增后单票 300/900 不足 2/3，不应最终化");
        assertEquals(new BigDecimal("900"), rec.getTotalWeight(), "总权重应刷新为 900");
        assertEquals(33, rec.progressPercent());
    }

    @Test
    void removedValidatorWeightExcludedAfterGovernanceRemove() {
        // 初始 3 验证人各 300：总权重 900
        addValidator("v1", 300);
        addValidator("v2", 300);
        addValidator("v3", 300);
        FinalityGadget gadget = new FinalityGadget(registry, staking);

        // 治理移除 v3（unregister → INACTIVE）
        registry.getValidator("v3").setStatus(ValidatorStatus.INACTIVE);

        // v1+v2 各 300 → 总权重 600，达标 2/3（600/600=100%）
        FinalityRecord r1 = gadget.submitVote(vote("v1", 1, CP1));
        FinalityRecord r2 = gadget.submitVote(vote("v2", 1, CP1));
        assertTrue(r2.isFinalized(), "移除 v3 后 v1+v2=600/600 应最终化");
        assertEquals(new BigDecimal("600"), r2.getTotalWeight(), "总权重应排除已移除验证人");
        assertEquals(100, r2.progressPercent());
    }

    @Test
    void stakeChangeReflectedInSnapshot() {
        // v1 300 + v2 300 → 总 600；v2 增质押到 600 → 总 900
        addValidator("v1", 300);
        addValidator("v2", 300);
        FinalityGadget gadget = new FinalityGadget(registry, staking);

        // 质押变更（治理）：直接更新 staking 内部 map（stake() 是追加语义，不能用于设置）
        @SuppressWarnings("unchecked")
        java.util.Map<String, BigDecimal> stakesMap;
        try {
            var f = StakingServiceImpl.class.getDeclaredField("stakes");
            f.setAccessible(true);
            stakesMap = (java.util.Map<String, BigDecimal>) f.get(staking);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        stakesMap.put("v2", new BigDecimal("600"));
        registry.getValidator("v2").setStakeAmount(new BigDecimal("600"));

        // v1 单票 300/900，不足 2/3
        FinalityRecord rec = gadget.submitVote(vote("v1", 1, CP1));
        assertEquals(new BigDecimal("900"), rec.getTotalWeight(), "质押变更后总权重应为 900");
        assertFalse(rec.isFinalized(), "300/900 不足 2/3 不应最终化");

        // v2 投票 600 → 900/900 最终化
        FinalityRecord rec2 = gadget.submitVote(vote("v2", 1, CP1));
        assertTrue(rec2.isFinalized(), "900/900 应最终化");
    }

    @Test
    void snapshotStableWithinEpochWithoutGovernanceChange() {
        // 无治理变化：同 epoch 内快照稳定（多次投票不触发刷新）
        addValidator("v1", 300);
        addValidator("v2", 300);
        FinalityGadget gadget = new FinalityGadget(registry, staking);

        gadget.submitVote(vote("v1", 1, CP1));
        FinalityRecord mid = gadget.submitVote(vote("v2", 1, CP1));
        assertEquals(new BigDecimal("600"), mid.getTotalWeight());
        assertTrue(mid.isFinalized());
        // 重复投票（幂等）不改变快照
        FinalityRecord dup = gadget.submitVote(vote("v1", 1, CP1));
        assertEquals(new BigDecimal("600"), dup.getTotalWeight());
    }
}
