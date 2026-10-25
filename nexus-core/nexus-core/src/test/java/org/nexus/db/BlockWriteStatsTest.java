package org.nexus.db;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区块写库失败聚合统计测试（PLAN-039）。
 *
 * <p>锁定：初始零值；写失败累计与"最近异常"取最新；超时与写失败分桶计数。</p>
 */
class BlockWriteStatsTest {

    @Test
    void freshStatsAreZeroed() {
        BlockWriteStats stats = new BlockWriteStats();
        Map<String, Object> snap = stats.snapshot();
        assertEquals(0L, snap.get("writeFailures"));
        assertEquals(0L, snap.get("stateUpdateTimeouts"));
        assertEquals(-1L, snap.get("lastFailureHeight"), "无异常高度为 -1");
        assertEquals(0L, snap.get("lastFailureAtMillis"), "无异常时刻为 0");
    }

    @Test
    void writeFailuresAccumulateAndTrackLatest() {
        BlockWriteStats stats = new BlockWriteStats();
        stats.recordWriteFailure(100);
        stats.recordWriteFailure(101);

        Map<String, Object> snap = stats.snapshot();
        assertEquals(2L, snap.get("writeFailures"));
        assertEquals(101L, snap.get("lastFailureHeight"), "最近异常高度取最新一次");
        assertTrue(((Number) snap.get("lastFailureAtMillis")).longValue() > 0L);
    }

    @Test
    void timeoutsCountedSeparatelyFromWriteFailures() {
        BlockWriteStats stats = new BlockWriteStats();
        stats.recordStateUpdateTimeout(200);

        assertEquals(0L, stats.writeFailures(), "超时不进写失败计数");
        assertEquals(1L, stats.stateUpdateTimeouts());
        assertEquals(200L, stats.snapshot().get("lastFailureHeight"), "超时同样更新最近异常");
    }
}
