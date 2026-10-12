package org.nexus.compliance.aml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 制裁名单数据入口门禁（P0 补齐 2026-10-11）。
 *
 * <p>此前名单没有数据入口（addEntry 全仓零调用方）⇒ check() 恒为空 ⇒ AML 筛查形同虚设。
 * 本测试钉住：① 文件加载能真正命中；② 注释/空行/CSV 兼容；③ 追加语义（不清运行时条目）；
 * ④ 缺路径/坏文件不阻断（仅告警）且空名单可判定。</p>
 */
class SanctionListLoadTest {

    private InMemorySanctionListChecker checker;

    @BeforeEach
    void setUp() {
        checker = new InMemorySanctionListChecker();
    }

    private Path writeList(String content) throws Exception {
        Path f = Files.createTempFile("sanction-", ".txt");
        Files.writeString(f, content);
        return f;
    }

    @Test
    @DisplayName("加载后能真正命中：归一化（大小写/空白）+ 精确 1.0")
    void loadedEntriesAreHit() throws Exception {
        Path f = writeList("# 制裁名单样例\nOFAC-ENTRY-001\n\n  Bad Actor Ltd  \n");

        int loaded = checker.loadFromFile(f.toString());

        assertEquals(2, loaded, "注释与空行不计入");
        assertEquals(1, checker.check("bad actor ltd").length, "归一化后应精确命中");
        assertEquals(1.0, checker.check("OFAC-ENTRY-001")[0].getMatchScore(), 0.0001);
    }

    @Test
    @DisplayName("CSV 兼容：含逗号取第一列")
    void csvFirstColumn() throws Exception {
        Path f = writeList("SANCTIONED-ADDR,extra,fields\n");
        checker.loadFromFile(f.toString());
        assertTrue(checker.check("SANCTIONED-ADDR").length > 0, "CSV 第一列应被识别并命中");
    }

    @Test
    @DisplayName("追加语义：重载不清空运行时手工添加的条目")
    void reloadIsAdditive() throws Exception {
        checker.addEntry("MANUAL-ENTRY");
        Path f = writeList("FILE-ENTRY\n");

        checker.loadFromFile(f.toString());

        assertEquals(2, checker.size(), "手工条目应保留（追加而非清空）");
        assertTrue(checker.check("MANUAL-ENTRY").length > 0);
        assertTrue(checker.check("FILE-ENTRY").length > 0);
    }

    @Test
    @DisplayName("fail-safe：路径为空/文件不存在 → 返回 0 且不抛（不阻断启动），空名单可判定")
    void missingFileDoesNotThrow() {
        assertEquals(0, checker.loadFromFile(null));
        assertEquals(0, checker.loadFromFile("   "));
        assertEquals(0, checker.loadFromFile("/nonexistent/sanction-list.txt"));
        assertTrue(checker.isEmpty(), "空名单必须可判定（调用方据此告警：筛查形同虚设）");
    }

    @Test
    @DisplayName("空名单不误命中：任何输入都返回空")
    void emptyListNeverHits() {
        assertTrue(checker.isEmpty());
        assertEquals(0, checker.check("anyone").length);
        assertEquals(0, checker.check("").length);
        assertEquals(0, checker.check(null).length);
    }
}
