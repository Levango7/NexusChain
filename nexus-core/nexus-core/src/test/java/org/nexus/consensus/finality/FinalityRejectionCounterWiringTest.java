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
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-066：拒绝计数必须真的被接线（不是"类写了、指标注册了、计数不发出"）。
 *
 * <p>PLAN-058/063 的同型教训在这里换了一头：那两次是"指标注册了但规则侧/生产侧
 * 看不到名字"；这一次的危险是"端口有了、Wire 调用也写了，但四条拒绝路径里漏了
 * 一条"——漏掉的那条恰恰是攻击最可能走的入口，而它的指标恒为 0 与"没有攻击"
 * 完全无法区分。所以每条拒绝路径各有一个用例，并额外钉住：</p>
 *
 * <ul>
 *   <li><b>构造期事件不丢</b>：{@link FinalityGadget#restoreFromStore()} 跑在
 *       构造器里，端口是 setter 注入 ⇒ 没重放缓冲的话，"重启后发现库被污染"
 *       这个信号在指标上恒为 0；</li>
 *   <li><b>诚实票不计数</b>：否则告警会把正常投票打成攻击；</li>
 *   <li><b>phase 分开</b>：同一 reason 在不同路径含义不同（库里有坏行 vs
 *       正在被网络攻击），合成一个标签会让告警失去定位能力。</li>
 * </ul>
 */
class FinalityRejectionCounterWiringTest {

    private static final byte[] CP1 = new byte[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};

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

    @Test
    void forgedVoteIncrementsSignatureRejectionOnAdmissionPath() {
        gadget.submitVote(forged("v1", 1, CP1));

        assertEquals(1, counter.count(Reason.SIGNATURE_INVALID, Phase.ADMISSION),
                "伪造票（公钥正确、签名伪造）必须被计数");
        assertEquals(1, counter.voteRejections(),
                "只有这一个拒绝，不得有多余的拒绝计数（否则告警定位会失真）");
        assertEquals(Set.of(Reason.SIGNATURE_INVALID + "@" + Phase.ADMISSION), counter.observedKeys(),
                "键名必须与闭合词表逐字对应");
    }

    @Test
    void unknownValidatorIsCountedSeparatelyFromBadSignature() {
        Ed25519KeyPair stranger = Ed25519.generateKeyPair();
        byte[] pub = stranger.getPublicKey().getEncoded();
        Vote outsider = new Vote(1, CP1, "ghost", new byte[64], pub);

        gadget.submitVote(outsider);

        assertEquals(1, counter.count(Reason.VALIDATOR_UNKNOWN, Phase.ADMISSION),
                "未注册验证人必须被单独计数（否则无法把'冒充未知地址'与'冒充已知验证人'分开）");
        assertEquals(0, counter.count(Reason.SIGNATURE_INVALID, Phase.ADMISSION),
                "身份检查在签名检查之前，未注册者不得再被记成签名伪造");
    }

    @Test
    void zeroTotalWeightIsCountedSoSilentValidatorSetFailuresBecomeVisible() {
        // 验证人集尚未装载（或质押全为 0）时：所有票都会被忽略。
        // 修复前只有一行 WARN，指标上看不出"其实一票都没在计"。
        ValidatorRegistry emptyRegistry = new ValidatorRegistry(new BigDecimal("100"), 100);
        StakingServiceImpl emptyStaking = new StakingServiceImpl();
        injectRegistryIntoStaking(emptyStaking, emptyRegistry);
        FinalityGadget emptyGadget = new FinalityGadget(emptyRegistry, emptyStaking, store);
        RecordingCounter emptyCounter = new RecordingCounter();
        emptyGadget.setRejectionCounter(emptyCounter);

        emptyGadget.submitVote(vote("v1", 1, CP1));

        assertEquals(1, emptyCounter.count(Reason.ZERO_TOTAL_WEIGHT, Phase.ADMISSION),
                "总权重为 0 的忽略必须可观测，否则'启动早期 vs 注册表坏了'无法区分");
    }

    @Test
    void convergenceRejectionIsCountedOnItsOwnPath() {
        Vote forged = forged("v2", 1, CP1);
        store.recordVote(1, CP1, "v2", forged.getSignature(), forged.getPublicKeyBytes());

        gadget.convergeFinalityView();

        assertEquals(1, counter.count(Reason.SIGNATURE_INVALID, Phase.CONVERGENCE),
                "收敛路径拒绝的是'库里躺着坏行'，必须与 admission 分开计数");
        assertEquals(0, counter.count(Reason.SIGNATURE_INVALID, Phase.ADMISSION),
                "本用例没有走提交路径，不得在 admission 上计数");
    }

    @Test
    void startupRestoreDropsAreReplayedWhenCounterIsInjectedLater() {
        // 关键回归点：restoreFromStore() 跑在构造器里，端口要等 Spring setter 注入。
        // 没有重放缓冲时，这条计数会永久消失。
        Vote forged = forged("v1", 1, CP1);
        store.recordVote(1, CP1, "v1", forged.getSignature(), forged.getPublicKeyBytes());

        FinalityGadget restarted = new FinalityGadget(registry, staking, store);
        assertEquals(0, counter.voteRejections(),
                "端口未注入的 gadget 不得把计数写到别的计数端点上");
        RecordingCounter lateCounter = new RecordingCounter();
        restarted.setRejectionCounter(lateCounter);

        assertEquals(1, lateCounter.count(Reason.SIGNATURE_INVALID, Phase.RESTORE),
                "构造期缓冲的拒绝必须在端口注入后补记（否则'重启后发现库被污染'指标恒 0）");
        RecordingCounter second = new RecordingCounter();
        restarted.setRejectionCounter(second);
        assertEquals(1, lateCounter.count(Reason.SIGNATURE_INVALID, Phase.RESTORE),
                "替换计数端点不得回改已发出的计数");
        assertEquals(0, second.count(Reason.SIGNATURE_INVALID, Phase.RESTORE),
                "已重放过的事件不得再补记一次（计数器会重复计数）");
    }

    @Test
    void aggregateVerificationFailureIsCountedOnFinalizePath() {
        gadget.setSignatureAggregator(rejectingAggregator());

        gadget.submitVote(vote("v1", 1, CP1));
        gadget.submitVote(vote("v2", 1, CP1));   // 600/900 达阈值 → 聚合验签失败

        assertEquals(1, counter.count(FinalizeReason.AGGREGATE_VERIFICATION_FAILED),
                "阈值已过但最终化被拒必须可观测（否则 liveness 卡死只剩日志）");
        assertEquals(0, counter.voteRejections(), "票级拒绝与检查点级拒绝不得混计");
    }

    @Test
    void honestVotesDoNotIncrementAnyRejectionCounter() {
        FinalityRecord first = gadget.submitVote(vote("v1", 1, CP1));
        FinalityRecord second = gadget.submitVote(vote("v2", 1, CP1));

        assertTrue(second.isFinalized(), "合法票应正常最终化（否则本用例的前提不成立）");
        assertEquals(new BigDecimal("300"), first.getVotedWeight(), "首票应只计 300 权重");
        assertEquals(0, counter.voteRejections() + counter.finalizeRejections(),
                "诚实投票不得产生任何拒绝计数，否则告警会把正常流量打成攻击");
    }

    // ------------------------------------------------------------------ helpers

    /** 记录型假端口：只存 <reason, phase> → 次数，不引入 Micrometer 依赖。 */
    private static final class RecordingCounter implements FinalityRejectionCounter {

        private final Map<String, Integer> votes = new HashMap<>();
        private final Map<FinalizeReason, Integer> finalize = new HashMap<>();

        int count(Reason reason, Phase phase) {
            return votes.getOrDefault(reason + "@" + phase, 0);
        }

        int count(FinalizeReason reason) {
            return finalize.getOrDefault(reason, 0);
        }

        int voteRejections() {
            return votes.values().stream().mapToInt(Integer::intValue).sum();
        }

        Set<String> observedKeys() {
            return Set.copyOf(votes.keySet());
        }

        int finalizeRejections() {
            return finalize.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public void countVoteRejected(Reason reason, Phase phase) {
            votes.merge(reason + "@" + phase, 1, Integer::sum);
        }

        @Override
        public void countFinalizeRejected(FinalizeReason reason) {
            finalize.merge(reason, 1, Integer::sum);
        }
    }

    private SignatureAggregator rejectingAggregator() {
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
                return false;   // 模拟聚合验签失败
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

    /** 伪造票：注册表里的真实公钥 + 非该私钥产生的垃圾签名。 */
    private Vote forged(String validator, long epoch, byte[] checkpoint) {
        Ed25519KeyPair keyPair = validatorKeys.get(validator);
        byte[] publicKey = keyPair.getPublicKey().getEncoded();
        byte[] garbage = new byte[64];
        for (int i = 0; i < garbage.length; i++) {
            garbage[i] = (byte) (0xA5 ^ i);
        }
        return new Vote(epoch, checkpoint, validator, garbage, publicKey);
    }
}
