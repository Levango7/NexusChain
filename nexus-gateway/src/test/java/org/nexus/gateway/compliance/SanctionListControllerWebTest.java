package org.nexus.gateway.compliance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.compliance.aml.InMemorySanctionListChecker;
import org.nexus.compliance.aml.SanctionListStartupLoader;
import org.nexus.gateway.audit.AuditEventRepository;
import org.nexus.gateway.audit.AuditLogService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 制裁名单管理端点 Web 层测试（P0 补齐 2026-10-11）。
 *
 * <p>名单此前无数据入口 ⇒ 筛查恒为空。本测试钉住端点真的通了：
 * 增/删/查/重载可用，且**增删写审计**（合规数据变更必须留痕）。</p>
 */
@ExtendWith(MockitoExtension.class)
class SanctionListControllerWebTest {

    @Mock
    private SanctionListStartupLoader loader;

    private InMemorySanctionListChecker checker;
    private AuditLogService auditLogService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        checker = new InMemorySanctionListChecker();
        auditLogService = new AuditLogService(mock(AuditEventRepository.class));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SanctionListController(checker, loader, auditLogService))
                .setControllerAdvice(new org.nexus.gateway.config.GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST 增条目 → 名单生效（能命中）")
    void addEntry_takesEffect() throws Exception {
        mockMvc.perform(post("/api/v1/compliance/sanction-list").param("entry", "OFAC-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1));

        // 端点加进去的条目必须真的能命中（这才是"数据入口"的意义）
        assertTrue(checker.check("OFAC-1").length > 0);
    }

    @Test
    @DisplayName("GET 查看名单 → 返回条目与空判定")
    void listShowsEntries() throws Exception {
        checker.addEntry("OFAC-2");
        mockMvc.perform(get("/api/v1/compliance/sanction-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.empty").value(false));
    }

    @Test
    @DisplayName("DELETE 删条目 → 不再命中")
    void removeEntry_takesEffect() throws Exception {
        checker.addEntry("OFAC-3");
        mockMvc.perform(delete("/api/v1/compliance/sanction-list").param("entry", "OFAC-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(0));
        assertEquals(0, checker.check("OFAC-3").length);
    }

    @Test
    @DisplayName("POST /reload → 走加载器并回报 loaded/size")
    void reloadDelegates() throws Exception {
        when(loader.reload()).thenReturn(3);
        mockMvc.perform(post("/api/v1/compliance/sanction-list/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded").value(3));
        verify(loader).reload();
    }

    @Test
    @DisplayName("空 entry → 400（参数错，不静默吞）")
    void blankEntry_400() throws Exception {
        mockMvc.perform(post("/api/v1/compliance/sanction-list").param("entry", "  "))
                .andExpect(status().isBadRequest());
    }
}
