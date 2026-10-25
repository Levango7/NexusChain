package org.nexus.bridge.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.bridge.repository.BridgeDailyUsageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日限额用量落库实现测试（PLAN-038）。
 *
 * <p>核心断言：语义与 {@link InMemoryDailyUsageStore} 对齐（预留/归还/窗口滚动/
 * 严格大于谓词），且状态<b>跨实例存在</b>（等价"重启不清零"）——用"同一
 * Repository 上新建第二个 store 实例"模拟重启；并验证多副本可见性（全局限额的
 * 前提）。</p>
 *
 * <p>注意：类级 {@code NOT_SUPPORTED} 关闭 @DataJpaTest 的默认测试事务——store
 * 的写操作按设计走 REQUIRES_NEW 独立提交，外层测试事务会干扰其可见性。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcDailyUsageStoreTest {

    private static final long T0 = 1_700_000_000_000L;

    @Autowired
    private BridgeDailyUsageRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcDailyUsageStore store;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        store = new JdbcDailyUsageStore(repository, transactionManager);
    }

    @Test
    void reserveAccumulatesAndEnforcesStrictlyGreaterLimit() {
        assertTrue(store.tryReserve(40L, 100L, T0 + 1_000));
        assertTrue(store.tryReserve(40L, 100L, T0 + 2_000));
        assertEquals(80L, store.currentUsed(T0 + 3_000));

        assertFalse(store.tryReserve(30L, 100L, T0 + 4_000), "80+30>100 应拒绝");
        assertEquals(80L, store.currentUsed(T0 + 5_000), "拒绝不得产生预留");

        assertTrue(store.tryReserve(20L, 100L, T0 + 6_000), "恰好到上限允许（历史谓词为 > 才拒绝）");
        assertEquals(100L, store.currentUsed(T0 + 7_000));
    }

    @Test
    void releaseClampsAtZero() {
        store.tryReserve(30L, 100L, T0 + 1_000);
        store.release(50L, T0 + 2_000);
        assertEquals(0L, store.currentUsed(T0 + 3_000), "归还超过已用下限钳制为 0");
    }

    @Test
    void expiredWindowReadsZeroAndRollsOnNextWrite() {
        long windowStart = T0 + 1_000;
        store.tryReserve(90L, 100L, windowStart);
        long afterWindow = windowStart + DailyUsageStore.WINDOW_MILLIS + 1_000;

        assertEquals(0L, store.currentUsed(afterWindow), "过窗后纯读为 0");

        assertTrue(store.tryReserve(90L, 100L, afterWindow + 1_000), "过窗后配额重置");
        assertEquals(90L, store.currentUsed(afterWindow + 2_000));
    }

    @Test
    void usageSurvivesStoreReconstructionAndIsSharedAcrossInstances() {
        store.tryReserve(77L, 100L, T0 + 1_000);

        // 模拟重启：同一 Repository 上新建 store 实例
        JdbcDailyUsageStore restarted = new JdbcDailyUsageStore(repository, transactionManager);
        assertEquals(77L, restarted.currentUsed(T0 + 2_000), "重启后已用量必须保留（历史内存实现会清零）");

        // 模拟多副本：另一实例继续预留——额度全局共享
        assertFalse(restarted.tryReserve(30L, 100L, T0 + 3_000), "77+30>100 在新实例上同样拒绝");
        assertTrue(restarted.tryReserve(23L, 100L, T0 + 4_000));
        assertEquals(100L, store.currentUsed(T0 + 5_000), "原实例也看到最新用量");
    }

    @Test
    void releaseAfterWindowExpiryIsNoOp() {
        long windowStart = T0 + 1_000;
        store.tryReserve(90L, 100L, windowStart);
        long afterWindow = windowStart + DailyUsageStore.WINDOW_MILLIS + 1_000;

        store.release(90L, afterWindow); // 旧窗口余量已作废（无落库效果）

        assertTrue(store.tryReserve(100L, 100L, afterWindow + 1_000),
                "归还空操作后，滚动窗口处应可满额预留");
    }
}
