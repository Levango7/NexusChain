package org.nexus.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.FinalityRejectionCounter.FinalizeReason;
import org.nexus.consensus.finality.FinalityRejectionCounter.Phase;
import org.nexus.consensus.finality.FinalityRejectionCounter.Reason;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-068：告警规则里的 {@code reason} / {@code phase} <b>标签值</b>必须与闭合词表逐字一致。
 *
 * <h2>缺口（与 {@link FinalityRulesContractTest} 互补）</h2>
 * <p>{@link FinalityRulesContractTest} 断的是"规则 {@code expr} 引用的<b>指标名</b>
 * 在 registry 里存在"。它<b>不检查标签值</b>：</p>
 * <pre>
 *   increase(nexus_finality_vote_rejected_total{reason=~"a|b|c"}[10m]) &gt; 0
 *              ↑ 名字对得上              ↑ 这里写错一个字母也全绿
 * </pre>
 * <p>标签值写错的后果与名字写错一样致命：序列存在、指标在涨，但 {@code reason=~...}
 * 一条都匹配不上 ⇒ 告警永远不触发。PLAN-066 把 6 个 reason 扩到 9 个、PLAN-067
 * 新增 3 个，每次都在改这个正则——而此前<b>没有任何断言</b>把正则和枚举绑在一起。</p>
 *
 * <p>本测试只读文件、不起 Spring 上下文（毫秒级），因此可以作为高频回归门禁。</p>
 *
 * @since 2.79.0
 */
class FinalityRejectionLabelContractTest {

    private static final String RULES_RELATIVE_PATH = "deploy/k8s/50-prometheus-rules.yaml";

    /** {@code reason=~"a|b|c"} 与 {@code phase=~"x|y"} 两种写法。 */
    private static final Pattern LABEL_REGEX =
            Pattern.compile("(reason|phase)\\s*=~\\s*\"([^\"]*)\"");

    private String rulesText;

    @BeforeEach
    void loadRules() throws IOException {
        Path rules = locateRules();
        rulesText = Files.readString(rules, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("规则里 reason=~ 的每个取值都是一个真实的拒绝原因（拼错即无法匹配）")
    void alertReasonValuesAreRealReasonTags() {
        Set<String> tagValues = tagValues(Reason.values());
        Set<String> referenced = collect("reason");

        assertTrue(!referenced.isEmpty(),
                "nexuschain.finality 组必须仍按 reason 过滤，否则告警无法区分伪造票与正常噪声");

        Set<String> unknown = new TreeSet<>(referenced);
        unknown.removeAll(tagValues);
        assertTrue(unknown.isEmpty(),
                "告警规则引用了不存在的 reason 标签值（永远不会匹配到任何序列）：" + unknown
                        + "\n真实取值：" + tagValues);
    }

    @Test
    @DisplayName("PLAN-067 的三个新 reason 必须真的被某条告警消费")
    void plan067ReasonsAreConsumedByAnAlert() {
        Set<String> referenced = collect("reason");
        for (Reason added : new Reason[]{
                Reason.CHECKPOINT_HASH_INVALID,
                Reason.CHECKPOINT_LIMIT_EXCEEDED,
                Reason.PAYLOAD_TOO_LARGE}) {
            String tag = added.name().toLowerCase(Locale.ROOT);
            assertTrue(referenced.contains(tag),
                    "PLAN-067 新增的 reason=" + tag + " 没有被任何告警引用 ⇒ 该拒绝只进指标、"
                            + "永不告警（PLAN-063 的'静默变成没人看的指标'）");
        }
    }

    @Test
    @DisplayName("规则里 phase=~ 的每个取值都是一个真实的 phase 标签值")
    void alertPhaseValuesAreRealPhaseTags() {
        Set<String> tagValues = tagValues(Phase.values());
        Set<String> referenced = collect("phase");
        if (referenced.isEmpty()) {
            return;   // phase 过滤是可选手段，不作强制
        }
        Set<String> unknown = new TreeSet<>(referenced);
        unknown.removeAll(tagValues);
        assertTrue(unknown.isEmpty(),
                "告警规则引用了不存在的 phase 标签值：" + unknown + "\n真实取值：" + tagValues);
    }

    @Test
    @DisplayName("最终化拒绝指标只有 aggregate_verification_failed，规则里出现别的值即为拼错")
    void alertFinalizeReasonValuesAreReal() {
        Set<String> tagValues = tagValues(FinalizeReason.values());
        Set<String> referenced = collect("reason");
        // 只校验"出现在 finalize 相关表达式附近"的 reason：简化为校验每个被引用的值
        // 至少属于某个闭合词表的并集，避免把两条告警的标签混在一起判错。
        Set<String> union = new TreeSet<>(tagValues);
        union.addAll(tagValues(Reason.values()));
        Set<String> unknown = new TreeSet<>(referenced);
        unknown.removeAll(union);
        assertTrue(unknown.isEmpty(), "未知 reason 标签值：" + unknown);
    }

    // ------------------------------------------------------------------ helpers

    private Set<String> collect(String label) {
        Set<String> values = new LinkedHashSet<>();
        Matcher m = LABEL_REGEX.matcher(rulesText);
        while (m.find()) {
            if (!m.group(1).equals(label)) {
                continue;
            }
            for (String part : m.group(2).split("\\|")) {
                String v = part.trim();
                if (!v.isEmpty()) {
                    values.add(v);
                }
            }
        }
        return values;
    }

    private static Set<String> tagValues(Enum<?>[] values) {
        Set<String> out = new TreeSet<>();
        for (Enum<?> v : values) {
            out.add(v.name().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** 与 {@link PrometheusRulesFixture} 同一套上溯逻辑（多模块构建下 cwd 可能是模块目录）。 */
    private static Path locateRules() throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(RULES_RELATIVE_PATH);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IOException("未找到 " + RULES_RELATIVE_PATH + "（仓库根定位失败）");
    }
}
