package org.nexus.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prometheus 告警规则文件的解析工具（PLAN-062 / PLAN-063 共用）。
 *
 * <p>存在的唯一理由：{@code BlockPersistenceRulesContractTest} 与
 * {@code FinalityRulesContractTest} 都要读
 * {@code deploy/k8s/50-prometheus-rules.yaml} 的一个告警组、取出它 {@code expr}
 * 里引用的 {@code nexus_*} 指标名。解析逻辑写两遍必然漂移，所以收在一处。</p>
 *
 * <p><b>刻意不做</b>：不做 YAML 通用解析、不校验规则语义、不capture 非
 * {@code expr} 字段里的名字（description 里提指标名是给人看的，不是契约）。
 * 只回答两个问题：这个组的 expr 引用了哪些指标<b>名字</b>（PLAN-062/063），
 * 以及这些名字在 expr 里<b>怎么被用</b>（PLAN-074 的引用形状门禁）。</p>
 */
final class PrometheusRulesFixture {

    static final String RULES_RELATIVE_PATH = "deploy/k8s/50-prometheus-rules.yaml";

    /** Micrometer 把 FunctionCounter 暴露为 {@code <name>_total}；Gauge 原样。 */
    private static final String PROMETHEUS_COUNTER_SUFFIX = "_total";

    private static final Pattern METER_NAME = Pattern.compile("nexus_[a-zA-Z0-9_]+");

    private PrometheusRulesFixture() {
    }

    /** 定位部署规则文件（多模块构建下 tests 运行目录可能是模块目录，故上溯）。 */
    private static Path rulesFile() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(RULES_RELATIVE_PATH);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * 取某个告警组的全部 {@code expr} 文本（单行 {@code expr: ...} 与
     * 块标量 {@code expr: |} 都支持）。
     *
     * <p>组边界：下一个顶层 {@code - name: xxx}，或下一个 {@code # ----} 章节注释。
     * 覆盖不到组末尾到文件末尾之间没有下一个组名的情形（用 end = 行数兜底）。</p>
     */
    private static String groupExprText(String group) throws IOException {
        Path rules = rulesFile();
        if (rules == null) {
            throw new IOException("未找到 " + RULES_RELATIVE_PATH + "（仓库根定位失败）");
        }
        java.util.List<String> lines = Files.readAllLines(rules, StandardCharsets.UTF_8);

        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals("- name: " + group)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            throw new IOException("规则文件里没有告警组 " + group + "：" + rules);
        }

        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.matches("^\\s{2}- name: \\S+.*") || line.startsWith("  # ----")) {
                end = i;
                break;
            }
        }

        StringBuilder exprs = new StringBuilder();
        boolean collecting = false;
        for (int i = start; i < end; i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("expr:")) {
                String inline = trimmed.substring("expr:".length());
                if ("|".equals(inline.trim()) || "|-".equals(inline.trim())) {
                    collecting = true;              // 块标量：收集后续更深缩进行
                    continue;
                }
                exprs.append(inline).append('\n');
                continue;
            }
            if (collecting) {
                // expr 块标量内的行都比 alert 项缩进更深；遇到同级/更浅的行即结束
                if (!line.startsWith("        ")) {
                    collecting = false;
                } else {
                    exprs.append(trimmed).append('\n');
                }
            }
        }
        return exprs.toString();
    }

    /**
     * 组内每条告警的 expr 文本（**带 alert 名**），PLAN-074 的引用形状门禁用。
     *
     * <p>与 {@link #groupExprText(String)} 的区别：那个把全部 expr 拼成一个字符串
     * （只回答"引用了哪些名字"）；这个保留"哪条告警的哪一行"（回答"这个名字在
     * 表达式里怎么被用"）。返回的每行是 {@code alert名\texpr行}，正文仍是
     * {@link #groupExprText(String)} 同一套提取规则的产物（inline + 块标量），
     * 不另立一套解析，避免漂移。</p>
     *
     * <p><b>为什么不让调用方自己扫文件</b>：第一版就是那么写的，结果把
     * {@code description} 里给人看的指标名、组级 {@code #} 注释行也算成"引用"，
     * 并把组级说明块算进下一条 alert——三个假阳性。只有"只取 expr 字段"能避开。</p>
     */
    static java.util.List<String> groupExprLines(String group) throws IOException {
        Path rules = rulesFile();
        if (rules == null) {
            throw new IOException("未找到 " + RULES_RELATIVE_PATH + "（仓库根定位失败）");
        }
        java.util.List<String> lines = Files.readAllLines(rules, StandardCharsets.UTF_8);

        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals("- name: " + group)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            throw new IOException("规则文件里没有告警组 " + group + "：" + rules);
        }

        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.matches("^\\s{2}- name: \\S+.*") || line.startsWith("  # ----")) {
                end = i;
                break;
            }
        }

        java.util.List<String> out = new java.util.ArrayList<>();
        String alert = "(组头部)";
        for (int i = start; i < end; i++) {
            String line = lines.get(i);
            String trimmed = line.trim();

            Matcher alertMatcher = Pattern.compile("^- alert:\\s*(\\S+)").matcher(trimmed);
            if (alertMatcher.find()) {
                alert = alertMatcher.group(1);
                continue;
            }

            if (trimmed.startsWith("expr:")) {
                String inline = trimmed.substring("expr:".length());
                if ("|".equals(inline.trim()) || "|-".equals(inline.trim())) {
                    // 块标量：后续更深缩进的行都属于这段 expr
                    for (int j = i + 1; j < end; j++) {
                        String block = lines.get(j);
                        if (!block.startsWith("        ")) {
                            break;
                        }
                        out.add(alert + "\t" + block.trim());
                    }
                } else {
                    out.add(alert + "\t" + inline.trim());
                }
            }
        }
        return out;
    }

    /** 组 expr 里引用的 {@code nexus_*} 名字，Prometheus 侧原样（counter 带 {@code _total}）。 */
    private static Set<String> rawMeterNames(String group) throws IOException {
        return collectNames(groupExprText(group));
    }

    /**
     * 归一化到 {@code SimpleMeterRegistry} 侧的名字。
     *
     * <p>Prometheus 把 FunctionCounter 暴露为 {@code <name>_total}，而 registry 里的
     * meter 名不带该后缀。不先归一化就会"名字明明一样也对不上"——PLAN-062 第一版
     * 就在这虚报过一次。</p>
     */
    static Set<String> normalizedMeterNames(String group) throws IOException {
        Set<String> normalized = new TreeSet<>();
        for (String raw : rawMeterNames(group)) {
            if (raw.endsWith(PROMETHEUS_COUNTER_SUFFIX)) {
                normalized.add(raw.substring(
                        0, raw.length() - PROMETHEUS_COUNTER_SUFFIX.length()));
            } else {
                normalized.add(raw);
            }
        }
        return normalized;
    }

    private static Set<String> collectNames(String exprs) {
        Set<String> names = new TreeSet<>();
        Matcher m = METER_NAME.matcher(exprs);
        while (m.find()) {
            names.add(m.group());
        }
        return names;
    }
}
