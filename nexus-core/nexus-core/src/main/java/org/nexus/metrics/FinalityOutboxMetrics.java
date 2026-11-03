package org.nexus.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.nexus.consensus.finality.FinalityCoordinator;
import org.nexus.consensus.finality.FinalityOutboxManager;
import org.nexus.consensus.finality.FinalityOutboxStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 最终性投票 outbox 的运行指标（PLAN-073）。
 *
 * <p><b>为什么需要</b>：{@code finality_vote_outbox} 是「票写不进库」的最后兜底。
 * 在 PLAN-073 之前，它只有 {@code GET /consensus/finality-outbox} 一个端点，
 * 没人盯屏时完全不可见；而 PG 抖动恰恰发生在没人盯屏的时候。更糟的是端点本身
 * 有两个语义缺陷：积压数被 {@code LIMIT 64} 低报、数据库读不到时被显示成 0
 * （与「已排空」无法区分）。本类把修正后的状态接进 Prometheus。</p>
 *
 * <p><b>指标</b>（{@code /actuator/prometheus}）：</p>
 * <table border="1">
 *   <caption>最终性 outbox 指标</caption>
 *   <tr><th>指标</th><th>类型</th><th>语义</th></tr>
 *   <tr><td>{@code nexus_finality_outbox_enabled}</td>
 *       <td>Gauge</td><td>最终性共识是否装配（1/0；首次采样前 NaN）</td></tr>
 *   <tr><td>{@code nexus_finality_outbox_pending{origin="local"|"received"}}</td>
 *       <td>Gauge</td><td>内存待补写队列长度（首次采样前 NaN）</td></tr>
 *   <tr><td>{@code nexus_finality_outbox_database_pending}</td>
 *       <td>Gauge</td><td>持久化 outbox 的<b>精确</b>行数（读不到为 NaN）</td></tr>
 *   <tr><td>{@code nexus_finality_outbox_database_readable}</td>
 *       <td>Gauge</td><td>1=可读或未装配；0=已装配但读不到；首次采样前 NaN</td></tr>
 *   <tr><td>{@code nexus_finality_outbox_enqueued_total}</td>
 *       <td>FunctionCounter</td><td>入队总数（单调）</td></tr>
 *   <tr><td>{@code nexus_finality_outbox_flushed_total}</td>
 *       <td>FunctionCounter</td><td>补写成功总数（单调）</td></tr>
 *   <tr><td>{@code nexus_finality_outbox_failed_total}</td>
 *       <td>FunctionCounter</td><td>重试失败总数（<b>不含</b>队列满丢票；单调）</td></tr>
 *   <tr><td>{@code nexus_finality_outbox_dropped_total}</td>
 *       <td>FunctionCounter</td><td>队列满丢弃的票数（<b>永久丢失</b>；单调）</td></tr>
 * </table>
 *
 * <p><b>为什么丢票要单独计数</b>：{@code totalFailed} 同时承接「重试失败（还会再试）」
 * 与「队列满丢票（永久丢失）」，前者是噪声、后者是数据丢失。混在一起时丢一张票
 * 会被成千上万次重试失败平均掉，既不可告警也不可复盘。</p>
 *
 * <p><b>抓取纪律</b>（与 {@link BlockPersistenceMetrics} 同一纪律）：
 * 所有 Gauge 只读本类的 volatile 缓存；唯一会访问数据库的
 * {@link FinalityOutboxManager#status()} 只在 30s 定时采样里调用。
 * 否则 PG 卡住时一个 Gauge 回调就能把整页 {@code /actuator/prometheus} 抓取拖死。</p>
 *
 * <p><b>线程安全</b>：Gauge 供值函数在 Prometheus 抓取线程执行，只读 volatile 字段；
 * 采样在 Spring 调度线程执行并写 volatile；计数器直接读 manager 的 AtomicLong。
 * 没有共享可变集合。</p>
 *
 * <p><b>非 pos 模式</b>：{@link FinalityOutboxManager} 是无条件 {@code @Component}，
 * 而 {@link FinalityCoordinator} 带 {@code @ConditionalOnProperty(nexus.consensus.mode=pos)}。
 * 因此「最终性是否真的在跑」以 coordinator 是否存在为准：不存在时
 * {@code enabled=0}、不采样、{@code readable=1}，避免 PG 故障在没有最终性的
 * 部署里误报 {@code FinalityOutboxUnreadable}。</p>
 *
 * @since 2.79.0
 */
@Component
public class FinalityOutboxMetrics implements MeterBinder {

    private static final Logger logger = LoggerFactory.getLogger(FinalityOutboxMetrics.class);

    /** 可为 null：单测或裁剪容器里没有 outbox 时，指标退化为 0/NaN 而不是抛异常。 */
    private final FinalityOutboxManager outboxManager;

    /**
     * 可为 null：{@link FinalityCoordinator} 带
     * {@code @ConditionalOnProperty(nexus.consensus.mode=pos)}，所以「非 null」正是
     * 「最终性共识真的在跑」的判据。不能用 manager 判，因为 manager 是无条件
     * {@code @Component}：非 pos 模式下它照旧存在，若 PG 不可达就会让
     * {@code FinalityOutboxUnreadable} 在没有最终性的部署里误报。
     */
    private final FinalityCoordinator finalityCoordinator;

    /** 是否已经成功采样过一次（采样前所有状态型指标为 NaN，不冒充 0）。 */
    private volatile boolean sampled;

    /** outbox 是否装配。 */
    private volatile boolean enabled;

    /** 本节点签名的待补写票数。 */
    private volatile long localPending;

    /** 从对端收到的待补写票数。 */
    private volatile long receivedPending;

    /** 持久化 outbox 行数；-1 = 读不到或未采样。 */
    private volatile long databasePending = -1L;

    /** 持久化 outbox 是否读得到（未装配视为可读：没有库可读，不算故障）。 */
    private volatile boolean databaseReadable = true;

    public FinalityOutboxMetrics(@Autowired(required = false) FinalityOutboxManager outboxManager,
                                  @Autowired(required = false) FinalityCoordinator finalityCoordinator) {
        this.outboxManager = outboxManager;
        this.finalityCoordinator = finalityCoordinator;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("nexus_finality_outbox_enabled", this,
                        m -> m.sampled ? (m.enabled ? 1.0 : 0.0) : Double.NaN)
                .description("Whether the finality vote outbox is wired (1/0; NaN before first sample)")
                .register(registry);

        Gauge.builder("nexus_finality_outbox_pending", this,
                        m -> m.sampled ? (double) m.localPending : Double.NaN)
                .tag("origin", "local")
                .description("In-memory finality vote retry queue size (NaN before first sample)")
                .register(registry);
        Gauge.builder("nexus_finality_outbox_pending", this,
                        m -> m.sampled ? (double) m.receivedPending : Double.NaN)
                .tag("origin", "received")
                .description("In-memory finality vote retry queue size (NaN before first sample)")
                .register(registry);

        Gauge.builder("nexus_finality_outbox_database_pending", this,
                        m -> m.sampled && m.databasePending >= 0L
                                ? (double) m.databasePending : Double.NaN)
                .description("Exact rows pending in the persistent finality vote outbox "
                        + "(NaN = unreadable/not sampled)")
                .register(registry);
        Gauge.builder("nexus_finality_outbox_database_readable", this, m -> {
                    if (!m.sampled) {
                        return Double.NaN;
                    }
                    // 未装配不算故障（没有库可读）；装配了但读不到才是 0。
                    return !m.enabled || m.databaseReadable ? 1.0 : 0.0;
                })
                .description("1 = outbox readable or not wired; 0 = wired but database unreadable; "
                        + "NaN before first sample")
                .register(registry);

        FunctionCounter.builder("nexus_finality_outbox_enqueued", this,
                        FinalityOutboxMetrics::enqueued)
                .description("Total votes enqueued into the finality outbox (monotonic)")
                .register(registry);
        FunctionCounter.builder("nexus_finality_outbox_flushed", this,
                        FinalityOutboxMetrics::flushed)
                .description("Total votes successfully flushed from the finality outbox (monotonic)")
                .register(registry);
        FunctionCounter.builder("nexus_finality_outbox_failed", this,
                        FinalityOutboxMetrics::failed)
                .description("Total retry failures of the finality outbox, excluding dropped votes "
                        + "(monotonic)")
                .register(registry);
        FunctionCounter.builder("nexus_finality_outbox_dropped", this,
                        FinalityOutboxMetrics::dropped)
                .description("Total votes dropped because the in-memory queue was full "
                        + "(permanent loss; monotonic)")
                .register(registry);
    }

    /**
     * 采样 outbox 快照（默认 30s 一次；Gauge 只读缓存，抓取不查库）。
     *
     * <p>{@link FinalityOutboxManager#status()} 自身已把数据库异常收敛成
     * {@code databaseReadable=false}；这里的 catch 只防「方法本身抛未预期异常」
     * 把调度线程打挂——保留上一次成功快照，等待下一轮。</p>
     */
    @Scheduled(
            initialDelayString = "${nexus.metrics.finality-outbox.initial-delay-ms:10000}",
            fixedDelayString = "${nexus.metrics.finality-outbox.refresh-ms:30000}")
    public void refreshOutboxSnapshot() {
        FinalityOutboxManager manager = outboxManager;
        if (manager == null || finalityCoordinator == null) {
            // 没有最终性共识 ⇒ 没有库可读，不算故障（readable=1），也不告警。
            enabled = false;
            localPending = 0L;
            receivedPending = 0L;
            databasePending = -1L;
            databaseReadable = true;
            sampled = true;
            return;
        }
        try {
            FinalityOutboxStatus status = manager.status();
            enabled = true;
            localPending = status.getLocalPending();
            receivedPending = status.getReceivedPending();
            databaseReadable = status.isDatabaseReadable();
            databasePending = status.isDatabaseReadable() ? status.getDatabasePending() : -1L;
            sampled = true;
        } catch (RuntimeException e) {
            logger.warn("FinalityOutboxMetrics: outbox snapshot failed, keeping last sample: {}",
                    e.getMessage());
        }
    }

    private double enqueued() {
        return outboxManager == null ? 0.0 : outboxManager.totalEnqueued();
    }

    private double flushed() {
        return outboxManager == null ? 0.0 : outboxManager.totalFlushed();
    }

    private double failed() {
        return outboxManager == null ? 0.0 : outboxManager.totalFailed();
    }

    private double dropped() {
        return outboxManager == null ? 0.0 : outboxManager.totalDropped();
    }
}
