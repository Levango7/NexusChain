package org.nexus.consensus.finality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-3 并发安全专项测试。
 *
 * <p>覆盖：并发投票不丢票（权重精确）、最终化状态确定性、
 * 双签检测在并发下不缺漏、重复投票幂等。</p>
 */
class FinalityGadgetConcurrencyTest {

    private static final byte[] CP1 = new byte[]{1, 2, 3};
    private static final byte[] CP2 = new byte[]{9, 8, 7};

    private ValidatorRegistry registry;
    private StakingService staking;

    /** B-17/B-18 修复后：投票必须携带真实 BLS 公钥+可验签签名才能最终化。 */
    private final Map<String, BlsSigner> validatorSigners = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        registry = new ValidatorRegistry(new BigDecimal("100"), 1000);
        staking = new StakingServiceImpl();
        injectRegistryIntoStaking();
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
    void concurrentVotesNoLostWeight() throws Exception {
        // 10 个验证人各 100 质押；并发投同一检查点
        int n = 10;
        FinalityGadget gadget = new FinalityGadget(registry, staking);
        for (int i = 1; i <= n; i++) addValidator("v" + i, 100);

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 1; i <= n; i++) {
            final String v = "v" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    gadget.submitVote(vote(v, 1, CP1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "全部投票应在超时前完成");

        FinalityRecord rec = gadget.getFinality(1, CP1);
        assertEquals(new BigDecimal("1000"), rec.getVotedWeight(), "10 票×100 权重应精确，不丢票");
        assertTrue(rec.isFinalized(), "权重 1000/1000 = 100% ≥ 2/3 应最终化");
        assertEquals(100, rec.progressPercent());
        pool.shutdownNow();
    }

    @Test
    void concurrentDuplicateVotesRemainIdempotent() throws Exception {
        // 同一验证人并发投同一检查点两次 → 权重只计一次
        addValidator("v1", 300);
        FinalityGadget gadget = new FinalityGadget(registry, staking);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    gadget.submitVote(vote("v1", 1, CP1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        done.await(10, TimeUnit.SECONDS);

        FinalityRecord rec = gadget.getFinality(1, CP1);
        assertEquals(new BigDecimal("300"), rec.getVotedWeight(), "重复投票应幂等，权重只计一次");
        pool.shutdownNow();
    }

    @Test
    void concurrentEquivocationDetectedExactlyOnce() throws Exception {
        // 并发投两个不同检查点 → 双签被检测（至少一次、权重不重复计）
        addValidator("v1", 300);
        FinalityGadget gadget = new FinalityGadget(registry, staking);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        for (int i = 0; i < 8; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    gadget.submitVote(vote("v1", 1, idx % 2 == 0 ? CP1 : CP2));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        done.await(10, TimeUnit.SECONDS);

        // 双签必须被检出（同验证人两个不同检查点）
        assertFalse(gadget.getDetectedEquivocations().isEmpty(), "并发双签必须被检出");
        // 权重只计一个检查点（幂等）
        FinalityRecord rec1 = gadget.getFinality(1, CP1);
        assertEquals(new BigDecimal("300"), rec1.getVotedWeight(), "CP1 权重应恰好一次");
        pool.shutdownNow();
    }

    @Test
    void concurrentMultiEpochIsolation() throws Exception {
        // 两 epoch 并发投票互不干扰
        int n = 6;
        for (int i = 1; i <= n; i++) addValidator("v" + i, 200);
        FinalityGadget gadget = new FinalityGadget(registry, staking);

        List<Thread> threads = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            final int idx = i;
            Thread t = new Thread(() ->
                    gadget.submitVote(vote("v" + idx, idx % 2 == 0 ? 1 : 2, CP1)));
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join(10_000);

        FinalityRecord e1 = gadget.getFinality(1, CP1);
        FinalityRecord e2 = gadget.getFinality(2, CP1);
        assertEquals(new BigDecimal("600"), e1.getVotedWeight(), "epoch1 三票×200=600");
        assertEquals(new BigDecimal("600"), e2.getVotedWeight(), "epoch2 三票×200=600");
        assertEquals(0, e1.getVotedWeight().compareTo(e2.getVotedWeight()));
    }
}