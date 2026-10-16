package org.nexus.signing.pool;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager tm;

    @BeforeAll
    static void createSchemaFromRealMigration() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        // MODE=MySQL：与 prod(MySQL8) 同语义跑条件 UPDATE / 主键；DB_CLOSE_DELAY 保证同 JVM 内共享库
        ds.setUrl("jdbc:h2:mem:nc_nonce_test_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");

        try (InputStream in = NonceAllocatorH2Test.class.getResourceAsStream(
                "/db/migration/V3__nonce_allocation.sql")) {
            assertTrue(in != null, "找不到 V3__nonce_allocation.sql（迁移没进 classpath？）");
            String ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // 去掉注释行后按语句执行（脚本里只有一条 CREATE TABLE）
            String body = ddl.lines()
                    .filter(l -> !l.strip().startsWith("--"))
                    .collect(java.util.stream.Collectors.joining("\n"));
            new JdbcTemplate(ds).execute(body.strip());
        }
        jdbc = new JdbcTemplate(ds);
        tm = new DataSourceTransactionManager(ds);
    }

    private NonceAllocator allocator() {
        return new NonceAllocator(jdbc, tm);
    }

    private static String freshAddress(String tag) {
        return "0x" + tag + System.nanoTime();
    }

    @Test
    @DisplayName("1.连续认领严格递增；链上播种只发生一次")
    void sequentialClaims_areMonotonicAndSeedOnce() {
        String address = freshAddress("SEQ");
        AtomicInteger seedCalls = new AtomicInteger();
        NonceAllocator a = allocator();

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

        assertEquals(100L, n0, "首次认领应发链上值本身");
        assertEquals(101L, n1);
        assertEquals(102L, n2);
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

        assertEquals(10L, alloc.claim(a, () -> 10L));
        assertEquals(700L, alloc.claim(b, () -> 700L));
        assertEquals(11L, alloc.claim(a, () -> 10L), "A 的水位不受 B 影响");
    }

    @Test
    @DisplayName("4.播种取链上值失败 → 抛 NonceAllocationException（fail-closed，绝不回退进程内计数）")
    void seedFailure_failsClosedInsteadOfSilentFallback() {
        String address = freshAddress("SEEDFAIL");
        NonceAllocator alloc = allocator();

        NonceAllocator.NonceAllocationException ex = assertThrows(
                NonceAllocator.NonceAllocationException.class,
                () -> alloc.claim(address, () -> {
                    throw new IllegalStateException("RPC getNonce 返回 code=5000");
                }),
                "取不到链上 nonce 时必须拒绝发放，而不是退回每副本一份的内存计数");

        // 且失败不留半成品水位：修好后该地址应仍以链上值正常播种
        long ok = alloc.claim(address, () -> 42L);
        assertEquals(42L, ok, "播种失败不应污染水位（哨兵仍在，重试即恢复）");
    }
}
