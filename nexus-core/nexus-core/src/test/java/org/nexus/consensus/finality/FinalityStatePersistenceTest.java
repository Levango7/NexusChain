package org.nexus.consensus.finality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.persistence.FinalityStateStore;
import org.nexus.consensus.finality.persistence.InMemoryFinalityStateStore;
import org.nexus.consensus.pos.StakingService;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.core.crypto.bls.BlsSigner;
import org.nexus.core.crypto.bls.BlsSignature;
import org.nexus.core.crypto.bls.Secp256k1BlsSigner;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-1 专项：最终性状态持久化与重启恢复。
 *
 * <p>验证节点重启后（新 FinalityGadget 实例 + 同一存储），
 * 已最终化的检查点不丢失——这是"已确认不可逆结算"的诚信底线。</p>
 */
class FinalityStatePersistenceTest {

    private static final byte[] CP1 = new byte[]{1, 2, 3};
    private static final byte[] CP2 = new byte[]{9, 8, 7};

    private ValidatorRegistry registry;
    private StakingService staking;

    /** B-17/B-18 修复后：投票必须携带真实 BLS 公钥+可验签签名才能最终化。 */
    private final Map<String, BlsSigner> validatorSigners = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        staking = new StakingServiceImpl();
        injectRegistryIntoStaking();
        addValidator("v1", 300);
        addValidator("v2", 300);
        addValidator("v3", 300);
    }

    private void injectRegistryIntoStaking() {
        try {
            var f = StakingServiceImpl.class.getDeclaredField("validatorRegistry");
            f.setAccessible(true);
            f.set(staking, registry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void addValidator(String addr, int stake) {
        registry.register(addr, "pub-" + addr, new BigDecimal(stake), 0.1);
        Validator v = registry.getValidator(addr);
        v.setStatus(ValidatorStatus.ACTIVE);
        staking.stake(addr, new BigDecimal(stake));
    }

    /**
     * 构造带真实 BLS 签名与公钥的投票（对齐 FinalityCoordinator 生产路径）。
     * 载荷格式与 {@link Vote#signingPayload()} 一致：epoch(8B BE) || checkpointHash。
     */
    private Vote vote(String validator, long epoch, byte[] cp) {
        BlsSigner signer = validatorSigners.computeIfAbsent(validator, k -> BlsSigner.generate());
        byte[] payload = ByteBuffer.allocate(8 + cp.length).putLong(epoch).put(cp).array();
        BlsSignature sig = signer.sign(payload);
        byte[] pub = ((Secp256k1BlsSigner) signer).getPublicKey().toBytesCompressed();
        return new Vote(epoch, cp, validator, sig.toBytesCompressed(), pub);
    }

    @Test
    void finalizedCheckpointSurvivesNodeRestart() {
        FinalityStateStore store = new InMemoryFinalityStateStore();

        // 第一个"节点"：达成 epoch1 最终化
        FinalityGadget node1 = new FinalityGadget(registry, staking, store);
        node1.submitVote(vote("v1", 1, CP1));
        node1.submitVote(vote("v2", 1, CP1));
        assertTrue(node1.isFinalized(1, CP1), "2/3 权重后 epoch1 应最终化");

        // 模拟节点重启：新实例 + 同一持久化存储
        FinalityGadget node2 = new FinalityGadget(registry, staking, store);

        assertTrue(node2.isFinalized(1, CP1), "重启后已最终化检查点必须不丢失");
        FinalityRecord rec = node2.getFinality(1, CP1);
        assertEquals(new BigDecimal("600"), rec.getVotedWeight(), "恢复后投票权重一致");
        assertEquals(67, rec.progressPercent());
    }

    @Test
    void voteRecordsRestoredForEquivocationContinuity() {
        FinalityStateStore store = new InMemoryFinalityStateStore();

        // 节点 1 收到部分投票并最终化
        FinalityGadget node1 = new FinalityGadget(registry, staking, store);
        node1.submitVote(vote("v1", 1, CP1));
        node1.submitVote(vote("v2", 1, CP1));
        node1.submitVote(vote("v3", 1, CP1));

        // 节点 2（重启）：v1 对同一 epoch 投不同检查点 → 应被识别为双签（非活跃投票被拒）
        FinalityGadget node2 = new FinalityGadget(registry, staking, store);
        node2.submitVote(vote("v1", 1, CP2));

        // v1 的 CP1 投票从 store 恢复，CP2 投票 → 双签检测（无 slash 服务时仅记录证据）
        assertFalse(node2.getDetectedEquivocations().isEmpty(),
                "重启后双签证据链路不应断裂");
        // epoch1 不因 CP2 的混入而受影响：CP1 仍最终化
        assertTrue(node2.isFinalized(1, CP1));
    }

    @Test
    void noVotesNoRestoration() {
        FinalityStateStore store = new InMemoryFinalityStateStore();
        FinalityGadget fresh = new FinalityGadget(registry, staking, store);
        assertFalse(fresh.isFinalized(1, CP1));
        assertEquals(0, fresh.getEpochProgress(1) == null ? 0 : fresh.getEpochProgress(1).getVotedWeight().longValue());
    }
}