package org.nexus.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.core.Block;
import org.nexus.core.ChainHeadProvider;
import org.nexus.consensus.finality.FinalityRejectionCounter.FinalizeReason;
import org.nexus.consensus.finality.FinalityRejectionCounter.Phase;
import org.nexus.consensus.finality.FinalityRejectionCounter.Reason;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最终性指标 ↔ 告警规则名契约门禁（PLAN-063，PLAN-062 的 finality 侧同款）。
 *
 * <p><b>缺口</b>：{@link FinalityMetricsRegistrationTest} 断的是"两个 binder 带
 * {@code @Component}" + "5 个 {@code nexus_finality_*} 名字在 registry 里存在"，
 * 它<b>不读规则文件</b>。于是规则侧把名字写错/改旧，或代码侧改名，告警就永远不会
 * 触发而该测试全绿。与 {@code BlockPersistenceRulesContractTest} 管的是同一条缝、
 * 另一头（block-persistence vs finality）。</p>
 *
 * <p>本测试覆盖两个引用 {@code nexus_finality_*} 的告警组：
 * {@code nexuschain.finality}（FinalityStalled / FinalityBelowQuorum）与
 * {@code nexuschain.block-adoption}（BlockAdoptionAbstainRisk）。</p>
 *
 * <p>与 {@link FinalityMetricsRegistrationTest} 的分工：那个测"有没有装、名字在不在"，
 * 本测试测"规则引用的名字与注册出来的名字是否同一个"。</p>
 */
class FinalityRulesContractTest {

    /** 引用 nexus_finality_* 的告警组（按需增减）。 */
    private static final List<String> GROUPS = List.of(
            "nexuschain.finality",
            "nexuschain.block-adoption");

    private static final int EXPECTED_METER_COUNT = 14;

    /**
     * 已注册但<b>没有</b>告警消费的指标白名单（诊断用）。
     *
     * <p>本测试的职责是<b>强制分类</b>：新增指标必须被有意识地放进这里或接进告警，
     * 不允许静默变成没人看的指标。它<b>不裁决</b>"这些指标是否该有告警"——
     * 那需要各自的阈值语义（例如 {@code nexus_finality_lag} 的健康值不是 0），
     * 属于产品决策而非门禁能代答的。</p>
     */
    private static final Set<String> ALERTLESS_BY_DESIGN = Set.of(
            // "是否装配"开关：0 = 非 pos 模式（系统设计如此），不可告警。
            "nexus_finality_enabled",
            // 当前链头 epoch / 滞后 epoch 数 / 是否已达终局阈值：
            // FinalityBelowQuorum 已用 progress_percent < 100 表达"票没收齐"，
            // 这三个是同一现象的密度更高的诊断视图，单独告警会重复。
            "nexus_finality_current_epoch",
            "nexus_finality_lag",
            "nexus_finality_finalized",
            // 累计型终局检查点：单调递增，用于对账/复盘，无阈值语义。
            "nexus_finality_finalized_checkpoints",
            // PLAN-052 口径：defer 计数"只增不减"，其含义是"本进程启动以来见过
            // 弃权风险"，不等于"当前正在弃权"——所以它喂的是 abstain_risk 的
            // 稳态口径，这三个是 abstain_risk 的分解视图（供排障定位是哪个高度）。
            "nexus_finality_defer_total",
            "nexus_finality_defer_max_height",
            "nexus_finality_defer_max_count",
            // 当前窗口内的弃权数：时序性强、抖动大，PLAN-052 选了 5m 稳态口径
            // 才有 abstain_risk 这个可告警的 0/1；本指标不进告警。
            "nexus_finality_abstain_current"
    );

    /**
     * PLAN-066 新增的两个指标**不**进上面的白名单——它们必须由告警规则真消费，
     * 否则就是 PLAN-063 说的"静默变成没人看的指标"。告警只按 reason 过滤出
     * 伪造票相关的两类（见 {@code nexuschain.finality} 组的
     * {@code FinalityVoteRejected}）；其余 reason 的 series 供按标签排查，
     * 不需要也不应该各自建告警（启动期/治理期的正常现象会产生噪声）。
     */

    private Set<String> registeredNames;

    @BeforeEach
    void bindRealRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 构造器只做字段赋值（bindTo 也只用延迟读取），故可传 null  collaborators。
        new FinalityMetrics(new ChainHeadProvider() {
            @Override
            public Block currentHeader() {
                return null;
            }
        }, null).bindTo(registry);
        new AbstainRiskMetrics(null, 120_000L).bindTo(registry);
        // PLAN-066：拒绝计数是 Counter，必须"计一次才注册一个序列"，否则
        // SimpleMeterRegistry 里一个序列都没有，下面的计数断言会虚高。
        FinalityRejectionMetrics rejections = new FinalityRejectionMetrics(registry);
        rejections.countFinalizeRejected(FinalizeReason.AGGREGATE_VERIFICATION_FAILED);
        rejections.countVoteRejected(Reason.SIGNATURE_INVALID, Phase.ADMISSION);
        rejections.countVoteRejected(Reason.SIGNATURE_INVALID, Phase.CONVERGENCE);

        registeredNames = new TreeSet<>();
        for (Meter meter : registry.getMeters()) {
            registeredNames.add(meter.getId().getName());
        }

        // 防假绿：绑定结果必须正是那 14 个（FinalityMetrics 7 + AbstainRiskMetrics 5
        // + PLAN-066 的两个拒绝计数指标）
        assertEquals(EXPECTED_METER_COUNT, registeredNames.size(),
                "三个 finality binder 应注册 14 个指标，实际：" + registeredNames);
    }

    @Test
    @DisplayName("告警规则引用的每个 nexus_finality_* 名字都必须在代码里注册")
    void everyRuleReferencedNameIsRegistered() throws IOException {
        Set<String> referenced = referencedNames();

        Set<String> unknown = new LinkedHashSet<>();
        for (String name : referenced) {
            if (!registeredNames.contains(name)) {
                unknown.add(name);
            }
        }

        assertTrue(unknown.isEmpty(),
                "Prometheus 规则引用了代码里未注册的指标名（该告警永远不触发）: " + unknown
                        + "\n  规则引用: " + referenced
                        + "\n  已注册:   " + registeredNames);
    }

    @Test
    @DisplayName("每个已注册指标要么被告警消费，要么在白名单里登记用途")
    void everyRegisteredNameIsAlertedOrDocumented() throws IOException {
        Set<String> uncovered = new TreeSet<>(registeredNames);
        uncovered.removeAll(referencedNames());
        uncovered.removeAll(ALERTLESS_BY_DESIGN);

        assertTrue(uncovered.isEmpty(),
                "存在既无告警消费、也未在 ALERTLESS_BY_DESIGN 登记用途的指标"
                        + "（新指标必须有意识地分类）：" + uncovered
                        + "\n  已注册:   " + registeredNames
                        + "\n  告警引用: " + referencedNames());
    }

    /** 两个告警组 expr 引用的 nexus_finality_* 名字（归一化到 registry 侧）并集。 */
    private static Set<String> referencedNames() throws IOException {
        Set<String> union = new TreeSet<>();
        for (String group : GROUPS) {
            union.addAll(PrometheusRulesFixture.normalizedMeterNames(group));
        }
        return union;
    }
}
