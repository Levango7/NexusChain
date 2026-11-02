package org.nexus.consensus.finality;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.FinalityRejectionCounter.FinalizeReason;
import org.nexus.consensus.finality.FinalityRejectionCounter.Phase;
import org.nexus.consensus.finality.FinalityRejectionCounter.Reason;
import org.nexus.consensus.finality.persistence.InMemoryFinalityStateStore;
import org.nexus.consensus.pos.StakingService;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.core.Block;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-067：最终性检查点键的尺寸/状态上界。
 *
 * <p>这里钉住四件事，且都要求"先校验、后建键"：</p>
 * <ul>
 *   <li>admission 对短哈希/超长哈希/null 哈希 fail-closed，且不在内存里留下键；</li>
 *   <li>convergence 对库里的坏行同样拒绝，并且**不把游标推到脏行的高 epoch**；</li>
 *   <li>单验证人单 epoch 新键上限 K=4：第 5 个新键被拒，但其他验证人仍可创建，
 *       对已存在键投票也不受限制；</li>
 *   <li>restore 路径对坏哈希只丢弃、不建键，并把拒绝原因计入 RESTORE phase。</li>
 * </ul>
 */
class FinalityCheckpointBoundsTest {

    private static final byte[] CP1 = hash(0x11);
    private static final byte[] CP2 = hash(0x22);

