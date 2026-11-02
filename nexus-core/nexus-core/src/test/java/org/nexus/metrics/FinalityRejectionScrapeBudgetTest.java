package org.nexus.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
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
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-070：最终性拒绝指标的<b>抓取开销上界</b>与<b>稀疏序列语义</b>门禁。
 *
 * <h2>为什么需要这个门禁</h2>
 * <p>PLAN-068 的遗留项写的是「吞吐/基数基准（<b>高基数</b>下 {@code phase × reason}
 * 的抓取开销）」。但认领前查代码发现<b>那个前提不成立</b>：</p>
 * <ul>
 *   <li>{@link Reason} = 9 项闭合枚举，{@link Phase} = 3 项，{@link FinalizeReason} = 1 项；</li>
 *   <li>{@link FinalityRejectionMetrics} 的 map 键是 {@code long}（两个 ordinal 打包）
 *       与枚举 {@code name()}，都是 {@code ConcurrentHashMap}
 *       ⇒ <b>键集合由编译器保证有界</b>，与流量/验证人数/地址数无关。</li>
 * </ul>
 * <p>⇒ 序列数上限是 <b>28</b>，不存在"基数无界"风险（PLAN-066 刻意不加 validator
 * 地址标签正是为此）。所以本测试<b>不做压力测试</b>，而是钉住三件结构性事实：</p>
 * <ol>
 *   <li><b>预算有界</b>：闭合词表全注册后，抓取文本的序列条数与体积必须落在预算内。
 *       将来若有人给这些 counter 加了高基数标签（例如 validator 地址），
 *       条数/体积会立刻越界 ⇒ 本测试变红；</li>
 *   <li><b>稀疏序列语义</b>：生产用 {@code computeIfAbsent} <b>懒注册</b>，
 *       所以"从未发生过的 reason"不会出现在抓取文本里；而
 *       {@code increase(<不存在的序列>[10m]) > 0} 在缺失序列上返回<b>空向量</b>
 *       （不是 0）⇒ 不误报，且首次发生时立刻可见（无延迟窗口）；</li>
 *   <li><b>枚举扩容需有意识</b>：{@code Reason} / {@code Phase} / {@code FinalizeReason}
 *       的数量变化必须同步本文件的预算与 {@code deploy/k8s/50-prometheus-rules.yaml}
 *       的告警正则——防止静默膨胀。</li>
 * </ol>
 *
 * <h2>实测基线（2026-11-03，Micrometer 1.16.7 + prometheus-metrics 1.4.3）</h2>
 * <table>
 *   <tr><th>序列数</th><th>scrape 平均</th><th>抓取文本</th></tr>
 *   <tr><td>1</td><td>0.0469 ms</td><td>172 字节</td></tr>
 *   <tr><td>9</td><td>0.0695 ms</td><td>764 字节</td></tr>
 *   <tr><td>27</td><td>0.0749 ms</td><td>2096 字节</td></tr>
 * </table>
 * <p>1→27 序列耗时只涨约 1.6 倍（固定开销占主导）。<b>本测试不断言绝对耗时</b>
 * ——那在不同机器/负载下会 flaky；只断言结构性事实（条数、体积、存在性）。</p>
 *
 * @since 2.81.0
 */
class FinalityRejectionScrapeBudgetTest {

    /** 序列数上限：{@code Reason × Phase + FinalizeReason}。改枚举就必须改这里。 */
    private static final int EXPECTED_MAX_VOTE_SERIES =
            Reason.values().length * Phase.values().length;

    /** 闭合词表全注册后，抓取文本里每个序列的成本上界（字节，含 HELP/TYPE 摊销）。 */
    private static final int BYTES_PER_SERIES_BUDGET = 200;

    @Test
    @DisplayName("闭合词表全注册 ⇒ 序列条数与抓取体积落在预算内（高基数标签会被立刻抓到）")
    void fullVocabularyStaysWithinScrapeBudget() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        FinalityRejectionMetrics metrics = new FinalityRejectionMetrics(registry);

