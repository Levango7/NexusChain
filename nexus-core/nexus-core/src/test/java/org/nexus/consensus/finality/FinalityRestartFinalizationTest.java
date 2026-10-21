package org.nexus.consensus.finality;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.persistence.FinalityOutboxEntry;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-018 Phase 6C 第二切片：重启后仍能补最终化。
 *
 * <p>PLAN-016 审计 P1 的根因是"完整 Vote 签名没落库"：重启后只能恢复
 * 「谁投过」，恢复不出「投的是什么签名」，聚合验签无票可验，
 * 检查点永远停在"权重够了但验签不了"。本测试用**真实 Ed25519 签名**
 * 钉死修复后的行为：</p>
 *
 * <ol>
 *   <li>票连同签名落库；</li>
 *   <li>重启后能恢复出完整 Vote 对象；</li>
 *   <li>重启后**不需要新票**即可把已达阈值的检查点补成最终化。</li>
 * </ol>
 */
class FinalityRestartFinalizationTest {

    private static final byte[] CP1 = new byte[]{1, 2, 3};

    private ValidatorRegistry registry;
    private StakingService staking;
    private SignaturesPersistingStore store;
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
        store = new SignaturesPersistingStore();
    }

    private FinalityGadget newGadget(FinalityStateStore stateStore) {
        return new FinalityGadget(registry, staking, stateStore);
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

    @Test
    void checkpointIsFinalizedAfterRestartWithoutAnyNewVote() {
        FinalityGadget gadget = newGadget(store);

        // v1 落库成功；v2 也落库成功，但最终化标记写失败（模拟最终化那一步的故障）。
        gadget.submitVote(vote("v1", 1, CP1));
        store.failMarkFinalized = true;
        assertThrows(IllegalStateException.class, () -> gadget.submitVote(vote("v2", 1, CP1)));
        assertFalse(gadget.isFinalized(1, CP1), "写库失败时不得对外报告已最终化");

        // 票与签名都已落库。
        assertEquals(2, store.loadAllVoteObjects().values().stream()
                .mapToInt(List::size).sum(), "两张票连同签名必须落库");

        // 重启：恢复后由启动补最终化扫描把检查点补上，**不需要新票**。
        store.failMarkFinalized = false;
        FinalityGadget restarted = newGadget(store);

        assertTrue(restarted.isFinalized(1, CP1), "重启后必须能补最终化（PLAN-016 审计 P1）");
        assertTrue(store.isFinalized(1, CP1), "最终化事实必须真的落库");
    }

    @Test
    void restoredVoteObjectsKeepTheirSignatures() {
        FinalityGadget gadget = newGadget(store);
        gadget.submitVote(vote("v1", 1, CP1));
        gadget.submitVote(vote("v2", 1, CP1));

        Map<String, List<Vote>> restored = store.loadAllVoteObjects();
        int total = restored.values().stream().mapToInt(List::size).sum();
        assertEquals(2, total, "两张票的完整 Vote 对象必须落库");
        for (List<Vote> votes : restored.values()) {
            for (Vote vote : votes) {
                assertTrue(vote.getSignature() != null && vote.getSignature().length > 0,
                        "恢复出来的 Vote 必须带签名，否则重启后无法验签");
                assertTrue(vote.getPublicKeyBytes() != null && vote.getPublicKeyBytes().length > 0,
                        "恢复出来的 Vote 必须带公钥");
            }
        }
    }

    /** 正常存储：落签名、落 outbox，可选地让"标记最终化"失败。 */
    private static final class SignaturesPersistingStore implements FinalityStateStore {
        private final FinalityStateStore delegate = new InMemoryFinalityStateStore();
        private boolean failMarkFinalized;

        @Override
        public void recordVote(long epoch, byte[] checkpointHash, String validatorAddress) {
            delegate.recordVote(epoch, checkpointHash, validatorAddress);
        }

        @Override
        public void recordVote(long epoch, byte[] checkpointHash, String validatorAddress,
                               byte[] signature, byte[] publicKey) {
            delegate.recordVote(epoch, checkpointHash, validatorAddress, signature, publicKey);
        }

        @Override
        public void markFinalized(long epoch, byte[] checkpointHash) {
            if (failMarkFinalized) {
                throw new IllegalStateException("simulated JDBC outage while marking finalized");
            }
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

        @Override
        public Map<String, List<Vote>> loadAllVoteObjects() {
            return delegate.loadAllVoteObjects();
        }

        @Override
        public void enqueuePendingVote(FinalityOutboxEntry entry) {
            delegate.enqueuePendingVote(entry);
        }

        @Override
        public List<FinalityOutboxEntry> loadPendingVotes(int limit) {
            return delegate.loadPendingVotes(limit);
        }

        @Override
        public void removePendingVote(FinalityOutboxEntry entry) {
            delegate.removePendingVote(entry);
        }

        @Override
        public void markPendingVoteFailure(FinalityOutboxEntry entry, String error) {
            delegate.markPendingVoteFailure(entry, error);
        }
    }

    /** 只存地址、不存签名的存储（模拟 PLAN-018 之前的老库）。 */
    private static final class AddressOnlyStore implements FinalityStateStore {
        private final FinalityStateStore delegate = new InMemoryFinalityStateStore();

        @Override
        public void recordVote(long epoch, byte[] checkpointHash, String validatorAddress) {
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
