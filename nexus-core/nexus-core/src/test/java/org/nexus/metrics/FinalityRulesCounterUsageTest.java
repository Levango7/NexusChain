package org.nexus.metrics;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最终性告警**引用形状**契约门禁（PLAN-074）。
 *
 * <h2>缺口</h2>
 * <p>{@link FinalityRulesContractTest} 只断「规则里引用的指标名 vs 代码注册的名字」，
 * 不关心名字在表达式里<b>怎么被用</b>。而 counter 的引用形状有硬约束：counter 是
 * 单调累计值，裸比较（{@code dropped_total > 0}）在进程重启（counter 归零）之后
 * <b>永远不触发</b>；必须包一层 {@code increase()} / {@code rate()} 才表示
 * 「过去 N 分钟发生了多少次」。</p>
 *
 * <p>这不是假想风险：PLAN-073 新增的 {@code FinalityOutboxDropping} 写的是
 * {@code increase(nexus_finality_outbox_dropped_total[10m]) > 0}，但那是因为当时
 * 记得加——<b>没有任何门禁保证以后的人记得</b>。本测试补上这一环，把 PLAN-074
 * 本地真实链路演练（真实 Prometheus + 真实规则文件）中确认的风险固化成 CI。</p>
 *
 * <h2>为什么走 {@link PrometheusRulesFixture}</h2>
 * <p>第一版自己在测试里按行扫规则文件，产生三个假阳性：把 {@code description}
 * 里给人看的指标名当引用、把组级 {@code #} 注释行当引用、把组级说明块算进下一条
 * alert。所以改成复用 fixture 的 {@link PrometheusRulesFixture#groupExprLines(String)}
 * ——它与 PLAN-062/063 用的是同一套「只取 expr 字段」的提取规则，只是额外保留了
 * alert 名，便于报错时精确到「哪条告警的哪一行」。</p>
 *
 * <h2>刻意不做</h2>
 * <ul>
 *   <li>不校验阈值、不校验 {@code for:} 窗口（产品决策，演练用）；</li>
 *   <li>不认 {@code resets()}：它对 counter 重启的语义与告警目标不同。将来若有正当
 *       用途必须显式加白名单并写明理由，不允许静默放行。</li>
 * </ul>
 *
 * @since 2.79.0
 */
class FinalityRulesCounterUsageTest {

    /** 与 {@link FinalityRulesContractTest} 相同的两个告警组（引用形状也在这里断言）。 */
    private static final List<String> GROUPS = List.of(
            "nexuschain.finality",
            "nexuschain.block-adoption");

    /** Prometheus 侧 counter 名（Micrometer FunctionCounter 的暴露形状）。 */
    private static final java.util.regex.Pattern COUNTER_NAME =
            java.util.regex.Pattern.compile("nexus_[a-zA-Z0-9_]*_total");

    /** 允许包裹 counter 的函数：increase/rate 表示「区间内增量」，裸比较/裸赋值都不行。 */
    private static final Set<String> ALLOWED_WRAPPERS = Set.of("increase", "rate");

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("counter 必须以 increase()/rate() 引用，不能裸比较")
    void everyCounterReferenceIsWrappedInIncreaseOrRate() throws IOException {
        List<String> violations = new java.util.ArrayList<>();
        for (String group : GROUPS) {
            for (String row : PrometheusRulesFixture.groupExprLines(group)) {
                int tab = row.indexOf('\t');
                String alert = row.substring(0, tab);
                String expr = row.substring(tab + 1);
                for (var m = COUNTER_NAME.matcher(expr); m.find(); ) {
                    int at = m.start();
                    // 向前找最近的 '('，判断它的前缀是不是允许的包裹函数。
                    int open = expr.lastIndexOf('(', at);
                    String wrapper = "";
                    if (open >= 0) {
                        var fn = java.util.regex.Pattern.compile("([a-zA-Z_]+)\\s*\\($")
                                .matcher(expr.substring(0, open + 1));
                        if (fn.find()) {
                            wrapper = fn.group(1);
                        }
                    }
                    if (!ALLOWED_WRAPPERS.contains(wrapper)) {
                        violations.add(String.format(
                                "%s / %s：counter %s 未包 increase()/rate()（expr 原文：%s）",
                                group, alert, m.group(), expr));
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "counter 指标必须包 increase()/rate() 引用——裸比较在进程重启（counter 归零）后"
                        + "永远不触发。违规：\n" + String.join("\n", violations));
    }
}