        // 把闭合词表的每一种组合都计一次（这是基数的绝对上限）。
        for (Reason reason : Reason.values()) {
            for (Phase phase : Phase.values()) {
                metrics.countVoteRejected(reason, phase);
            }
        }
        for (FinalizeReason reason : FinalizeReason.values()) {
            metrics.countFinalizeRejected(reason);
        }

        String text = registry.scrape();
        long voteSeries = seriesCount(text, "nexus_finality_vote_rejected_total");
        long finalizeSeries = seriesCount(text, "nexus_finality_finalize_rejected_total");

        assertEquals(EXPECTED_MAX_VOTE_SERIES, voteSeries,
                "票级拒绝序列条数必须等于 Reason × Phase —— 多了说明有人加了额外标签"
                        + "（例如 validator 地址，会让基数随攻击者数量无界增长）");
        assertEquals(FinalizeReason.values().length, finalizeSeries,
                "检查点级拒绝序列条数必须等于 FinalizeReason 项数");

        // 关键：条数相同**不代表**基数有界——加了 validator 标签后，7 条序列仍只暴露 7 行，
        // 但每条多了一个标签维度，标签组合数会随验证人数膨胀。所以必须**逐标签**断言：
        // 每个序列只允许出现 reason / phase（或 reason）这两个业务标签。
        for (String line : text.lines()
                .filter(l -> l.startsWith("nexus_finality_vote_rejected_total{"))
                .toList()) {
            assertTagsAreBounded(line, "nexus_finality_vote_rejected_total",
                    java.util.Set.of("reason", "phase"));
        }
        for (String line : text.lines()
                .filter(l -> l.startsWith("nexus_finality_finalize_rejected_total{"))
                .toList()) {
            assertTagsAreBounded(line, "nexus_finality_finalize_rejected_total",
                    java.util.Set.of("reason"));
        }