    private ValidatorRegistry registry;
    private StakingService staking;
    private InMemoryFinalityStateStore store;
    private FinalityGadget gadget;
    private RecordingCounter counter;
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
        store = new InMemoryFinalityStateStore();
        gadget = new FinalityGadget(registry, staking, store);
        counter = new RecordingCounter();
        gadget.setRejectionCounter(counter);
    }

    // ------------------------------------------------------------------ admission

    @Test
    void admissionRejectsShortCheckpointHashBeforeCreatingAnyKey() {
        byte[] shortHash = new byte[]{1, 2, 3};

        gadget.submitVote(vote("v1", 1, shortHash));

        assertEquals(1, counter.count(Reason.CHECKPOINT_HASH_INVALID, Phase.ADMISSION),
                "短哈希必须在准入路径被计数（否则无法与签名伪造区分）");
        assertEquals(0, counter.count(Reason.SIGNATURE_INVALID, Phase.ADMISSION),
                "哈希长度检查必须先于验签：短哈希的票不应被记成签名伪造");
        assertTrue(store.loadAllVoteObjects().isEmpty(),
                "被拒票不得落库");
        assertTrue(votersMap(gadget).isEmpty(),
                "被拒票不得在内存里留下 voter set / 检查点键");
    }

    @Test
    void admissionRejectsOversizedCheckpointHashBeforeCreatingAnyKey() {
        byte[] hugeHash = new byte[4_096];
        Arrays.fill(hugeHash, (byte) 0x7F);

        gadget.submitVote(vote("v1", 1, hugeHash));

        assertEquals(1, counter.count(Reason.CHECKPOINT_HASH_INVALID, Phase.ADMISSION),
                "超长哈希同样必须被拒绝并计数");
        assertTrue(store.loadAllVoteObjects().isEmpty(), "超长哈希不得落库");
        assertTrue(votersMap(gadget).isEmpty(), "超长哈希不得建键");
    }

    @Test
    void admissionRejectsNullCheckpointHashWithoutNpe() {
        byte[] publicKey = validatorKeys.get("v1").getPublicKey().getEncoded();
        Vote nullHash = new Vote(1, null, "v1", new byte[0], publicKey);

        assertDoesNotThrow(() -> gadget.submitVote(nullHash),
                "null 哈希必须走拒绝路径，不能在 signingPayload() 里 NPE");
        assertEquals(1, counter.count(Reason.CHECKPOINT_HASH_INVALID, Phase.ADMISSION),
                "null 哈希必须归入哈希畸形，而不是 malformed_vote");
    }

    // ---------------------------------------------------------------- convergence

    @Test
    void convergenceBadHashDoesNotAdvanceCursorPastLowerValidVotes() {
        Vote validAtEpoch1 = vote("v1", 1, CP1);
        store.recordVote(1, CP1, "v1",
                validAtEpoch1.getSignature(), validAtEpoch1.getPublicKeyBytes());

        byte[] shortHash = new byte[]{1, 2, 3};
        Vote dirtyAtEpoch1000 = vote("v2", 1000, shortHash);
        store.recordVote(1000, shortHash, "v2",
                dirtyAtEpoch1000.getSignature(), dirtyAtEpoch1000.getPublicKeyBytes());

        gadget.convergeFinalityView();

        assertEquals(0, new BigDecimal("300").compareTo(
                        gadget.getFinality(1, CP1).getVotedWeight()),
                "epoch 1 的合法票必须被合并");
        assertEquals(1, counter.count(Reason.CHECKPOINT_HASH_INVALID, Phase.CONVERGENCE),
                "库里的短哈希坏行必须在收敛路径计数");
        assertFalse(votersMap(gadget).containsKey("1000|" + Hex.encodeHexString(shortHash)),
                "收敛路径必须先校验后建键：脏行不得留下检查点键");

        // 关键回归：如果游标被 epoch=1000 的脏行推到 1000，下面这条 epoch 2 的
        // 合法票将永远不会被 loadVoteObjectsSince(from) 看到。
        Vote validAtEpoch2 = vote("v2", 2, CP2);
        store.recordVote(2, CP2, "v2",
                validAtEpoch2.getSignature(), validAtEpoch2.getPublicKeyBytes());
        gadget.convergeFinalityView();

        assertEquals(0, new BigDecimal("300").compareTo(
                        gadget.getFinality(2, CP2).getVotedWeight()),
                "脏高 epoch 行不得把收敛游标推过 epoch 2 的合法票");
        assertEquals(1, counter.count(Reason.CHECKPOINT_HASH_INVALID, Phase.CONVERGENCE),
                "同一条坏行每 5 秒重扫一次，不得重复累加指标");
    }

    @Test
    void startupRestoreDropsBadHashAndReplaysTheRejectionCount() {
        byte[] shortHash = new byte[]{4, 5, 6};
        Vote dirty = vote("v1", 1, shortHash);
        store.recordVote(1, shortHash, "v1",
                dirty.getSignature(), dirty.getPublicKeyBytes());

        FinalityGadget restarted = new FinalityGadget(registry, staking, store);
        RecordingCounter lateCounter = new RecordingCounter();
        restarted.setRejectionCounter(lateCounter);

        assertEquals(1, lateCounter.count(Reason.CHECKPOINT_HASH_INVALID, Phase.RESTORE),
                "重启恢复发现坏哈希时必须计数（构造期事件要在端口注入后补记）");
        assertFalse(votersMap(restarted).containsKey("1|" + Hex.encodeHexString(shortHash)),
                "恢复路径不得为坏哈希建键");
    }

    // ------------------------------------------------------------------ key limit

    @Test
    void fifthNewKeyIsRejectedButOtherValidatorsCanStillCreateIt() {
        byte[][] hashes = new byte[5][];
        for (int i = 0; i < hashes.length; i++) {
            hashes[i] = hash(0x30 + i);
        }

        for (int i = 0; i < 4; i++) {
            gadget.submitVote(vote("v1", 1, hashes[i]));
            assertEquals(0, new BigDecimal("300").compareTo(
                            gadget.getFinality(1, hashes[i]).getVotedWeight()),
                    "前 4 个新键必须被接受");
        }

        gadget.submitVote(vote("v1", 1, hashes[4]));

        assertEquals(1, counter.count(Reason.CHECKPOINT_LIMIT_EXCEEDED, Phase.ADMISSION),
                "第 5 个新键必须被拒绝并计数");
        assertFalse(store.loadAllVoteObjects().containsKey(
                        "1|" + Hex.encodeHexString(hashes[4])),
                "被上限拒绝的票不得落库");
        assertEquals(0, new BigDecimal("0").compareTo(
                        gadget.getFinality(1, hashes[4]).getVotedWeight()),
                "被上限拒绝的检查点不得有投票权重");

        // 上限只约束"该验证人新引入的键"：另一个验证人仍可创建同一个键。
        gadget.submitVote(vote("v2", 1, hashes[4]));
        assertEquals(0, new BigDecimal("300").compareTo(
                        gadget.getFinality(1, hashes[4]).getVotedWeight()),
                "其他验证人必须仍能为新检查点建键（否则一个作恶者可以挡住真检查点）");

        // 键已存在后，原先被上限拒绝的 v1 投票必须被接受。
        gadget.submitVote(vote("v1", 1, hashes[4]));
        assertEquals(0, new BigDecimal("600").compareTo(
                        gadget.getFinality(1, hashes[4]).getVotedWeight()),
                "对已存在键投票不得被上限拦截");
        assertEquals(1, counter.count(Reason.CHECKPOINT_LIMIT_EXCEEDED, Phase.ADMISSION),
                "对已存在键投票不得再产生上限拒绝计数");
        assertTrue(gadget.getFinality(1, hashes[4]).isFinalized(),
                "600/900 = 2/3 应最终化");
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] hash(int seed) {
        byte[] h = new byte[Block.HASH_SIZE];
        Arrays.fill(h, (byte) seed);
        return h;
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

    /** 合法票：真实私钥签名。 */
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

    private void injectRegistryIntoStaking(StakingService target, ValidatorRegistry targetRegistry) {
        try {
            var field = StakingServiceImpl.class.getDeclaredField("validatorRegistry");
            field.setAccessible(true);
            field.set(target, targetRegistry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> votersMap(FinalityGadget target) {
        try {
            Field field = FinalityGadget.class.getDeclaredField("epochCheckpointVoters");
            field.setAccessible(true);
            return (Map<String, Set<String>>) field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot inspect epochCheckpointVoters", e);
        }
    }

    /** 记录型假端口：只存 (reason, phase) → 次数，不引入 Micrometer 依赖。 */
    private static final class RecordingCounter implements FinalityRejectionCounter {

        private final Map<String, Integer> votes = new HashMap<>();

        int count(Reason reason, Phase phase) {
            return votes.getOrDefault(reason + "@" + phase, 0);
        }

        @Override
        public void countVoteRejected(Reason reason, Phase phase) {
            votes.merge(reason + "@" + phase, 1, Integer::sum);
        }

        @Override
        public void countFinalizeRejected(FinalizeReason reason) {
            // 本用例不覆盖 finalize 路径。
        }
    }
}
