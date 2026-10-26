package org.nexus.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.nexus.consensus.finality.FinalityCoordinator;
import org.nexus.core.ChainHeadProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/**
 * 最终性视图指标（PLAN-045）：把 {@code GET /consensus/finality}（PLAN-044）的
 * 只读快照接进 Micrometer，供 {@code deploy/k8s/50-prometheus-rules.yaml} 的
 * {@code nexuschain.finality} 告警组消费。
 *
 * <h2>抓取安全</h2>
 * <p>数据源是 {@link ChainHeadProvider#currentHeader()}（**内存**链头，不是
 * 数据库查询）+ {@link FinalityCoordinator#finalitySnapshot(long)}（gadget
 * 内存视图）——整条链路不碰数据库，PG 抖动不会拖垮抓取（与
 * {@link BlockPersistenceMetrics} 同一纪律）。多次 Gauge 回调共享一个最多 1 秒
 * 新的快照缓存，避免单次抓取内重复计算。</p>
 *
 * <h2>指标</h2>
 * <table>
 *   <tr><th>指标</th><th>语义</th></tr>
 *   <tr><td>{@code nexus_finality_enabled}</td><td>最终性共识是否装配（0/1；非 pos 模式为 0）</td></tr>
 *   <tr><td>{@code nexus_finality_current_epoch}</td><td>当前高度的 epoch（未装配为 NaN）</td></tr>
 *   <tr><td>{@code nexus_finality_latest_voted_epoch}</td><td>最近一个有本地视图记录的 epoch（无记录为 NaN）</td></tr>
 *   <tr><td>{@code nexus_finality_lag}</td><td><b>最终性落后出块几个 epoch</b>（无记录为 NaN）</td></tr>
 *   <tr><td>{@code nexus_finality_finalized}</td><td>最新已投票 epoch 是否达门限（1/0；无记录为 NaN）</td></tr>
 *   <tr><td>{@code nexus_finality_progress_percent}</td><td>最新已投票 epoch 的聚合进度（无记录为 NaN）</td></tr>
 *   <tr><td>{@code nexus_finality_finalized_checkpoints}</td><td>内存已最终化检查点总数</td></tr>
 * </table>
 *
 * <h2>口径</h2>
 * <p>{@code progress_percent} 是进程内聚合视图的**提交时快照**，随对端票到达
 * 浮动（2 验证人拓扑刚投完自己通常 50%，收到对端票后 100%；PLAN-043/044 实测）。
 * 因此告警用 <b>lag</b>（落后幾個 epoch，持续值）与 <b>长窗口 percent</b>
 * （10 分钟仍不到门限），不用单次采样的 50%。</p>
 *
 * @since 2.77.0
 */
public class FinalityMetrics implements MeterBinder {

    /** 快照缓存最长新鲜度（毫秒）：单次抓取内多个 Gauge 共享一次计算。 */
    private static final long SNAPSHOT_TTL_MS = 1_000L;

    private final ChainHeadProvider chainHeadProvider;
    private final FinalityCoordinator finalityCoordinator;

    private volatile Map<String, Object> cachedSnapshot;
    private volatile long cachedAtMillis;

    public FinalityMetrics(ChainHeadProvider chainHeadProvider,
                           @Autowired(required = false) FinalityCoordinator finalityCoordinator) {
        this.chainHeadProvider = chainHeadProvider;
        this.finalityCoordinator = finalityCoordinator;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("nexus_finality_enabled", this, m -> m.finalityCoordinator != null ? 1.0 : 0.0)
                .description("Whether finality consensus is wired (1/0; 0 = non-pos mode)")
                .register(registry);
        Gauge.builder("nexus_finality_current_epoch", this, m -> m.number("currentEpoch"))
                .description("Epoch of the current in-memory chain head (NaN = not wired)")
                .register(registry);
        Gauge.builder("nexus_finality_latest_voted_epoch", this, m -> m.number("latestVotedEpoch"))
                .description("Newest epoch with a local finality record (NaN = none / not wired)")
                .register(registry);
        Gauge.builder("nexus_finality_lag", this, m -> m.number("finalityLag"))
                .description("Finality lag in epochs (currentEpoch - latestVotedEpoch; NaN = none / not wired)")
                .register(registry);
        Gauge.builder("nexus_finality_finalized", this, m -> m.progressFlag())
                .description("Whether the latest voted epoch reached the finality threshold (1/0; NaN = no record / not wired)")
                .register(registry);
        Gauge.builder("nexus_finality_progress_percent", this, m -> m.progressNumber("progressPercent"))
                .description("Aggregation progress of the latest voted epoch (NaN = no record / not wired)")
                .register(registry);
        Gauge.builder("nexus_finality_finalized_checkpoints", this, m -> m.number("finalizedCheckpoints"))
                .description("In-memory finalized checkpoint count (NaN = not wired)")
                .register(registry);
    }

    /** 取快照（1s TTL 缓存；未装配最终性共识时返回 null）。 */
    private Map<String, Object> snapshot() {
        if (finalityCoordinator == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        Map<String, Object> cached = cachedSnapshot;
        if (cached != null && now - cachedAtMillis <= SNAPSHOT_TTL_MS) {
            return cached;
        }
        // currentHeader 是内存链头读（ChainHeadProvider），不查库
        Map<String, Object> fresh = finalityCoordinator.finalitySnapshot(
                chainHeadProvider.currentHeader().nHeight);
        cachedSnapshot = fresh;
        cachedAtMillis = now;
        return fresh;
    }

    private double number(String key) {
        Map<String, Object> snapshot = snapshot();
        Object value = snapshot == null ? null : snapshot.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> progress() {
        Map<String, Object> snapshot = snapshot();
        Object progress = snapshot == null ? null : snapshot.get("progress");
        return progress instanceof Map ? (Map<String, Object>) progress : null;
    }

    private double progressFlag() {
        Map<String, Object> progress = progress();
        Object finalized = progress == null ? null : progress.get("finalized");
        return finalized instanceof Boolean ? ((Boolean) finalized) ? 1.0 : 0.0 : Double.NaN;
    }

    private double progressNumber(String key) {
        Map<String, Object> progress = progress();
        Object value = progress == null ? null : progress.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }
}
