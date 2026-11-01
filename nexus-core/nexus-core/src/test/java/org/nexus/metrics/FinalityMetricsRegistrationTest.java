package org.nexus.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.FinalityRejectionCounter;
import org.nexus.core.Block;
import org.nexus.core.ChainHeadProvider;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-058：最终性指标**必须真的被 Spring 注册**（断的是"注册"这一环，不是取值）。
 *
 * <p>背景：{@link FinalityMetrics}（PLAN-045/048）与 {@link AbstainRiskMetrics}
 * （PLAN-052）都曾缺 {@code @Component} ⇒ 从未进容器 ⇒
 * {@code /actuator/prometheus} 从不暴露 {@code nexus_finality_*} ⇒
 * 告警规则第 15 条 {@code FinalityStalled}、第 17 条
 * {@code FinalityBelowQuorum}、第 18 条 {@code BlockAdoptionAbstainRisk}
 * 消费的指标不存在，三条告警永不会触发。</p>
 *
 * <p>这与 PLAN-055 同型：单测只 {@code new} 不起容器，"代码绿"不等于"已接线"。</p>
 *
 * <p><b>为什么这样写</b>：第一版用「@Bean 手动 new + bindTo + 断言指标名」，
 * 反向对照（去掉两个 @Component）**不会红**——因为它验的是"我手动 new 出来的对象
 * 能绑指标",而缺陷在"生产里根本没有这个 bean"。所以这里改成断两件真正的事：</p>
 * <ol>
 *   <li>两个类**带着 {@code @Component}**（生产注册路径的前提；缺了它上面两段全空转）；</li>
 *   <li>绑定后的指标名覆盖三条告警各自消费的那一个（规则里
 *       {@code expr: nexus_finality_* == 1} 才可能为真）。</li>
 * </ol>
 */
class FinalityMetricsRegistrationTest {

    @Test
    void finalityBindersAreAnnotatedAndExposeAlertConsumedMeters() {
        // ① 注册前提：@Component 必须在类上。
        assertTrue(FinalityMetrics.class.isAnnotationPresent(Component.class),
                "FinalityMetrics 必须带 @Component（PLAN-058：缺它 ⇒ nexus_finality_latest_voted_epoch "
                        + "/progress_percent 从未注册 ⇒ FinalityStalled / FinalityBelowQuorum 永不会触发）");
        assertTrue(AbstainRiskMetrics.class.isAnnotationPresent(Component.class),
                "AbstainRiskMetrics 必须带 @Component（PLAN-058：缺它 ⇒ nexus_finality_abstain_risk "
                        + "从未注册 ⇒ BlockAdoptionAbstainRisk 永不会触发）");
        assertTrue(FinalityRejectionMetrics.class.isAnnotationPresent(Component.class),
                "FinalityRejectionMetrics 必须带 @Component（PLAN-066：缺它 ⇒ "
                        + "nexus_finality_vote_rejected_total / finalize_rejected_total 从未注册 "
                        + "⇒ FinalityVoteRejected / FinalityFinalizeRejected 永不会触发）");

        // ② 绑定后指标名必须覆盖三条告警的消费目标。
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ChainHeadProvider head = new ChainHeadProvider() {
            @Override
            public Block currentHeader() {
                return null;
            }
        };
        new FinalityMetrics(head, null).bindTo(registry);
        new AbstainRiskMetrics(null, 120_000L).bindTo(registry);
        // PLAN-066：Counter 序列按需注册，必须先计一次才有名字可断。
        FinalityRejectionMetrics rejections = new FinalityRejectionMetrics(registry);
        rejections.countFinalizeRejected(FinalityRejectionCounter.FinalizeReason.AGGREGATE_VERIFICATION_FAILED);
        rejections.countVoteRejected(FinalityRejectionCounter.Reason.SIGNATURE_INVALID,
                FinalityRejectionCounter.Phase.ADMISSION);

        Set<String> names = registry.getMeters().stream()
                .map(Meter::getId)
                .map(id -> id.getName())
                .collect(Collectors.toSet());

        assertTrue(names.contains("nexus_finality_vote_rejected"),
                "FinalityVoteRejected 消费的指标必须在计数后注册，实际 = " + names);
        assertTrue(names.contains("nexus_finality_finalize_rejected"),
                "FinalityFinalizeRejected 消费的指标必须在计数后注册，实际 = " + names);

        assertTrue(names.contains("nexus_finality_latest_voted_epoch"),
                "FinalityStalled 消费的指标必须存在，实际 = " + names);
        assertTrue(names.contains("nexus_finality_progress_percent"),
                "FinalityBelowQuorum 消费的指标必须存在，实际 = " + names);
        assertTrue(names.contains("nexus_finality_abstain_risk"),
                "BlockAdoptionAbstainRisk 消费的指标必须存在，实际 = " + names);
        // PLAN-052 的另外两个指标一并钉住，避免将来回退。
        assertTrue(names.contains("nexus_finality_defer_max_height"), "实际 = " + names);
        assertTrue(names.contains("nexus_finality_defer_total"), "实际 = " + names);
    }
}
