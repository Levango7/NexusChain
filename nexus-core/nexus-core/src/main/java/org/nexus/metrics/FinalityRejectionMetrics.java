package org.nexus.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.nexus.consensus.finality.FinalityRejectionCounter;
import org.nexus.consensus.finality.FinalityRejectionCounter.FinalizeReason;
import org.nexus.consensus.finality.FinalityRejectionCounter.Phase;
import org.nexus.consensus.finality.FinalityRejectionCounter.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 最终性投票拒绝计数指标（PLAN-066）：把 {@link FinalityGadget} 的拒绝判定接到
 * Micrometer，供 {@code deploy/k8s/50-prometheus-rules.yaml} 的
 * {@code nexuschain.finality} 告警组消费。
 *
 * <h2>指标</h2>
 * <table>
 *   <tr><th>指标</th><th>标签</th><th>语义</th></tr>
 *   <tr><td>{@code nexus_finality_vote_rejected_total}</td>
 *       <td>{@code reason}（9 种）、{@code phase}（3 种）</td>
 *       <td>票级拒绝累计次数：一张票没有进入计票/落库的原因与路径</td></tr>
 *   <tr><td>{@code nexus_finality_finalize_rejected_total}</td>
 *       <td>{@code reason}（当前 1 种）</td>
 *       <td>检查点级拒绝累计次数：阈值已过但最终化被拒</td></tr>
 * </table>
 *
 * <h2>口径与刻意不做的事</h2>
 * <ul>
 *   <li><b>没有 validator 地址标签</b>：PLAN-065 §六 的原始设想是"按验证人/原因
 *       分桶"。若把地址做成标签，攻击者用随机地址洪泛即可让标签基数无界增长
 *       ——被观测对象就会被观测本身打垮（Prometheus 内存与查询代价）。
 *       本切片只做原因/路径分桶；验证人归因仍在 WARN 日志里（含地址与 epoch），
 *       需要按人定位时登日志，这是有意的取舍，不是遗漏。</li>
 *   <li><b>计数只增不减</b>：Counter 语义，符合"拒绝发生过"的事实语义；
 *       告警一律用 {@code increase(...)}，不用瞬时值。</li>
 *   <li><b>没有 registry 也能活</b>：{@code MeterRegistry} 缺失时全部计数降级为
 *       no-op 并在首次计数时打一次 WARN（与 {@code FinalityGadget} 未注入本端口
 *       时的"只打日志"一致），不因缺依赖而让 Spring 上下文启动失败。</li>
 * </ul>
 *
 * @since 2.78.0
 */
@Component
public class FinalityRejectionMetrics implements FinalityRejectionCounter {

    private static final Logger log = LoggerFactory.getLogger(FinalityRejectionMetrics.class);

    /** 票级拒绝累计计数（Micrometer 自动补 {@code _total} 后缀）。 */
    public static final String METER_VOTE_REJECTED = "nexus_finality_vote_rejected";

    /** 检查点级拒绝累计计数。 */
    public static final String METER_FINALIZE_REJECTED = "nexus_finality_finalize_rejected";

    /**
     * registry 缺失时只打一次 WARN：缺依赖时的每个拒绝都刷日志会把日志本身
     * 变成噪声（与 PLAN-064 修掉的死告警同型）。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean WARNED_NO_REGISTRY =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * {@code MeterRegistry} 可选注入。
     *
     * <p>参数级 {@code @Autowired(required = false)}：与 {@link FinalityMetrics} 对
     * {@code FinalityCoordinator} 的既有处理一致——指标类不应因缺注册表而阻断
     * Spring 上下文（非 pos / 测试上下文里可能没有 registry bean）。</p>
     */
    private final MeterRegistry registry;

    /** reason+phase → Counter。基数闭合（9 × 3）故可直接用并发 Map，无内存风险。 */
    private final Map<Long, Counter> voteCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> finalizeCounters = new ConcurrentHashMap<>();

    public FinalityRejectionMetrics(@Autowired(required = false) MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void countVoteRejected(Reason reason, Phase phase) {
        if (reason == null || phase == null) {
            return;
        }
        Counter counter = voteCounters.computeIfAbsent(key(reason, phase), k -> {
            if (registry == null) {
                warnNoRegistry();
                return null;
            }
            return Counter.builder(METER_VOTE_REJECTED)
                    .tag("reason", reason.name().toLowerCase(Locale.ROOT))
                    .tag("phase", phase.name().toLowerCase(Locale.ROOT))
                    .description("Finality votes rejected before counting/persistence "
                            + "(reason x path; PLAN-066)")
                    .register(registry);
        });
        if (counter == null) {
            return;
        }
        counter.increment();
    }

    @Override
    public void countFinalizeRejected(FinalizeReason reason) {
        if (reason == null) {
            return;
        }
        Counter counter = finalizeCounters.computeIfAbsent(reason.name(), k -> {
            if (registry == null) {
                warnNoRegistry();
                return null;
            }
            return Counter.builder(METER_FINALIZE_REJECTED)
                    .tag("reason", k.toLowerCase(Locale.ROOT))
                    .description("Finality checkpoints rejected at finalization "
                            + "(threshold met, verification failed; PLAN-066)")
                    .register(registry);
        });
        if (counter == null) {
            return;
        }
        counter.increment();
    }

    /**
     * 枚举序数打包成 map key（闭合词表 ⇒ 无碰撞、无字符串分配）。
     *
     * <p>不能用 {@code (byte) phase.ordinal()} 参与按位或：byte 是有符号的，
     * 符号扩展会把高 56 位置起来，两个不同组合可能撞同一个 key
     * （SpotBugs {@code BIT_IOR_OF_SIGNED_BYTE} 正是抓这个）。这里把两个
     * 非负 int 分别放进高/低 32 位，语义明确且无符号问题。</p>
     */
    private static long key(Reason reason, Phase phase) {
        return ((long) reason.ordinal() << Integer.SIZE) | phase.ordinal();
    }

    private static void warnNoRegistry() {
        if (WARNED_NO_REGISTRY.compareAndSet(false, true)) {
            log.warn("FinalityRejectionMetrics: no MeterRegistry available; "
                    + "rejection counters are no-op (PLAN-066)");
        }
    }
}
