package org.nexus.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.core.PendingBlocksManager;
import org.nexus.db.BlockWriteStats;
import org.nexus.db.StateDB;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 区块持久化指标测试（PLAN-040）。
 *
 * <p>锁定：① 写失败/超时是单调 FunctionCounter；② 最近失败高度/年龄反映最新异常；
 * ③ outbox 正常快照映射；④ 读不到库 ⇒ DB 系列 NaN + readable=0；
 * ⑤ 未装配 ⇒ readable=1；⑥ 采样前 outbox 系列 NaN（不冒充 0）；
 * ⑦ 采样异常不抛出、保留上一次成功值。</p>
 */
class BlockPersistenceMetricsTest {

    private StateDB stateDB;
    private BlockWriteStats stats;
    private PendingBlocksManager pendingBlocksManager;
    private BlockPersistenceMetrics metrics;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        stateDB = mock(StateDB.class);
        stats = new BlockWriteStats();
        when(stateDB.blockWriteStats()).thenReturn(stats);
        pendingBlocksManager = mock(PendingBlocksManager.class);
        metrics = new BlockPersistenceMetrics(stateDB, pendingBlocksManager);
        registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double counter(String name) {
        return registry.get(name).functionCounter().count();
    }

    private static Map<String, Object> outboxStatus(int memoryPending, int databasePending,
                                                    long oldestCreatedAt, int maxAttempts, boolean enabled) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("memoryPending", memoryPending);
        data.put("databasePending", databasePending);
        data.put("oldestPendingCreatedAt", oldestCreatedAt);
        data.put("maxAttempts", maxAttempts);
        data.put("enabled", enabled);
        return data;
    }

    @Test
    void exposesWriterCountersAndFailureLevels() {
        assertEquals(0.0, counter("nexus_block_writer_write_failures"));
        assertEquals(0.0, counter("nexus_block_writer_state_update_timeouts"));
        assertEquals(-1.0, gauge("nexus_block_writer_last_failure_height"));
        assertTrue(Double.isNaN(gauge("nexus_block_writer_last_failure_age_seconds")),
                "无失败时年龄必须是 NaN，不能是 0（0 会被误读成刚失败）");

        stats.recordWriteFailure(42L);
        assertEquals(1.0, counter("nexus_block_writer_write_failures"));
        assertEquals(42.0, gauge("nexus_block_writer_last_failure_height"));
        assertTrue(gauge("nexus_block_writer_last_failure_age_seconds") >= 0.0);

        // 同一高度重试重复计数（PLAN-039 口径）——FunctionCounter 是尝试次数
        stats.recordWriteFailure(42L);
        assertEquals(2.0, counter("nexus_block_writer_write_failures"));

        stats.recordStateUpdateTimeout(43L);
        assertEquals(1.0, counter("nexus_block_writer_state_update_timeouts"));
        assertEquals(43.0, gauge("nexus_block_writer_last_failure_height"),
                "状态更新超时同样更新最近异常高度");
    }

    @Test
    void outboxValuesAreNaNBeforeFirstSample() {
        assertTrue(Double.isNaN(gauge("nexus_block_outbox_enabled")));
        assertTrue(Double.isNaN(gauge("nexus_block_outbox_memory_pending")));
        assertTrue(Double.isNaN(gauge("nexus_block_outbox_database_pending")));
        assertTrue(Double.isNaN(gauge("nexus_block_outbox_oldest_pending_age_seconds")));
        assertTrue(Double.isNaN(gauge("nexus_block_outbox_max_attempts")));
        assertTrue(Double.isNaN(gauge("nexus_block_outbox_readable")));
    }

    @Test
    void mapsReadableOutboxSnapshot() {
        long oldest = System.currentTimeMillis() - 60_000L;
        when(pendingBlocksManager.outboxStatus())
                .thenReturn(outboxStatus(7, 3, oldest, 9, true));

        metrics.refreshOutboxSnapshot();

        assertEquals(1.0, gauge("nexus_block_outbox_enabled"));
        assertEquals(7.0, gauge("nexus_block_outbox_memory_pending"));
        assertEquals(3.0, gauge("nexus_block_outbox_database_pending"));
        assertEquals(9.0, gauge("nexus_block_outbox_max_attempts"));
        assertEquals(1.0, gauge("nexus_block_outbox_readable"));
        double age = gauge("nexus_block_outbox_oldest_pending_age_seconds");
        assertTrue(age >= 59.0 && age <= 120.0, "最老行年龄应约等于 60s，实际=" + age);
    }

    @Test
    void unreadableDatabaseIsNaNNotZero() {
        when(pendingBlocksManager.outboxStatus())
                .thenReturn(outboxStatus(5, -1, -1L, -1, true));

        metrics.refreshOutboxSnapshot();

        assertEquals(5.0, gauge("nexus_block_outbox_memory_pending"),
                "内存队列是本地状态，库读不到也应可读");
        assertTrue(Double.isNaN(gauge("nexus_block_outbox_database_pending")),
                "读不到必须是 NaN，不能是 0（0 会被误读成已排空）");
        assertTrue(Double.isNaN(gauge("nexus_block_outbox_oldest_pending_age_seconds")));
        assertTrue(Double.isNaN(gauge("nexus_block_outbox_max_attempts")));
        assertEquals(0.0, gauge("nexus_block_outbox_readable"));
    }

    @Test
    void disabledOutboxIsNotAnError() {
        when(pendingBlocksManager.outboxStatus())
                .thenReturn(outboxStatus(0, -1, -1L, -1, false));

        metrics.refreshOutboxSnapshot();

        assertEquals(0.0, gauge("nexus_block_outbox_enabled"));
        assertEquals(1.0, gauge("nexus_block_outbox_readable"),
                "未装配 = 没有库可读，不算故障");
        assertTrue(Double.isNaN(gauge("nexus_block_outbox_database_pending")));
    }

    @Test
    void samplingFailureKeepsLastSuccessfulSnapshot() {
        when(pendingBlocksManager.outboxStatus())
                .thenReturn(outboxStatus(2, 1, System.currentTimeMillis() - 5_000L, 3, true));
        metrics.refreshOutboxSnapshot();

        when(pendingBlocksManager.outboxStatus()).thenThrow(new IllegalStateException("unexpected"));
        metrics.refreshOutboxSnapshot();

        assertEquals(2.0, gauge("nexus_block_outbox_memory_pending"),
                "采样异常不应清零上一次成功值");
        assertEquals(1.0, gauge("nexus_block_outbox_database_pending"));
        assertEquals(3.0, gauge("nexus_block_outbox_max_attempts"));
        assertEquals(1.0, gauge("nexus_block_outbox_readable"));
    }
}
