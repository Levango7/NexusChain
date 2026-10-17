package org.nexus.signing.pool;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NonceAllocator} 机制回归（#33）——用<b>真实 H2(MODE=MySQL)</b> 跑迁移脚本与并发认领，
 * 不用 mock 假装验过原子性（同 #101 的 V98 幂等表手法：让唯一键/条件 UPDATE 真挡）。
 *
 * <p>四条各守一个面：
 * ① 连续认领严格递增、播种只发生一次（RPC 不重复打）；
 * ② <b>两个 allocator 实例</b>（就是 prod 三副本的形状：各 JVM 一个 bean、共用同一个库）并发认领
 *    互不重号——这条正是旧 {@code getMaxNonce} 读式分配做不到的，见
 *    {@link NoncePoolAllocationPatternTest}；
 * ③ 不同 address 水位互不干扰；
 * ④ 播种取值失败必须抛出（fail-closed，不回退进程内计数）。</p>
 */
@DisplayName("nonce 共享原子分配器")
class NonceAllocatorH2Test {

    /**
     * 共享夹具：一份库 + 一个默认分配器（间隔给大，令本组"发放"用例不被周期校正干扰；
     * 周期校正本身另有 5–8 四个用例专测）。库的 DDL 走 {@link NonceTestDbs}，
     * 它按真实顺序应用 V3→V4——不在测试里另写一份"等价"schema。
     */
    private static NonceTestDbs.H2Harness shared;

    @BeforeAll
    static void createSchemaFromRealMigration() {
        shared = NonceTestDbs.freshH2Harness("shared", 3600L);
    }

    private NonceAllocator allocator() {
        return shared.replica();
    }

    private static String freshAddress(String tag) {
        return "0x" + tag + System.nanoTime();
    }

    @Test
    @DisplayName("1.连续认领严格递增；链上播种只发生一次，且播种值是「上一笔已应用 +1」")
    void sequentialClaims_areMonotonicAndSeedOnce() {
        String address = freshAddress("SEQ");
        AtomicInteger seedCalls = new AtomicInteger();
        NonceAllocator a = allocator();

        // 节点侧语义（非推测）：HatchServiceImpl.getNonce 返回 best block 上 account.nonce，
        // 而 MerkleRule 在应用交易时 setNonce(tran.nonce) ⇒ 那是**上一笔已应用**的 nonce；
        // PackageMiner 又要求 tx.nonce > account.nonce（>= 即丢弃）⇒ 下一个可用值必须是 chain+1。
        long n0 = a.claim(address, () -> {
            seedCalls.incrementAndGet();
            return 100L;
        });
        long n1 = a.claim(address, () -> {
            seedCalls.incrementAndGet();
            return 999L;
        });
        long n2 = a.claim(address, () -> {
            seedCalls.incrementAndGet();
            return 999L;
        });

        assertEquals(101L, n0, "首次认领必须发 chain+1：直接发 100 会被节点以 nonce 不递增为由丢弃");
        assertEquals(102L, n1);
        assertEquals(103L, n2);
        assertEquals(1, seedCalls.get(), "RPC 播种只在该地址首次（哨兵态）发生，平时不得再打链");
    }

