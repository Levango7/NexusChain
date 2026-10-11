package org.nexus.gateway.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计查询端点 Web 层测试（P0 修复 2026-10-11）。
 *
 * <p>补足单元测试之外的两件事：<b>端点真能被 HTTP 调用且返回正确 JSON</b>，
 * 以及 <b>只读契约</b>——审计表 append-only，任何写方法都不该打通（POST 应 4xx/405）。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuditEventControllerWebTest {

    @Mock
    private AuditEventRepository repository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 带上生产同款异常处理：否则 IAE 会冒泡成 500（与生产 400 不一致，
        // 首轮测试即因缺 advice 出现过假红——这正是 Web 层测试的价值）
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuditEventController(repository))
                .setControllerAdvice(new org.nexus.gateway.config.GlobalExceptionHandler())
                .build();
    }

    private AuditEventEntity event(String action, Long merchantId) {
        AuditEventEntity e = new AuditEventEntity();
        e.setId(1L);
        e.setAction(action);
        e.setMerchantId(merchantId);
        e.setResource("order:ORD-1");
        e.setEventTime(Instant.parse("2026-10-11T10:00:00Z"));
        return e;
    }

    @Test
    @DisplayName("GET /api/v1/audit/events/merchant/{id} → 200 + JSON 数组")
    void byMerchant_returnsJson() throws Exception {
        when(repository.findByMerchantIdOrderByEventTimeDesc(700L))
                .thenReturn(List.of(event("REFUND_EXECUTED", 700L)));

        mockMvc.perform(get("/api/v1/audit/events/merchant/700"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("REFUND_EXECUTED"))
                .andExpect(jsonPath("$[0].resource").value("order:ORD-1"))
                .andExpect(jsonPath("$[0].merchantId").value(700));
    }

    @Test
    @DisplayName("GET /action/{action} → 200（可按资金动作检索）")
    void byAction_returnsJson() throws Exception {
        when(repository.findByActionOrderByEventTimeDesc("CHANNEL_REFUND_EXECUTED"))
                .thenReturn(List.of(event("CHANNEL_REFUND_EXECUTED", 500L)));

        mockMvc.perform(get("/api/v1/audit/events/action/CHANNEL_REFUND_EXECUTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("CHANNEL_REFUND_EXECUTED"));
    }

    @Test
    @DisplayName("GET /window 参数非法 → 4xx（from 不早于 to）")
    void byWindow_badRange_4xx() throws Exception {
        mockMvc.perform(get("/api/v1/audit/events/window")
                        .param("from", "2026-10-11T12:00:00Z")
                        .param("to", "2026-10-11T10:00:00Z"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("GET /window 合法 → 200")
    void byWindow_happyPath() throws Exception {
        when(repository.findByEventTimeBetween(any(), any()))
                .thenReturn(List.of(event("REFUND_APPROVED", 1L)));

        mockMvc.perform(get("/api/v1/audit/events/window")
                        .param("from", "2026-10-11T00:00:00Z")
                        .param("to", "2026-10-12T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("REFUND_APPROVED"));
    }

    @Test
    @DisplayName("GET / → 200 分页视图")
    void recent_ok() throws Exception {
        when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        mockMvc.perform(get("/api/v1/audit/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("只读契约：审计端点不提供任何写入口（POST → 405）")
    void writeMethods_notAllowed() throws Exception {
        mockMvc.perform(post("/api/v1/audit/events"))
                .andExpect(status().isMethodNotAllowed());
    }
}
