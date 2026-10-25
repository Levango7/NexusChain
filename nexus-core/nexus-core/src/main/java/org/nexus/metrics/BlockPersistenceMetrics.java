package org.nexus.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.nexus.core.PendingBlocksManager;
import org.nexus.db.BlockWriteStats;
import org.nexus.db.StateDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 区块持久化指标（PLAN-040 / roadmap #29 ③ 告警对接）。
 *
 * <p>把 PLAN-039 的端点信号接进 Micrometer，暴露到
 * {@code /actuator/prometheus}，供
 * {@code deploy/k8s/50-prometheus-rules.yaml} 的
 * {@code nexuschain.block-persistence} 告警组消费。</p>
 *
 * <h2>抓取安全（本类最重要的设计）</h2>
 * <ul>
 *   <li><b>写失败/状态超时/最后失败高度</b>是纯内存读（{@link BlockWriteStats}），
 *       抓取时零数据库访问；</li>
 *   <li><b>outbox 行数/年龄/重试次数</b>需要查库，放在
 *       {@link #refreshOutboxSnapshot()} 的 30s 定时采样里；Gauge 只读 volatile
 *       缓存。这样 PG 抖动时 {@code /actuator/prometheus} <b>不会</b>被一个
 *       Gauge 阻塞——否则整页抓取超时会把 {@code nexuschain_block_height}
 *       和链停摆告警一起打哑；</li>
 *   <li>采样前 outbox 系列返回 <b>NaN</b>（不是 0），避免"还没采到"被误读成
 *       "队列为空"；采样失败保留上一次成功值（缓存不清零），由
 *       {@code nexus_block_outbox_readable=0} 表达"现在读不到"。</li>
 * </ul>
 *
 * <h2>指标</h2>
 * <table>
 *   <tr><th>指标</th><th>类型</th><th>语义</th></tr>
 *   <tr><td>{@code nexus_block_writer_write_failures_total}</td>
 *       <td>FunctionCounter</td><td>写入失败/跳块的尝试次数（同一高度重试重复计数）</td></tr>
 *   <tr><td>{@code nexus_block_writer_state_update_timeouts_total}</td>
 *       <td>FunctionCounter</td><td>pendingBlock 等待超时被强制清空的次数</td></tr>
 *   <tr><td>{@code nexus_block_writer_last_failure_height}</td>
 *       <td>Gauge</td><td>最近一次失败尝试的高度（无失败为 -1）</td></tr>
 *   <tr><td>{@code nexus_block_writer_last_failure_age_seconds}</td>
 *       <td>Gauge</td><td>距最近一次失败尝试的秒数（无失败为 NaN）</td></tr>
 *   <tr><td>{@code nexus_block_outbox_enabled}</td>
 *       <td>Gauge</td><td>outbox 是否装配（0/1）</td></tr>
 *   <tr><td>{@code nexus_block_outbox_memory_pending}</td>
 *       <td>Gauge</td><td>存储故障后的内存重试队列长度</td></tr>
 *   <tr><td>{@code nexus_block_outbox_database_pending}</td>
 *       <td>Gauge</td><td>持久化 outbox 待补写行数（读不到为 NaN）</td></tr>
 *   <tr><td>{@code nexus_block_outbox_oldest_pending_age_seconds}</td>
 *       <td>Gauge</td><td>最老滞留行年龄（无滞留/读不到为 NaN）</td></tr>
 *   <tr><td>{@code nexus_block_outbox_max_attempts}</td>
 *       <td>Gauge</td><td>单行最大重试次数（无滞留/读不到为 NaN）</td></tr>
 *   <tr><td>{@code nexus_block_outbox_readable}</td>
 *       <td>Gauge</td><td>1=可读或未装配；0=已装配但读不到；首次采样前 NaN</td></tr>
 * </table>
 *
 * <h2>线程安全</h2>
 * <p>Gauge 供值函数在 Prometheus 抓取线程执行，只读 volatile 字段；
 * 采样在 Spring 调度线程执行（{@code task-*}），写 volatile。没有共享可变集合。</p>
 *
 * @since 2.77.0
 */
@Component
public class BlockPersistenceMetrics implements MeterBinder {

    private static final Logger logger = LoggerFactory.getLogger(BlockPersistenceMetrics.class);

    private final StateDB stateDB;
    private final PendingBlocksManager pendingBlocksManager;

    /** 是否已经成功采样过一次 outbox（采样前所有 outbox 指标为 NaN）。 */
    private volatile boolean outboxSampled;

    /** outbox 是否装配（来自 outboxStatus().enabled）。 */
    private volatile boolean outboxEnabled;

    /** 内存重试队列长度（outboxStatus().memoryPending）。 */
    private volatile long outboxMemoryPending;

    /** 持久化 outbox 待补写行数；-1 = 读不到。 */
    private volatile long outboxDatabasePending = -1L;

    /** 最老滞留行的 created_at（epoch millis）；-1 = 无滞留或读不到。 */
    private volatile long outboxOldestCreatedAt = -1L;

    /** 单行最大重试次数；-1 = 无滞留或读不到。 */
    private volatile long outboxMaxAttempts = -1L;

    public BlockPersistenceMetrics(StateDB stateDB, PendingBlocksManager pendingBlocksManager) {
        this.stateDB = stateDB;
        this.pendingBlocksManager = pendingBlocksManager;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        BlockWriteStats stats = stateDB.blockWriteStats();

        FunctionCounter.builder("nexus_block_writer_write_failures", stats, BlockWriteStats::writeFailures)
                .description("Block write failure/skip attempts (same height retried in later confirmation rounds counts repeatedly)")
                .register(registry);
        FunctionCounter.builder("nexus_block_writer_state_update_timeouts", stats, BlockWriteStats::stateUpdateTimeouts)
                .description("Account-state update waits that timed out and cleared pendingBlock")
                .register(registry);
        Gauge.builder("nexus_block_writer_last_failure_height", stats, s -> (double) s.lastFailureHeight())
                .description("Height of the most recent failed write attempt (-1 = none since process start)")
                .register(registry);
        Gauge.builder("nexus_block_writer_last_failure_age_seconds", stats, s -> {
                    long at = s.lastFailureAtMillis();
                    return at == 0L ? Double.NaN : (System.currentTimeMillis() - at) / 1000.0;
                })
                .description("Seconds since the most recent failed write attempt (NaN = none since process start)")
                .register(registry);

        Gauge.builder("nexus_block_outbox_enabled", this,
                        m -> m.outboxSampled ? (m.outboxEnabled ? 1.0 : 0.0) : Double.NaN)
                .description("Whether the persistent block outbox is wired (1/0; NaN before first sample)")
                .register(registry);
        Gauge.builder("nexus_block_outbox_memory_pending", this,
                        m -> m.outboxSampled ? (double) m.outboxMemoryPending : Double.NaN)
                .description("In-memory block retry queue size after storage failures (NaN before first sample)")
                .register(registry);
        Gauge.builder("nexus_block_outbox_database_pending", this,
                        m -> m.outboxSampled && m.outboxDatabasePending >= 0L
                                ? (double) m.outboxDatabasePending : Double.NaN)
                .description("Rows pending in the persistent block outbox (NaN = unreadable/not sampled)")
                .register(registry);
        Gauge.builder("nexus_block_outbox_oldest_pending_age_seconds", this,
                        m -> m.outboxSampled && m.outboxOldestCreatedAt > 0L
                                ? (System.currentTimeMillis() - m.outboxOldestCreatedAt) / 1000.0 : Double.NaN)
                .description("Age of the oldest pending outbox row (NaN = no pending rows/unreadable/not sampled)")
                .register(registry);
        Gauge.builder("nexus_block_outbox_max_attempts", this,
                        m -> m.outboxSampled && m.outboxMaxAttempts >= 0L
                                ? (double) m.outboxMaxAttempts : Double.NaN)
                .description("Max retry attempts among pending outbox rows (NaN = no pending rows/unreadable/not sampled)")
                .register(registry);
        Gauge.builder("nexus_block_outbox_readable", this, m -> {
                    if (!m.outboxSampled) {
                        return Double.NaN;
                    }
                    // 未装配不算故障（没有库可读）；装配了但 databasePending<0 才是读不到。
                    return !m.outboxEnabled || m.outboxDatabasePending >= 0L ? 1.0 : 0.0;
                })
                .description("1 = outbox readable or not wired; 0 = wired but database unreadable; NaN before first sample")
                .register(registry);
    }

    /**
     * 采样 outbox 快照（30s 一次；Gauge 只读缓存，抓取不查库）。
     *
     * <p>{@link PendingBlocksManager#outboxStatus()} 自身已逐项吞掉数据库异常
     * （不可读时返回 {@code databasePending=-1}）；这里的 catch 只防"方法本身
     * 抛未预期异常"把调度线程打挂——保留上一次成功快照，等待下一轮。</p>
     */
    @Scheduled(
            initialDelayString = "${nexus.metrics.block-outbox.initial-delay-ms:10000}",
            fixedDelayString = "${nexus.metrics.block-outbox.refresh-ms:30000}")
    public void refreshOutboxSnapshot() {
        try {
            Map<String, Object> status = pendingBlocksManager.outboxStatus();
            outboxEnabled = Boolean.TRUE.equals(status.get("enabled"));
            outboxMemoryPending = longValue(status.get("memoryPending"), 0L);
            outboxDatabasePending = longValue(status.get("databasePending"), -1L);
            outboxOldestCreatedAt = longValue(status.get("oldestPendingCreatedAt"), -1L);
            outboxMaxAttempts = longValue(status.get("maxAttempts"), -1L);
            outboxSampled = true;
        } catch (RuntimeException e) {
            logger.warn("BlockPersistenceMetrics: outbox snapshot failed, keeping last sample: {}", e.getMessage());
        }
    }

    private static long longValue(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }
}
