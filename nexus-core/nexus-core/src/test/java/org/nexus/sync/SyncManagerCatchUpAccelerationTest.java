package org.nexus.sync;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-033（切片 A）：grpc 大缺口追块加速的判定纯函数测试。
 *
 * <p>锁定三条边界：小缺口不加速（常规 30s 周期足够）；大缺口且无在途 ⇒ 加速；
 * 在途窗口未超时 ⇒ 不重复发（在途 ≤1），超时（丢响应）⇒ 放行重试。</p>
 */
class SyncManagerCatchUpAccelerationTest {

    private SyncManager manager() {
        SyncManager m = new SyncManager();
        ReflectionTestUtils.setField(m, "maxBlocksPerTransfer", 256);
        return m;
    }

    @Test
    void smallGapDoesNotAccelerate() {
        SyncManager m = manager();
        assertFalse(m.shouldAccelerateCatchUp(100L, 100L + 512L, false, 0L, 1_000_000L),
                "差距 == 2×窗口：不加速");
        assertFalse(m.shouldAccelerateCatchUp(100L, 100L + 100L, false, 0L, 1_000_000L));
    }

    @Test
    void largeGapWithoutInFlightAccelerates() {
        SyncManager m = manager();
        assertTrue(m.shouldAccelerateCatchUp(100L, 100L + 513L, false, 0L, 1_000_000L),
                "差距 > 2×窗口且无在途：加速");
    }

    @Test
    void freshInFlightBlocksAcceleration() {
        SyncManager m = manager();
        assertFalse(m.shouldAccelerateCatchUp(100L, 5_000L, true, 1_000_000L, 1_019_000L),
                "在途窗口未超时：不重复拉（在途 ≤1）");
    }

    @Test
    void staleInFlightAllowsRetry() {
        SyncManager m = manager();
        assertTrue(m.shouldAccelerateCatchUp(100L, 5_000L, true, 1_000_000L, 1_025_000L),
                "在途超时（丢响应）：放行重试");
    }

    @Test
    void thresholdBoundaryIsStrict() {
        SyncManager m = manager();
        assertFalse(m.shouldAccelerateCatchUp(0L, 512L, false, 0L, 0L));
        assertTrue(m.shouldAccelerateCatchUp(0L, 513L, false, 0L, 0L));
    }
}
