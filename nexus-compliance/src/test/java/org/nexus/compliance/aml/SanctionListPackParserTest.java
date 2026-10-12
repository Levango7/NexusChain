package org.nexus.compliance.aml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 制裁名单离线包解析器测试（2026-10-12 新增）。
 *
 * <p>钉住三类真实格式，重点是**"取对列/取对字段"**：旧实现"取首列"喂 OFAC SDN CSV
 * 得到的是一列 {@code ent_num} 数字——名单看起来加载了、实际永远匹配不到人。</p>
 */
class SanctionListPackParserTest {

    private static final String OFAC_CSV = """
            ent_num,SDN_Name,SDN_Type,Program
            123,"ACME SHIPPING LTD",Entity,IRAN
            124,"DOE, JOHN",Individual,SDGT
            125,-0-,Individual,SDGT
            126,,Entity,CUBA
            """;

    private static final String UN_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <CONSOLIDATED_LIST>
              <INDIVIDUALS>
                <INDIVIDUAL>
                  <DATAID>1</DATAID>
                  <FIRST_NAME>JOHN</FIRST_NAME>
                  <SECOND_NAME>DOE</SECOND_NAME>
                  <THIRD_NAME>SMITH</THIRD_NAME>
                  <INDIVIDUAL_ALIAS><ALIAS_NAME>JOHNNY</ALIAS_NAME></INDIVIDUAL_ALIAS>
                </INDIVIDUAL>
                <INDIVIDUAL>
                  <DATAID>2</DATAID>
                  <FIRST_NAME>-0-</FIRST_NAME>
                </INDIVIDUAL>
              </INDIVIDUALS>
              <ENTITIES>
                <ENTITY><DATAID>9</DATAID><ENTITY_NAME>ACME SHIPPING LTD</ENTITY_NAME></ENTITY>
              </ENTITIES>
            </CONSOLIDATED_LIST>
            """;

    @Test
    @DisplayName("OFAC CSV：按 SDN_Name 列取姓名（不是取首列的 ent_num）")
    void ofacCsv_takesSdnNameColumn() {
        SanctionListPackParser.Result r = SanctionListPackParser.parse(OFAC_CSV, SanctionListPackParser.Format.AUTO);

        assertEquals(SanctionListPackParser.Format.OFAC_CSV, r.getFormat(), "应识别为 OFAC CSV");
        List<String> e = r.getEntries();
        assertTrue(e.contains("ACME SHIPPING LTD"));
        assertTrue(e.contains("DOE, JOHN"), "带引号的逗号不能被切开: " + e);
        assertFalse(e.contains("123"), "不得把 ent_num 当姓名");
        assertFalse(e.contains("124"));
        assertEquals(2, r.getSkipped(), "-0- 与空值应计入 skipped");
    }

    @Test
    @DisplayName("OFAC CSV：无表头时按官方列序（第 2 列=姓名），表头行不混入")
    void ofacCsv_withoutHeader_usesOfficialColumnOrder() {
        String noHeader = "1,ALPHA CORP,Entity\n2,BETA LTD,Entity\n";
        SanctionListPackParser.Result r = SanctionListPackParser.parse(noHeader, SanctionListPackParser.Format.OFAC_CSV);
        assertEquals(List.of("ALPHA CORP", "BETA LTD"), r.getEntries());
    }

    @Test
    @DisplayName("UN XML：INDIVIDUAL 三段名拼接 + ENTITY 名称；别名人名不入名单")
    void unXml_individualAndEntity() {
        SanctionListPackParser.Result r = SanctionListPackParser.parse(UN_XML, SanctionListPackParser.Format.AUTO);

        assertEquals(SanctionListPackParser.Format.UN_XML, r.getFormat());
        assertTrue(r.getEntries().contains("JOHN DOE SMITH"), r.getEntries().toString());
        assertTrue(r.getEntries().contains("ACME SHIPPING LTD"));
        assertFalse(r.getEntries().contains("JOHNNY"), "别名不应入名单（避免误伤同名）");
        assertFalse(r.getEntries().contains(""), "空名不应入名单");
    }

    @Test
    @DisplayName("UN XML：拒绝含 DTD 的文件（XXE 防护）")
    void unXml_rejectsDoctype() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <CONSOLIDATED_LIST><INDIVIDUALS><INDIVIDUAL>
                  <FIRST_NAME>&xxe;</FIRST_NAME>
                </INDIVIDUAL></INDIVIDUALS></CONSOLIDATED_LIST>
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SanctionListPackParser.parse(xxe, SanctionListPackParser.Format.AUTO));
        assertTrue(ex.getMessage().contains("DTD"), ex.getMessage());
    }

    @Test
    @DisplayName("纯文本兜底：注释/空行忽略，含逗号取首列（与旧行为兼容）")
    void lines_fallback() {
        String text = "# 客户自备名单\n\nALPHA CORP,备注\nBETA LTD\n";
        SanctionListPackParser.Result r = SanctionListPackParser.parse(text, SanctionListPackParser.Format.AUTO);

        assertEquals(SanctionListPackParser.Format.LINES, r.getFormat());
        assertEquals(List.of("ALPHA CORP", "BETA LTD"), r.getEntries());
    }

    @Test
    @DisplayName("空内容 → 0 条（调用方据此 fail-closed）")
    void emptyContent() {
        assertEquals(0, SanctionListPackParser.parse("   \n\n", SanctionListPackParser.Format.AUTO).getCount());
        assertEquals(0, SanctionListPackParser.parse(null, SanctionListPackParser.Format.AUTO).getCount());
    }
}
