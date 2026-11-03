package org.nexus.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.FinalityCoordinator;
import org.nexus.consensus.finality.FinalityOutboxManager;
import org.nexus.consensus.finality.FinalityOutboxStatus;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 最终性 outbox 指标测试（PLAN-073）。
 *
 * <p>锁定：① 采样前状态型指标是 NaN（不冒充 0）；② 可读快照的精确映射
 * （积压 100 行不能被 LIMIT 64 低报）；③ 读不到库 ⇒ database_pending=NaN +
 * readable=0，内存队列仍可读；④ 未装配 ⇒ enabled=0、readable=1；
 * ⑤ 采样异常保留上一次成功值；⑥ pending 的标签键集合严格等于 {@code origin}，
 * 取值只有 local/received（PLAN-070 的高基数教训）。</p>
 */
class FinalityOutboxMetricsTest {

    private FinalityOutboxManager manager;
    private FinalityCoordinator coordinator;
    private FinalityOutboxMetrics metrics;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        manager = mock(FinalityOutboxManager.class);
        coordinator = mock(FinalityCoordinator.class);
        metrics = new FinalityOutboxMetrics(manager, coordinator);
        registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double pending(String origin) {
        return registry.get("nexus_finality_outbox_pending").tag("origin", origin).gauge().value();
    }

    private double counter(String name) {
        return registry.get(name).functionCounter().count();
    }

    private static FinalityOutboxStatus status(int local, int received, long database,
                                               long enqueued, long flushed, long failed,
                                               long dropped, boolean readable) {
        return new FinalityOutboxStatus(local, received, database, 0L, 0L,
                enqueued, flushed, failed, dropped, readable, "");
    }

    @Test
    void outboxValuesAreNaNBeforeFirstSample() {
        assertTrue(Double.isNaN(gauge("nexus_finality_outbox_enabled")));
        assertTrue(Double.isNaN(pending("local")));
        assertTrue(Double.isNaN(pending("received")));
        assertTrue(Double.isNaN(gauge("nexus_finality_outbox_database_pending")));
        assertTrue(Double.isNaN(gauge("nexus_finality_outbox_database_readable")),
                "首次采样前不能冒充「可读」");

        // 计数器直接读 manager，不依赖采样；未配置时必须是 0 而不是缺失。
        assertEquals(0.0, counter("nexus_finality_outbox_enqueued"));
        assertEquals(0.0, counter("nexus_finality_outbox_flushed"));
        assertEquals(0.0, counter("nexus_finality_outbox_failed"));
        assertEquals(0.0, counter("nexus_finality_outbox_dropped"));
    }

