package org.nexus.compliance.aml;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 基于内存的制裁名单检查器实现。
 * <p>
 * 维护制裁名单（姓名 / 地址集合），支持运行时动态增删（对接外部名单
 * 数据源后由同步任务调用 {@link #addEntry} / {@link #removeEntry}）。
 * 匹配策略：归一化后精确匹配得满分 1.0；子串包含匹配得 0.8。
 * </p>
 */
@Component
public class InMemorySanctionListChecker implements SanctionListChecker {

    /** 名单名称 */
    private static final String LIST_NAME = "NEXUS_SANCTION_LIST";

    /** 归一化后的名单条目 */
    private final Set<String> entries = new CopyOnWriteArraySet<>();

    @Override
    public SanctionHit[] check(String nameOrAddress) {
        if (nameOrAddress == null || nameOrAddress.isBlank()) {
            return new SanctionHit[0];
        }
        String normalized = normalize(nameOrAddress);
        List<SanctionHit> hits = new ArrayList<>();
        for (String entry : entries) {
            if (entry.equals(normalized)) {
                hits.add(new SanctionHit(LIST_NAME, 1.0, nameOrAddress));
            } else if (entry.contains(normalized) || normalized.contains(entry)) {
                hits.add(new SanctionHit(LIST_NAME, 0.8, nameOrAddress));
            }
        }
        return hits.toArray(new SanctionHit[0]);
    }

    /**
     * 添加名单条目。
     *
     * @param entry 姓名或地址
     */
    public void addEntry(String entry) {
        if (entry != null && !entry.isBlank()) {
            entries.add(normalize(entry));
        }
    }

    /**
     * 移除名单条目。
     *
     * @param entry 姓名或地址
     */
    public void removeEntry(String entry) {
        if (entry != null) {
            entries.remove(normalize(entry));
        }
    }

    /**
     * 当前名单条目数。
     *
     * @return 条目数
     */
    public int size() {
        return entries.size();
    }

    /**
     * 批量添加名单条目（2026-10-12 新增，供离线名单包导入使用）。
     *
     * <p>与 {@link #addEntry(String)} 同语义（追加、按 {@link #normalize} 归一），
     * 只是省去逐条调用；空值/空白忽略，返回**实际新增**的条目数（已存在的不重复计入）。</p>
     *
     * @param values 待添加条目
     * @return 实际新增条目数
     */
    public int addEntries(java.util.Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (String v : values) {
            if (v == null || v.isBlank()) {
                continue;
            }
            if (entries.add(normalize(v))) {
                added++;
            }
        }
        return added;
    }

    /**
     * 用新名单**整体替换**现有名单（2026-10-12 新增，显式 replace 模式专用）。
     *
     * <p><b>为什么单列一个方法而不是给 addEntries 加开关</b>：替换是**破坏性**操作
     * （会抹掉人工补录的条目），调用方必须显式选择；方法名自带警示，
     * 且调用点（导入端点）在 {@code mode=replace} 之外不会走到这里。</p>
     *
     * @param values 新名单全量条目
     * @return **本次真正消失**的旧条目数（旧有新无；审计用"这次替换动了多少"）
     */
    public int replaceAll(java.util.Collection<String> values) {
        java.util.Set<String> before = new java.util.HashSet<>(entries);
        entries.clear();
        addEntries(values);
        int removed = 0;
        for (String b : before) {
            if (!entries.contains(b)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * 从文件加载名单（P0 补齐 2026-10-11；2026-10-12 改为按**格式自动识别**解析）。
     *
     * <p><b>为什么需要</b>：本类此前只有 {@code addEntry} 一个入口，而**全仓没有调用方**
     * ——名单恒为空 ⇒ {@code check()} 恒返回空 ⇒ AML 筛查形同虚设（"结构接了、数据没有"）。
     * 本方法给运营一个真实的数据入口：把名单文件放到配置路径
     * {@code nexus.compliance.sanction-list-path} 即生效。</p>
     *
     * <p><b>格式（2026-10-12 升级）</b>：交给 {@link SanctionListPackParser} 自动识别
     * OFAC SDN CSV（按 {@code SDN_Name} 列取姓名，而不是"取首列"拿到 ID）、
     * UN 综合名单 XML（FIRST/SECOND/THIRD_NAME 拼接）、以及一行一条的纯文本兜底。</p>
     *
     * <p><b>加载语义</b>：<b>追加</b>而非清空（避免"重载把运行时手工加的条目抹掉"）；
     * 文件不存在/不可读 → 记 WARN 并返回 0（**不阻断启动**，但会显式告警"名单为空"）。</p>
     *
     * @param path 名单文件路径
     * @return 本次加载的有效条目数
     */
    public int loadFromFile(String path) {
        if (path == null || path.isBlank()) {
            return 0;
        }
        java.nio.file.Path file = java.nio.file.Paths.get(path);
        if (!java.nio.file.Files.isRegularFile(file)) {
            org.slf4j.LoggerFactory.getLogger(InMemorySanctionListChecker.class)
                    .warn("[SanctionList] 名单文件不存在，跳过加载（当前名单条目数={}）: path={}",
                            entries.size(), path);
            return 0;
        }
        try {
            String content = java.nio.file.Files.readString(file);
            SanctionListPackParser.Result parsed =
                    SanctionListPackParser.parse(content, SanctionListPackParser.Format.AUTO);
            int added = addEntries(parsed.getEntries());
            org.slf4j.LoggerFactory.getLogger(InMemorySanctionListChecker.class).info(
                    "[SanctionList] 名单加载完成: format={}, parsed={}, skipped={}, added={}, total={}, path={}",
                    parsed.getFormat(), parsed.getCount(), parsed.getSkipped(), added, entries.size(), path);
            if (parsed.getCount() == 0) {
                org.slf4j.LoggerFactory.getLogger(InMemorySanctionListChecker.class).warn(
                        "[SanctionList] 名单文件解析出 0 条有效条目（文件格式是否受支持？）: path={}", path);
            }
            return added;
        } catch (java.io.IOException e) {
            org.slf4j.LoggerFactory.getLogger(InMemorySanctionListChecker.class)
                    .error("[SanctionList] 名单文件读取失败: path={}, error={}", path, e.getMessage());
            return 0;
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(InMemorySanctionListChecker.class)
                    .error("[SanctionList] 名单文件解析失败（已 fail-closed，名单未变更）: path={}, error={}",
                            path, e.getMessage());
            return 0;
        }
    }

    /** 当前名单快照（运营核对用）。 */
    public Set<String> snapshot() {
        return java.util.Set.copyOf(entries);
    }

    /** 名单是否为空（**空名单必须显式告警**：筛查形同虚设）。 */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
