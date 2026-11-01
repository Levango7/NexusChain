package org.nexus.consensus.finality;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * 拜占庭投票输入：**公钥绑定正确但签名伪造**的票。
 *
 * <p>背景：{@code FinalityGadget.validateVoteBinding} 只校验"票里的公钥 == 注册表
 * 登记的公钥"，签名验签被推迟到最终化时的 {@code SignatureAggregator}。这留下
 * 一个无需私钥的攻击面：</p>
 *
 * <pre>
 * 攻击者拿验证人 v1 的**公开**公钥，伪造 Vote(epoch, cp, "v1", 垃圾签名, v1公钥)
 *   -> 绑定校验通过 -> 落库 + 计入 v1 的权重 + 进入 voters 集合
 * 真正的 v1 随后投出合法票
 *   -> voters 已含 v1 -> 被当作"重复票"幂等拒绝，合法票对象永远进不了 votes 列表
 * 最终化时聚合验签失败
 *   -> finalizationRejected 置位 -> 该检查点永久无法最终化（重启后依旧）
 * </pre>
 *
 * <p>本测试先钉住"修复后必须成立"的行为；修复前的反向对照见 PLAN-065 §四。</p>
 */
class FinalityVoteForgeryTest {

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
        addValidator("v1", 300);
        addValidator("v2", 300);
        addValidator("v3", 300);
        store = new InMemoryFinalityStateStore();
        gadget = new FinalityGadget(registry, staking, store);
        // 不注入恒通过聚合器：本测试要的就是真实 Ed25519 验签。
    }

    @Test
    void forgedSignatureWithRegisteredPublicKeyIsRejectedBeforePersist() {
        Vote forged = forged("v1", 1, CP1);

        FinalityRecord record = gadget.submitVote(forged);

        assertEquals(0, record.getVotedWeight().signum(),
                "伪造签名的票不得计入权重");
        assertTrue(store.loadAllVoteObjects().isEmpty(),
                "伪造签名的票不得落库（否则重启后会再次污染计票）");

        // 合法票仍能正常走完流程：v1 + v2 = 600/900 ≥ 2/3。
        gadget.submitVote(vote("v1", 1, CP1));
        FinalityRecord afterRealVotes = gadget.submitVote(vote("v2", 1, CP1));

        assertEquals(new BigDecimal("600"), afterRealVotes.getVotedWeight(),
                "合法票必须能补上被伪造票占位的那个验证人");
        assertTrue(afterRealVotes.isFinalized(),
                "伪造票不得让检查点永久卡死");
    }

    @Test
    void forgedVoteCannotBlockFinalizationByPoisoningVoterSet() {
        // 攻击序列：先伪造 v1，再让真实 v1、v2 投票。
        gadget.submitVote(forged("v1", 1, CP1));
        gadget.submitVote(vote("v1", 1, CP1));
        FinalityRecord record = gadget.submitVote(vote("v2", 1, CP1));

        assertEquals(new BigDecimal("600"), record.getVotedWeight(),
                "v1 的合法票必须被计入（不能被伪造票去重掉）");
        assertTrue(record.isFinalized(),
                "2/3 合法票已到齐，检查点必须最终化");
    }

    @Test
    void convergenceSkipsVoteWithValidPublicKeyButBadSignature() {
        // 模拟对端库里已存在一行"公钥正确、签名伪造"的票。
        Vote forged = forged("v2", 1, CP1);
        store.recordVote(1, CP1, "v2", forged.getSignature(), forged.getPublicKeyBytes());

        gadget.convergeFinalityView();

        assertEquals(0, gadget.getFinality(1, CP1).getVotedWeight().signum(),
                "收敛路径也必须验签，不能只比公钥");
        assertFalse(gadget.isFinalized(1, CP1));
    }

    @Test
    void restartDropsBadSignatureRowsSoCheckpointCanStillFinalize() {
        // 模拟"修复前已被伪造票污染的库"：重启恢复不能无条件信任历史行。
        Vote forged = forged("v1", 1, CP1);
        store.recordVote(1, CP1, "v1", forged.getSignature(), forged.getPublicKeyBytes());

        FinalityGadget restarted = new FinalityGadget(registry, staking, store);
        restarted.submitVote(vote("v1", 1, CP1));
        FinalityRecord record = restarted.submitVote(vote("v2", 1, CP1));

        assertEquals(new BigDecimal("600"), record.getVotedWeight(),
                "重启后合法票仍必须能计入");
        assertTrue(record.isFinalized(),
                "历史脏行不得在重启后继续占位、阻塞最终化");
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
