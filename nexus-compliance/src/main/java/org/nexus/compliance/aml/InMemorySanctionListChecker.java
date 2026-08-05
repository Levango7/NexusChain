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

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
