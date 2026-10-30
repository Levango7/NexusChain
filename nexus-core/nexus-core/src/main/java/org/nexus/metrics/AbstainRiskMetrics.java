package org.nexus.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.nexus.core.PendingBlocksManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.Map;
import org.springframework.stereotype.Component;

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
 * <p>PLAN-053 切片增补 {@code nexus_finality_abstain_current}（可回落的"当前”语义）：
 * "窗口内还有达阈高度在被 defer"。配置
 * {@code nexus.finality.abstain-current-window-ms}（缺省 120000）。</p>
 *
 * <p>无 defer 时四个值为 0（不是 NaN）："没有拒绝"是明确事实，
 * 与"没装上"（{@link PendingBlocksManager} 未装配）不同——后者由
 * 计数器恒 0 表达，与"健康无 defer"数值同形但语义无害（都不会误告警）。</p>
 *
 * @since 2.77.0（PLAN-053 切片 2.78.0）
 *
 * <p><b>PLAN-058</b>：本类与 {@link FinalityMetrics} 曙乊缺
 * {@code @Component}，从未被 Spring 注册 ⇒ 四个 gauge/counter 从未出现在
 * {@code /actuator/prometheus} ⇒ 告警规则第 15/17/18 条
 * （FinalityStalled / FinalityBelowQuorum / BlockAdoptionAbstainRisk）消费的指标不存在，
 * 三条告警究竟不会触发。与 PLAN-055 同型：单测只 new 不起容器，
 * "代码绿"不等于"已接线"。</p>
 */
@Component
public class AbstainRiskMetrics implements MeterBinder {

    private final PendingBlocksManager pendingBlocksManager;

    /**
     * "当前正在启杀"的判定窗口（毫秒）。
     *
     * <p>启杀节点的特征是"持续被拒"：窗口内还在继续 defer ⇒ 当前；
     * 窗口外沉靚 ⇒ 回落（历史仍由 {@code abstain_risk} 表达，两者不可徒相取代）。</p>
     */
    private final long currentWindowMs;

    public AbstainRiskMetrics(
            @Autowired(required = false) PendingBlocksManager pendingBlocksManager,
            @Value("${nexus.finality.abstain-current-window-ms:120000}") long currentWindowMs) {
        this.pendingBlocksManager = pendingBlocksManager;
        this.currentWindowMs = Math.max(0L, currentWindowMs);
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
        Gauge.builder("nexus_finality_abstain_current", this, m -> m.abstainCurrent())
                .description("1 = some height at/over the threshold is STILL being deferred within the "
                        + "current window; 0 = no active abstention (lifetime history stays on abstain_risk)")
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

    /**
     * "当前正在启杀"：存在达阈值高度，且其最后一次 defer 落在窗口内。
     *
     * <p>与 {@link #abstainRisk()} 的区别：后者是生命周期语义（只增不减）；
     * 本方法可回落——高度停止被拒（验证人集同步成功/链前进）后，
     * 窗口内没有新 defer 就自动回 0。</p>
     */
    private double abstainCurrent() {
        if (pendingBlocksManager == null || currentWindowMs <= 0L) {
            return 0.0;
        }
        int threshold = PendingBlocksManager.abstainDeferThreshold();
        java.util.Map<Long, Integer> counts = snapshot();
        if (counts.isEmpty()) {
            return 0.0;
        }
        java.util.Map<Long, Long> lastAt = pendingBlocksManager.unknownProposerDeferLastNanosSnapshot();
        long windowNanos = currentWindowMs * 1_000_000L;
        long now = System.nanoTime();
        for (java.util.Map.Entry<Long, Integer> e : counts.entrySet()) {
            if (e.getValue() < threshold) {
                continue;
            }
            Long at = lastAt.get(e.getKey());
            if (at == null) {
                continue;
            }
            if (now - at <= windowNanos) {
                return 1.0;
            }
        }
        return 0.0;
    }
}
