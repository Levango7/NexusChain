package org.nexus.gateway.compliance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nexus.compliance.aml.InMemorySanctionListChecker;
import org.nexus.compliance.aml.SanctionListStartupLoader;
import org.nexus.gateway.audit.AuditEvent;
import org.nexus.gateway.audit.AuditLogService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 制裁名单「离线包导入」Web 层测试（2026-10-12）。
 *
 * <p>钉住四件事：① 官方格式（OFAC CSV / UN XML）导进来是**姓名**而不是 ID；
 * ② 空包**绝不清空名单**（fail-closed，400）；③ replace 模式显式可用且动账可查；
 * ④ 每次导入落审计（含操作人与版本）+ 版本台账。</p>
 */
class SanctionListImportWebTest {

    private InMemorySanctionListChecker checker;
    private AuditLogService auditLogService;
    private SanctionListImportRepository importRepository;
    private SanctionListController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        checker = new InMemorySanctionListChecker();
        auditLogService = mock(AuditLogService.class);
        importRepository = mock(SanctionListImportRepository.class);
        controller = new SanctionListController(checker, mock(SanctionListStartupLoader.class),
                auditLogService, importRepository);
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new org.nexus.gateway.config.GlobalExceptionHandler())
                .build();
    }

    private static final String OFAC_CSV = """
            ent_num,SDN_Name,SDN_Type
            1,"ACME SHIPPING LTD",Entity
            2,-0-,Individual
            """;

    @Test
    @DisplayName("裸体直传 OFAC CSV：导入的是姓名、写审计（含版本）、落版本台账")
    void importPack_rawCsv_worksAndRecordsLedger() throws Exception {
        mockMvc.perform(post("/api/v1/compliance/sanction-list/import")
                        .contentType("text/csv")
                        .content(OFAC_CSV)
                        .param("source", "OFAC_SDN")
                        .param("version", "2026-10-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("OFAC_CSV"))
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.total").value(1));

        // 真的能命中（这才是"导入"的意义）
        assertTrue(checker.check("ACME SHIPPING LTD").length > 0);

        // 审计：action 正确 + detail 带来源/版本/条目数（2026-10-12 修好"明细被丢弃"）
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogService).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertEquals("SANCTION_LIST_IMPORTED", event.getAction());
        assertTrue(event.getDetail().contains("source=OFAC_SDN"), event.getDetail());
        assertTrue(event.getDetail().contains("version=2026-10-12"), event.getDetail());

        // 版本台账：source/version/格式/条目数/哈希都要落
        ArgumentCaptor<SanctionListImportEntity> ledger = ArgumentCaptor.forClass(SanctionListImportEntity.class);
        verify(importRepository).save(ledger.capture());
        assertEquals("OFAC_SDN", ledger.getValue().getSource());
        assertEquals("2026-10-12", ledger.getValue().getVersion());
        assertEquals("OFAC_CSV", ledger.getValue().getFormat());
        assertEquals("append", ledger.getValue().getMode());
        assertEquals(1, ledger.getValue().getTotalAfter());
        assertNotNull(ledger.getValue().getContentSha256());
    }

    @Test
    @DisplayName("空包（解析 0 条）→ 400 且名单不变（fail-closed，绝不'用空包清空名单'）")
    void importPack_emptyParsed_rejectedAndListUnchanged() throws Exception {
        checker.addEntry("EXISTING NAME");

        mockMvc.perform(post("/api/v1/compliance/sanction-list/import")
                        .contentType("text/plain")
                        .content("\n# 只有注释\n\n")
                        .param("source", "CUSTOM"))
                .andExpect(status().isBadRequest());

        assertEquals(1, checker.size(), "名单不得因空包而变更");
        assertTrue(checker.check("EXISTING NAME").length > 0);
        verify(importRepository, never()).save(any());
    }

    @Test
    @DisplayName("replace 模式：显式指定才整体替换，返回真正消失的旧条目数")
    void importPack_replaceMode_removesOldEntries() throws Exception {
        checker.addEntry("OLD NAME A");
        checker.addEntry("OLD NAME B");

        mockMvc.perform(post("/api/v1/compliance/sanction-list/import")
                        .contentType("text/csv")
                        .content(OFAC_CSV)
                        .param("source", "OFAC_SDN")
                        .param("mode", "replace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("replace"))
                .andExpect(jsonPath("$.removed").value(2));

        assertEquals(0, checker.check("OLD NAME A").length, "replace 后旧条目应消失");
        assertTrue(checker.check("ACME SHIPPING LTD").length > 0);
    }

    @Test
    @DisplayName("JSON 形态：content 直传可行；缺 source → 400")
    void importPack_jsonForm_andMissingSource() throws Exception {
        String json = "{\"source\":\"UN_CONSOLIDATED\",\"version\":\"2026-06\",\"content\":"
                + "\"<?xml version=\\\"1.0\\\"?><CONSOLIDATED_LIST><INDIVIDUALS><INDIVIDUAL>"
                + "<FIRST_NAME>JOHN</FIRST_NAME><SECOND_NAME>DOE</SECOND_NAME>"
                + "</INDIVIDUAL></INDIVIDUALS></CONSOLIDATED_LIST>\"}";
        mockMvc.perform(post("/api/v1/compliance/sanction-list/import")
                        .contentType("application/json")
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("UN_XML"));
        assertTrue(checker.check("JOHN DOE").length > 0);

        mockMvc.perform(post("/api/v1/compliance/sanction-list/import")
                        .contentType("text/plain")
                        .content("SOME NAME"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("版本台账查询：返回最近记录与当前版本")
    void importsHistory_returnsCurrent() throws Exception {
        SanctionListImportEntity e = new SanctionListImportEntity();
        e.setSource("OFAC_SDN");
        e.setVersion("2026-10-12");
        e.setFormat("OFAC_CSV");
        e.setMode("append");
        e.setImportedCount(100);
        e.setSkippedCount(3);
        e.setRemovedCount(0);
        e.setTotalAfter(100);
        e.setImportedAt(LocalDateTime.now());
        when(importRepository.findAllByOrderByImportedAtDesc(any())).thenReturn(List.of(e));

        mockMvc.perform(get("/api/v1/compliance/sanction-list/imports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.current.source").value("OFAC_SDN"))
                .andExpect(jsonPath("$.current.version").value("2026-10-12"));
    }

    @Test
    @DisplayName("单条增删的审计也带条目明细（修 2026-10-12『明细被丢弃』）")
    void addEntry_auditCarriesEntry() throws Exception {
        mockMvc.perform(post("/api/v1/compliance/sanction-list").param("entry", "TARGET PERSON"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogService).record(captor.capture());
        assertEquals("SANCTION_LIST_ENTRY_ADDED", captor.getValue().getAction());
        assertTrue(captor.getValue().getDetail().contains("TARGET PERSON"),
                "审计必须写明加了谁: " + captor.getValue().getDetail());
    }
}