    @Test
    @DisplayName("2.两个 allocator 实例（=多副本形状）并发认领 16 次，零重号")
    void twoAllocators_concurrentClaims_neverHandOutTheSameNonce() throws Exception {
        String address = freshAddress("CONC");
        // 先单线程播一次种，把"并发首次建行"这条路径交给用例 1/4 覆盖，
        // 这里要测的是「已播种水位上的并发认领是否串行」
        allocator().claim(address, () -> 500L);

        NonceAllocator replicaA = allocator();   // 模拟 pod A 的 bean
        NonceAllocator replicaB = allocator();   // 模拟 pod B 的 bean（同一个库、不同 JVM 实例）

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Long> allocated = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                final NonceAllocator which = (i % 2 == 0) ? replicaA : replicaB;
                pool.submit(() -> {
                    try {
                        start.await();
                        allocated.add(which.claim(address, () -> 500L));
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发认领超时（疑似锁等待死循环）");
        } finally {
            pool.shutdownNow();
        }

        assertTrue(errors.isEmpty(), "并发认领不应抛错，但出现了: " + errors);
        Set<Long> distinct = new HashSet<>(allocated);
        assertEquals(threads, distinct.size(),
                "16 次并发认领必须拿到 16 个互不相同的 nonce（旧的读式分配在这里会重号）; 实得=" + distinct);
        assertEquals(threads, allocated.size());
        long min = Collections.min(allocated);
        long max = Collections.max(allocated);
        assertEquals(threads - 1, max - min, "连续水位应无空洞: min=" + min + " max=" + max);
    }

    @Test
    @DisplayName("3.不同 address 的水位互不干扰")
    void differentAddresses_haveIndependentWatermarks() {
        String a = freshAddress("A1");
        String b = freshAddress("B1");
        NonceAllocator alloc = allocator();

        assertEquals(11L, alloc.claim(a, () -> 10L), "A 首次发放 = chain(10)+1");
        assertEquals(701L, alloc.claim(b, () -> 700L), "B 的水位独立，不受 A 影响");
        assertEquals(12L, alloc.claim(a, () -> 10L), "A 的第二次认领继续在自己的水位上前进");
    }

    @Test
    @DisplayName("4.播种取链上值失败 → 抛 NonceAllocationException（fail-closed，绝不回退进程内计数）")
    void seedFailure_failsClosedInsteadOfSilentFallback() {
        String address = freshAddress("SEEDFAIL");
        NonceAllocator alloc = allocator();

        assertThrows(NonceAllocator.NonceAllocationException.class,
                () -> alloc.claim(address, () -> {
                    throw new IllegalStateException("RPC getNonce 返回 code=5000");
                }),
                "取不到链上 nonce 时必须拒绝发放，而不是退回每副本一份的内存计数");

        // 且失败不留半成品水位：修好后该地址应仍以链上值正常播种（chain+1）
        long ok = alloc.claim(address, () -> 42L);
        assertEquals(43L, ok, "播种失败不应污染水位（哨兵仍在，重试即恢复）");
    }

    @Test
    @DisplayName("5.外部消耗过 nonce（链更靠后）⇒ 水位向前校正后发放新值，不会一直发被节点丢弃的旧号")
    void externalConsumption_bumpsWatermarkForward() {
        NonceTestDbs.H2Harness h = NonceTestDbs.freshH2Harness("RECON_FWD", 3600L);
        NonceAllocator alloc = h.allocator();
        String address = freshAddress("FWD");
        AtomicInteger seedCalls = new AtomicInteger();

        assertEquals(51L, alloc.claim(address, () -> {
            seedCalls.incrementAndGet();
            return 50L;
        }), "首次发放 = chain+1");
        assertEquals(52L, h.watermark(address), "发完 51 后水位应是 52");

        // 模拟"分配器之外"的消耗：链上上一笔已应用变成 80（外部/人工补发了 30 笔）
        h.forceReconcileDue(address);
        long allocated = alloc.claim(address, () -> {
            seedCalls.incrementAndGet();
            return 80L;
        });

        assertEquals(81L, allocated, "校正后必须发链上之后的号（80+1），而不是继续发 52（会被节点丢弃）");
        assertEquals(82L, h.watermark(address));
        assertEquals(2, seedCalls.get(), "播种 1 次 + 校正 1 次，不多打");
    }

    @Test
    @DisplayName("6.未到校正周期 ⇒ 绝不打 RPC（否则把 #33 省掉的开销原样加回来）")
    void notDue_mustNotCallChain() {
        NonceTestDbs.H2Harness h = NonceTestDbs.freshH2Harness("RECON_DUE", 3600L);
        NonceAllocator alloc = h.allocator();
        String address = freshAddress("DUE");
        AtomicInteger calls = new AtomicInteger();

        alloc.claim(address, () -> {
            calls.incrementAndGet();
            return 50L;
        });
        long second = alloc.claim(address, () -> {
            calls.incrementAndGet();
            return 9999L;
        });
        long third = alloc.claim(address, () -> {
            calls.incrementAndGet();
            return 9999L;
        });

        assertEquals(52L, second);
        assertEquals(53L, third);
        assertEquals(1, calls.get(), "除首次播种外，一个周期内不得再取链上值");
    }

    @Test
    @DisplayName("7.链上值落后于水位（有在飞交易）⇒ 校正只前进不后退，水位绝不被调回")
    void chainBehind_neverMovesWatermarkBackwards() {
        NonceTestDbs.H2Harness h = NonceTestDbs.freshH2Harness("RECON_BACK", 3600L);
        NonceAllocator alloc = h.allocator();
        String address = freshAddress("BACK");

        assertEquals(51L, alloc.claim(address, () -> 50L));
        long before = h.watermark(address);

        h.forceReconcileDue(address);
        long allocated = alloc.claim(address, () -> 20L); // 链上「上一笔已应用」远小于水位（在飞交易未上链）

        assertEquals(52L, allocated, "水位高于链时必须照常用自己的水位，不能退回 21");
        assertEquals(53L, h.watermark(address));
        // before 是"第一个未被发放的值"（51 发出后=52），本次发放正是它 ⇒ 相等才是对的；
        // 这条用例真正要守的是"链上更旧时水位不被调回"，由上一行的 53 与新发放值 52 共同证明。
        assertEquals(52L, before, "校正未生效时水位保持原样（未被 chain=20 拉低）");
    }

    @Test
    @DisplayName("8.校正时 RPC 失败 ⇒ 只跳过本周期校正，不阻断发放（也不静默改水位）")
    void reconcileRpcFailure_doesNotBlockIssuance() {
        NonceTestDbs.H2Harness h = NonceTestDbs.freshH2Harness("RECON_FAIL", 3600L);
        NonceAllocator alloc = h.allocator();
        String address = freshAddress("FAIL");

        assertEquals(51L, alloc.claim(address, () -> 50L));
        h.forceReconcileDue(address);

        long allocated = alloc.claim(address, () -> {
            throw new IllegalStateException("RPC 超时");
        });

        assertEquals(52L, allocated, "校正失败应退化为「按既有水位继续发放」，而不是把这笔交易打死");
        assertEquals(53L, h.watermark(address), "失败的校正不得改水位");
    }
}