    @Test
    void pendingGaugeHasOnlyBoundedOriginTag() {
        Set<String> keys = new TreeSet<>();
        for (Meter meter : registry.get("nexus_finality_outbox_pending").meters()) {
            for (Tag tag : meter.getId().getTags()) {
                keys.add(tag.getKey());
            }
        }
        assertEquals(Set.of("origin"), keys,
                "pending 只能有 origin 标签；任何额外标签都可能引入高基数");

        Set<String> values = registry.get("nexus_finality_outbox_pending").meters().stream()
                .map(meter -> meter.getId().getTag("origin"))
                .collect(Collectors.toSet());
        assertEquals(Set.of("local", "received"), values,
                "origin 取值必须恰好是 local/received（编译器/枚举保证有界）");

        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName();
            if (name.startsWith("nexus_finality_outbox_")
                    && !name.equals("nexus_finality_outbox_pending")) {
                assertTrue(meter.getId().getTags().isEmpty(),
                        name + " 不应带标签，实际 = " + meter.getId().getTags());
            }
        }
    }

    @Test
    void mapsReadableSnapshotExactly() {
        when(manager.status()).thenReturn(status(2, 3, 100L, 11L, 7L, 5L, 1L, true));
        when(manager.totalEnqueued()).thenReturn(11L);
        when(manager.totalFlushed()).thenReturn(7L);
        when(manager.totalFailed()).thenReturn(5L);
        when(manager.totalDropped()).thenReturn(1L);

        metrics.refreshOutboxSnapshot();

        assertEquals(1.0, gauge("nexus_finality_outbox_enabled"));
        assertEquals(2.0, pending("local"));
        assertEquals(3.0, pending("received"));
        assertEquals(100.0, gauge("nexus_finality_outbox_database_pending"),
                "积压 100 行必须报 100，不能被 LIMIT 64 低报");
        assertEquals(1.0, gauge("nexus_finality_outbox_database_readable"));
        assertEquals(11.0, counter("nexus_finality_outbox_enqueued"));
        assertEquals(7.0, counter("nexus_finality_outbox_flushed"));
        assertEquals(5.0, counter("nexus_finality_outbox_failed"));
        assertEquals(1.0, counter("nexus_finality_outbox_dropped"));
    }

    @Test
    void unreadableDatabaseIsNaNNotZero() {
        when(manager.status()).thenReturn(status(5, 0, 0L, 0L, 0L, 0L, 0L, false));

        metrics.refreshOutboxSnapshot();

        assertEquals(5.0, pending("local"), "内存队列是本地状态，库读不到也应可读");
        assertTrue(Double.isNaN(gauge("nexus_finality_outbox_database_pending")),
                "读不到必须是 NaN，不能是 0（0 会被误读成已排空）");
        assertEquals(0.0, gauge("nexus_finality_outbox_database_readable"));
        assertEquals(1.0, gauge("nexus_finality_outbox_enabled"));
    }

    @Test
    void notWiredIsNotAnError() {
        SimpleMeterRegistry fresh = new SimpleMeterRegistry();
        FinalityOutboxMetrics unwired = new FinalityOutboxMetrics(null, null);
        unwired.bindTo(fresh);
        unwired.refreshOutboxSnapshot();

        assertEquals(0.0, fresh.get("nexus_finality_outbox_enabled").gauge().value());
        assertEquals(1.0, fresh.get("nexus_finality_outbox_database_readable").gauge().value(),
                "未装配 = 没有库可读，不算故障");
        assertTrue(Double.isNaN(
                fresh.get("nexus_finality_outbox_database_pending").gauge().value()));
        assertEquals(0.0, fresh.get("nexus_finality_outbox_pending")
                .tag("origin", "local").gauge().value());
        assertEquals(0.0, fresh.get("nexus_finality_outbox_dropped").functionCounter().count());
    }

    /**
     * 非 pos 模式：manager 仍是无条件 {@code @Component}，但 coordinator 不在容器里。
     * 此时不能去读库，否则 PG 故障会让 {@code FinalityOutboxUnreadable} 在
     * 根本没有最终性的部署里误报。
     */
    @Test
    void nonPosModeDoesNotReportUnreadable() {
        SimpleMeterRegistry fresh = new SimpleMeterRegistry();
        FinalityOutboxMetrics nonPos = new FinalityOutboxMetrics(manager, null);
        nonPos.bindTo(fresh);
        // 即使 manager 会返回「读不到」，也不应该被采样。
        when(manager.status()).thenReturn(status(5, 0, 0L, 0L, 0L, 0L, 0L, false));

        nonPos.refreshOutboxSnapshot();

        assertEquals(0.0, fresh.get("nexus_finality_outbox_enabled").gauge().value(),
                "非 pos 模式必须 enabled=0");
        assertEquals(1.0, fresh.get("nexus_finality_outbox_database_readable").gauge().value(),
                "非 pos 模式没有库可读，不算故障");
        assertTrue(Double.isNaN(
                fresh.get("nexus_finality_outbox_database_pending").gauge().value()));
        assertEquals(0.0, fresh.get("nexus_finality_outbox_pending")
                .tag("origin", "local").gauge().value());
    }

    @Test
    void samplingFailureKeepsLastSuccessfulSnapshot() {
        when(manager.status()).thenReturn(status(2, 1, 3L, 0L, 0L, 0L, 0L, true));
        metrics.refreshOutboxSnapshot();

        when(manager.status()).thenThrow(new IllegalStateException("unexpected"));
        metrics.refreshOutboxSnapshot();

        assertEquals(2.0, pending("local"), "采样异常不应清零上一次成功值");
        assertEquals(3.0, gauge("nexus_finality_outbox_database_pending"));
        assertEquals(1.0, gauge("nexus_finality_outbox_database_readable"));
        assertFalse(Double.isNaN(gauge("nexus_finality_outbox_enabled")));
    }
}
