package org.nexus.bridge.safety;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内日限额用量存储测试（PLAN-038）。
 *
 * <p>时间全部显式传入（包级构造器设定窗口起点）——窗口滚动语义可确定性验证。</p>
 */
class InMemoryDailyUsageStoreTest {

    private static final long T0 = 1_700_000_000_000L;

    private InMemoryDailyUsageStore store() {
        return new InMemoryDailyUsageStore(T0);
    }

    @Test
    void reserveAccumulatesAndEnforcesStrictlyGreaterLimit() {
        InMemoryDailyUsageStore store = store();
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
        InMemoryDailyUsageStore store = store();
        store.tryReserve(30L, 100L, T0 + 1_000);
        store.release(50L, T0 + 2_000);
        assertEquals(0L, store.currentUsed(T0 + 3_000), "归还超过已用下限钳制为 0");
    }

    @Test
    void expiredWindowReadsZeroAndRollsOnNextWrite() {
        InMemoryDailyUsageStore store = store();
        store.tryReserve(90L, 100L, T0 + 1_000);

        long afterWindow = T0 + DailyUsageStore.WINDOW_MILLIS + 1_000;
        assertEquals(0L, store.currentUsed(afterWindow), "过窗后纯读为 0");

        assertTrue(store.tryReserve(90L, 100L, afterWindow + 1_000),
                "过窗后配额重置：90/100 可再次预留");
        assertEquals(90L, store.currentUsed(afterWindow + 2_000));
    }

    @Test
    void releaseAfterWindowExpiryIsNoOp() {
        InMemoryDailyUsageStore store = store();
        store.tryReserve(90L, 100L, T0 + 1_000);

        long afterWindow = T0 + DailyUsageStore.WINDOW_MILLIS + 1_000;
        store.release(90L, afterWindow); // 旧窗口余量已作废，归还为空操作

        assertTrue(store.tryReserve(100L, 100L, afterWindow + 1_000),
                "归还空操作后，滚动窗口处应可满额预留");
    }
}
