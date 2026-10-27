package org.nexus.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.nexus.core.PendingBlocksManager;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/**
 * 区块采纳"弃权风险"指标（PLAN-052：把 PLAN-051 的拒绝路径接进 Prometheus）。
 *
 * <p>三路最终性信号的**原因维度**：</p>
 * <ul>
 *   <li>{@code FinalityStalled}（PLAN-048）：结果维度、全网、滞后 15min；</li>
 *   <li>{@code FinalityBelowQuorum}（PLAN-045）：结果维度、票不齐；</li>
 *   <li><b>本类</b>：原因维度、单节点、**最早出现症状**（第一次拒绝就能看到）——
 *       数据源是 {@link PendingBlocksManager#unknownProposerDeferSnapshot()}
 *       （PLAN-051 的按高度 defer 计数）。</li>
 * </ul>
 *
 * <h2>指标</h2>
 * <table>
 *   <tr><th>指标</th><th>语义</th></tr>
 *   <tr><td>{@code nexus_finality_defer_total}</td><td>FunctionCounter：unknown-proposer defer 累计次数（单调）</td></tr>
 *   <tr><td>{@code nexus_finality_defer_max_height}</td><td>Gauge：defer 计数最高的高度（最可能卡住的采纳点；无 defer 为 0）</td></tr>
 *   <tr><td>{@code nexus_finality_defer_max_count}</td><td>Gauge：该高度的 defer 次数（无 defer 为 0）</td></tr>
 *   <tr><td>{@code nexus_finality_abstain_risk}</td><td>Gauge 0/1：是否已有任一高度达 {@code abstainDeferThreshold()}——告警直接用它</td></tr>
 * </table>
 *
 * <h2>口径（重要，勿误读）</h2>
 * <p>计数**按高度独立累计、只增不减**（与 PLAN-051 的日志语义一致）⇒
 * {@code abstain_risk=1} 的含义是"**本进程启动以来**见过弃权风险"，
 * **不是"当前正在弃权"**。一个历史 defer 过 5 次的高度会让它一直亮。
 * 如需"当前"语义（最近 N 分钟增量/衰减），属后续切片——本切片如实登记该局限。</p>
 *
 * <p>无 defer 时三个值为 0（不是 NaN）："没有拒绝"是明确事实，
 * 与"没装上"（{@link PendingBlocksManager} 未装配）不同——后者由
 * 计数器恒 0 表达，与"健康无 defer"数值同形但语义无害（都不会误告警）。</p>
 *
 * @since 2.77.0
 */
public class AbstainRiskMetrics implements MeterBinder {

    private final PendingBlocksManager pendingBlocksManager;

    public AbstainRiskMetrics(@Autowired(required = false) PendingBlocksManager pendingBlocksManager) {
        this.pendingBlocksManager = pendingBlocksManager;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        FunctionCounter.builder("nexus_finality_defer_total", this, m -> m.total())
                .description("Total unknown-proposer defer events (monotonic, per-process)")
                .register(registry);
        Gauge.builder("nexus_finality_defer_max_height", this, m -> m.maxHeight())
                .description("Height with the highest unknown-proposer defer count (0 = none)")
                .register(registry);
        Gauge.builder("nexus_finality_defer_max_count", this, m -> m.maxCount())
                .description("Defer count at that height (0 = none)")
                .register(registry);
        Gauge.builder("nexus_finality_abstain_risk", this, m -> m.abstainRisk())
                .description("1 = some height reached the abstain defer threshold (this process lifetime); 0 = none")
                .register(registry);
    }

    private Map<Long, Integer> snapshot() {
        return pendingBlocksManager == null ? Map.of() : pendingBlocksManager.unknownProposerDeferSnapshot();
    }

    private double total() {
        long sum = 0L;
        for (int count : snapshot().values()) {
            sum += count;
        }
        return (double) sum;
    }

    /** 计数最高的高度（并列取最小高度——稳定可复现）。 */
    private double maxHeight() {
        Map<Long, Integer> snapshot = snapshot();
        long bestHeight = 0L;
        int bestCount = 0;
        for (Map.Entry<Long, Integer> e : snapshot.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                bestHeight = e.getKey();
            }
        }
        return (double) bestHeight;
    }

    private double maxCount() {
        int best = 0;
        for (int count : snapshot().values()) {
            if (count > best) {
                best = count;
            }
        }
        return (double) best;
    }

    private double abstainRisk() {
        int threshold = PendingBlocksManager.abstainDeferThreshold();
        for (int count : snapshot().values()) {
            if (count >= threshold) {
                return 1.0;
            }
        }
        return 0.0;
    }
}