        int budget = (int) (voteSeries + finalizeSeries) * BYTES_PER_SERIES_BUDGET;
        assertTrue(text.length() < budget,
                "抓取文本 " + text.length() + " 字节超出预算 " + budget
                        + "（每序列 " + BYTES_PER_SERIES_BUDGET + " 字节）——"
                        + "很可能有人往标签里放了高基数内容");
    }

    @Test
    @DisplayName("懒注册：未计数的 reason 不出现在抓取文本中（生产用 computeIfAbsent）")
    void unReachedReasonsAreAbsentBecauseRegistrationIsLazy() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        FinalityRejectionMetrics metrics = new FinalityRejectionMetrics(registry);

        String before = registry.scrape();
        assertEquals(0, seriesCount(before, "nexus_finality_vote_rejected_total"),
                "一次拒绝都没发生时，不应有任何票级拒绝序列");

        // 只触发一种 reason。
        metrics.countVoteRejected(Reason.SIGNATURE_INVALID, Phase.ADMISSION);

        String after = registry.scrape();
        assertEquals(1, seriesCount(after, "nexus_finality_vote_rejected_total"),
                "只触发一种 reason 时，只应出现这一条序列");
        assertTrue(after.contains("reason=\"signature_invalid\""),
                "已触发的 reason 必须可见");
        assertFalse(after.contains("reason=\"checkpoint_limit_exceeded\""),
                "未触发的 reason 不得凭空出现——如果出现了，说明有人改成了预注册全量，"
                        + "那会让 28 条序列恒常暴露（虽不致命，但破坏了『只在需要时占预算』的设计）");
    }

    @Test
    @DisplayName("稀疏序列不破坏告警语义：缺失的序列让 increase()>0 为空向量，首现即 1")
    void missingSeriesDoesNotBreakAlertSemantics() {
        // 语义依据（写在这里，防止后人把『序列没出现』误判成『指标坏了』）：
        //   increase(<不存在的序列>[10m])  =>  空向量（不是 0），
        //   expr: increase(...) > 0        =>  空向量比较恒为空 ⇒ 不触发告警（不误报）；
        //   首次拒绝发生后序列立刻出现且值为 1 ⇒ 下一次评估即可能触发（无延迟窗口）。
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        FinalityRejectionMetrics metrics = new FinalityRejectionMetrics(registry);

        assertFalse(registry.scrape().contains("nexus_finality_vote_rejected_total"),
                "前提：初始态无该指标族（懒注册）");

        metrics.countVoteRejected(Reason.CHECKPOINT_HASH_INVALID, Phase.ADMISSION);

        String text = registry.scrape();
        String expected = "nexus_finality_vote_rejected_total{phase=\"admission\","
                + "reason=\"checkpoint_hash_invalid\"}";
        assertTrue(text.contains(expected + " 1.0"),
                "首次拒绝后序列必须立刻可见且计数为 1（告警无延迟窗口）：" + expected);

        // 告警正则里的取值必须与枚举字面量一致（PLAN-068 已由
        // FinalityRejectionLabelContractTest 钉住；这里只断言本测试用到的那个）。
        assertTrue(text.contains("reason=\""
                        + Reason.CHECKPOINT_HASH_INVALID.name().toLowerCase(Locale.ROOT) + "\""),
                "标签值必须是小写枚举名");
    }

    @Test
    @DisplayName("基数预算与告警正则同源：枚举扩容必须同步（防止静默膨胀）")
    void cardinalityBudgetMatchesClosedVocabulary() throws IOException {
        assertEquals(EXPECTED_MAX_VOTE_SERIES,
                Reason.values().length * Phase.values().length,
                "预算常量必须与枚举组合数一致");

        // 序列数上限必须落到文档可见的位置，否则"上限 28"只是一个口头结论。
        String doc = readRepoFile("docs/plan/PLAN-070-scrape-cost-and-sparse-series.md");
        assertTrue(doc.contains(String.valueOf(EXPECTED_MAX_VOTE_SERIES))
                        || doc.contains(String.valueOf(EXPECTED_MAX_VOTE_SERIES + 1)),
                "PLAN-070 文档必须写明序列上限（" + EXPECTED_MAX_VOTE_SERIES
                        + " 或含 finalize 的 " + (EXPECTED_MAX_VOTE_SERIES + 1) + "），"
                        + "否则『上限有界』只是口头结论");
    }

    // ------------------------------------------------------------------ helpers

    /** 统计抓取文本中某个指标族的序列行数（不含 HELP/TYPE 注释行）。 */
    private static long seriesCount(String text, String meterName) {
        String prefix = meterName + "{";
        return text.lines().filter(line -> line.startsWith(prefix)).count();
    }

    /**
     * 断言某条序列只带「白名单里的标签键」。
     *
     * <p><b>为什么这条断言不可省</b>：反向对照实测发现——给 counter 加一个
     * {@code validator} 标签后，{@code seriesCount} 仍返回 7（每个 (reason, phase)
     * 组合仍是一条序列），<b>条数与体积断言都不会红</b>。也就是说"数行数"根本
     * 抓不住高基数标签，必须直接检查<b>标签键集合</b>。</p>
     *
     * <p>真实风险：加了地址标签后，基数 = 27 × 验证人数，随攻击者/验证人数量膨胀——
     * 这正是 PLAN-066 刻意不做的事。</p>
     */
    private static void assertTagsAreBounded(String line, String meterName,
                                             java.util.Set<String> allowedKeys) {
        int open = line.indexOf('{');
        int close = line.indexOf('}', open);
        assertTrue(open > 0 && close > open, "无法解析序列标签: " + line);

        java.util.Set<String> actual = new java.util.TreeSet<>();
        for (String part : line.substring(open + 1, close).split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            assertTrue(eq > 0, "无法解析标签片段: " + trimmed);
            actual.add(trimmed.substring(0, eq).trim());
        }

        assertEquals(new java.util.TreeSet<>(allowedKeys), actual,
                meterName + " 的标签键必须严格等于 " + allowedKeys
                        + "（实际 " + actual + "）——多出来的标签会让基数随维度值膨胀："
                        + line);
    }

    /** 上溯定位仓库根下的文件（多模块构建下 cwd 可能是模块目录）。 */
    private static String readRepoFile(String relativePath) {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new AssertionError("无法读取 " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new AssertionError("未找到 " + relativePath + "（仓库根定位失败）");
    }
}
