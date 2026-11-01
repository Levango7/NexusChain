package org.nexus.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.FinalityRejectionCounter.FinalizeReason;
import org.nexus.consensus.finality.FinalityRejectionCounter.Phase;
import org.nexus.consensus.finality.FinalityRejectionCounter.Reason;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-066：{@link FinalityRejectionMetrics} 的取值与标签语义（"值"这一层）。
 *
 * <p>四层门禁分工（沿用 PLAN-063 §六）：{@code WiringAuditTest} 管注册路径、
 * {@code FinalityMetricsRegistrationTest} 管"类装没装、名字在不在"、
 * {@code FinalityRulesContractTest} 管"规则引用的名字与注册出来的名字是否同一个"、
 * 本类管"计数值与标签值对不对"。</p>
 */
class FinalityRejectionMetricsTest {

    @Test
    @DisplayName("reason × phase 各是一个独立序列：同 reason 不同 phase 不相加")
    void reasonAndPhaseAreSeparateSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FinalityRejectionMetrics metrics = new FinalityRejectionMetrics(registry);

        metrics.countVoteRejected(Reason.SIGNATURE_INVALID, Phase.ADMISSION);
        metrics.countVoteRejected(Reason.SIGNATURE_INVALID, Phase.ADMISSION);
        metrics.countVoteRejected(Reason.SIGNATURE_INVALID, Phase.CONVERGENCE);

        assertEquals(2.0, counter(registry, "nexus_finality_vote_rejected",
                        "reason", "signature_invalid", "phase", "admission").count(),
                "admission 路径必须是它自己的序列");
        assertEquals(1.0, counter(registry, "nexus_finality_vote_rejected",
                        "reason", "signature_invalid", "phase", "convergence").count(),
                "收敛路径（库里有坏行）不得与 admission（正在被攻击）相加");
    }

    @Test
    @DisplayName("计数器只增不减、重复计数累加（counter 语义）")
    void counterIsMonotonic() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FinalityRejectionMetrics metrics = new FinalityRejectionMetrics(registry);

        for (int i = 0; i < 5; i++) {
            metrics.countVoteRejected(Reason.PUBKEY_MISMATCH, Phase.ADMISSION);
        }
        Counter c = counter(registry, "nexus_finality_vote_rejected",
                "reason", "pubkey_mismatch", "phase", "admission");
        assertEquals(5.0, c.count());
        metrics.countVoteRejected(Reason.PUBKEY_MISMATCH, Phase.ADMISSION);
        assertEquals(6.0, c.count(), "同一个序列必须累加而不是重建");
    }

    @Test
    @DisplayName("检查点级拒绝是独立指标，不与票级拒绝混计")
    void finalizeRejectionIsItsOwnMeter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FinalityRejectionMetrics metrics = new FinalityRejectionMetrics(registry);

        metrics.countFinalizeRejected(FinalizeReason.AGGREGATE_VERIFICATION_FAILED);
        metrics.countVoteRejected(Reason.SIGNATURE_INVALID, Phase.ADMISSION);

        assertEquals(1.0, counter(registry, "nexus_finality_finalize_rejected",
                        "reason", "aggregate_verification_failed").count(),
                "聚合验签失败必须记在检查点级指标上");
        assertEquals(1.0, counter(registry, "nexus_finality_vote_rejected",
                        "reason", "signature_invalid", "phase", "admission").count(),
                "票级拒绝仍记在自己的指标上，两者互不影响");
    }

    @Test
    @DisplayName("null 入参与缺失 registry 都不抛异常（观测 fail-safe，不是共识依赖）")
    void nullAndMissingRegistryAreSafe() {
        FinalityRejectionMetrics withoutRegistry = new FinalityRejectionMetrics(null);
        withoutRegistry.countVoteRejected(Reason.SIGNATURE_INVALID, Phase.ADMISSION);
        withoutRegistry.countVoteRejected(null, Phase.ADMISSION);
        withoutRegistry.countVoteRejected(Reason.SIGNATURE_INVALID, null);
        withoutRegistry.countFinalizeRejected(null);
        withoutRegistry.countFinalizeRejected(FinalizeReason.AGGREGATE_VERIFICATION_FAILED);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FinalityRejectionMetrics metrics = new FinalityRejectionMetrics(registry);
        int before = registry.getMeters().size();
        metrics.countVoteRejected(null, Phase.ADMISSION);
        metrics.countFinalizeRejected(null);
        assertEquals(before, registry.getMeters().size(),
                "null 入参不得注册出带 null 标签的序列");
    }

    @Test
    @DisplayName("闭合词表的每种组合各占一个序列，标签值是小写下划线字面量")
    void tagValuesMatchEnumNames() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FinalityRejectionMetrics metrics = new FinalityRejectionMetrics(registry);
        for (Reason r : Reason.values()) {
            for (Phase p : Phase.values()) {
                metrics.countVoteRejected(r, p);
            }
        }
        for (FinalizeReason r : FinalizeReason.values()) {
            metrics.countFinalizeRejected(r);
        }

        // 期望的"name{tags}"字面量（Prometheus 查询与规则 expr 都按这个写）。
        // 标签按 key 字典序输出（与 Micrometer Id#getTags 的顺序一致），避免
        // "字符串因标签顺序不一致而虚红"这种与自己无关的失败。
        Set<String> expected = new java.util.HashSet<>();
        for (Reason r : Reason.values()) {
            for (Phase p : Phase.values()) {
                expected.add("nexus_finality_vote_rejected{phase="
                        + p.name().toLowerCase(Locale.ROOT)
                        + ", reason=" + r.name().toLowerCase(Locale.ROOT) + "}");
            }
        }
        for (FinalizeReason r : FinalizeReason.values()) {
            expected.add("nexus_finality_finalize_rejected{reason="
                    + r.name().toLowerCase(Locale.ROOT) + "}");
        }

        Map<String, String> actual = new HashMap<>();
        for (Meter meter : registry.getMeters()) {
            StringBuilder sb = new StringBuilder(meter.getId().getName()).append('{');
            boolean first = true;
            for (io.micrometer.core.instrument.Tag tag
                    : meter.getId().getTags().stream().sorted().toList()) {
                if (!tag.getKey().equals("reason") && !tag.getKey().equals("phase")) {
                    continue;
                }
                if (!first) {
                    sb.append(", ");
                }
                sb.append(tag.getKey()).append('=').append(tag.getValue());
                first = false;
            }
            sb.append('}');
            actual.put(sb.toString(), sb.toString());
            for (io.micrometer.core.instrument.Tag tag : meter.getId().getTags()) {
                assertTrue(tag.getValue().equals(tag.getValue().toLowerCase(Locale.ROOT)),
                        "标签值必须是小写下划线形式（Prometheus 按字面值过滤）: " + tag);
            }
        }

        assertEquals(expected, actual.keySet(),
                "Prometheus 侧的序列字面量必须与闭合词表逐字对应");
        assertEquals(Reason.values().length * Phase.values().length + FinalizeReason.values().length,
                expected.size(), "序列数 = 闭合词表组合数");
    }

    private static Counter counter(SimpleMeterRegistry registry, String name, String... tags) {
        Counter found = registry.find(name).tags(tags).counter();
        assertNotNull(found, "序列不存在: " + name + " " + java.util.Arrays.toString(tags));
        return found;
    }
}
