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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-021：最终性视图的跨节点收敛。
 *
 * <p>要修的缺口：gadget 的内存视图原本只在**启动恢复**时装载一次 ⇒ 启动之后
 * 对端投的票本节点看不到 ⇒ ①对外报告的权重进度是错的；②即便对端已把该检查点终局，
 * 本节点也永远不会本地补最终化（3 节点验收实测：掉线节点重启后只看到自己 1000/3000）。</p>
 *
 * <p>本测试用"共享存储模拟对端落库"来驱动收敛路径，并钉死三条安全边界：
 * 合并必须走与提交路径同一套绑定校验、必须去重（不重复计权）、收敛后能补最终化。</p>
 */
class FinalityViewConvergenceTest {

    private static final byte[] CP1 = new byte[]{1, 2, 3};

    private ValidatorRegistry registry;
    private StakingService staking;
    private InMemoryFinalityStateStore store;
    private FinalityGadget gadget;
    private final Map<String, Ed25519KeyPair> validatorKeys = new HashMap<>();

    @BeforeEach
    void setUp() {
        validatorKeys.clear();
        registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        staking = new StakingServiceImpl();
        injectRegistryIntoStaking(staking, registry);
        addValidator("v1", 1000);
        addValidator("v2", 1000);
        addValidator("v3", 1000);
        store = new InMemoryFinalityStateStore();
        gadget = new FinalityGadget(registry, staking, store);
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

    /** 模拟"对端节点把票落进共享库"（不经过本节点的 submitVote）。 */
    private void peerWritesVote(String validator, long epoch, byte[] checkpoint) {
        Vote v = vote(validator, epoch, checkpoint);
        store.recordVote(epoch, checkpoint, v.getValidatorAddress(), v.getSignature(), v.getPublicKeyBytes());
    }

    @Test
    void convergencePullsPeerVotesAndEnablesLocalFinalization() {
        // 本节点只收到自己的一票：1/3，达不到 2/3。
        gadget.submitVote(vote("v1", 1, CP1));
        assertEquals(new BigDecimal("1000"), gadget.getFinality(1, CP1).getVotedWeight());
        assertFalse(gadget.isFinalized(1, CP1));

        // 对端随后落库两票（本节点此前完全看不到）。
        peerWritesVote("v2", 1, CP1);
        peerWritesVote("v3", 1, CP1);

        // 收敛：把库里的新事实拉进本地视图。
        gadget.convergeFinalityView();

        assertEquals(new BigDecimal("3000"), gadget.getFinality(1, CP1).getVotedWeight(),
                "收敛后权重口径应包含对端的票");
        assertTrue(gadget.isFinalized(1, CP1), "收敛后本节点应能本地补最终化");
        assertTrue(store.isFinalized(1, CP1), "最终化事实必须落库");
    }

    @Test
    void repeatedConvergenceDoesNotDoubleCountWeight() {
        gadget.submitVote(vote("v1", 1, CP1));
        peerWritesVote("v2", 1, CP1);
        gadget.convergeFinalityView();
        BigDecimal afterFirst = gadget.getFinality(1, CP1).getVotedWeight();
        gadget.convergeFinalityView();
        gadget.convergeFinalityView();
        assertEquals(afterFirst, gadget.getFinality(1, CP1).getVotedWeight(),
                "重复收敛不得重复计权（去重靠投票者集合）");
        assertEquals(new BigDecimal("2000"), afterFirst);
    }

    @Test
    void convergenceRejectsVoteWithMismatchedPublicKey() {
        gadget.submitVote(vote("v1", 1, CP1));
        // 库里有一张公钥与注册表不符的票（伪造/错配）⇒ 收敛时必须拒绝。
        Vote forged = new Vote(1, CP1, "v2", new byte[64], new byte[32]);
        store.recordVote(1, CP1, "v2", forged.getSignature(), forged.getPublicKeyBytes());

        gadget.convergeFinalityView();

        assertEquals(new BigDecimal("1000"), gadget.getFinality(1, CP1).getVotedWeight(),
                "公钥不匹配的票不得计权");
        assertFalse(gadget.isFinalized(1, CP1));
    }

    @Test
    void convergenceMarksFinalizedCheckpointsKnownFromPeers() {
        gadget.submitVote(vote("v1", 1, CP1));
        // 对端把该检查点标记为已终局（共享库里有事实）。
        store.markFinalized(1, CP1);

        gadget.convergeFinalityView();

        assertTrue(gadget.isFinalized(1, CP1), "对端已终局的事实应被本节点视图采纳");
    }
}
