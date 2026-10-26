package org.nexus.gateway.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计查询端点 Web 层测试（P0 修复 2026-10-11；商户隔离 2026-10-26）。
 *
 * <p>补足单元测试之外的两件事：<b>端点真能被 HTTP 调用且返回正确 JSON</b>，
 * 以及 <b>只读契约</b>——审计表 append-only，任何写方法都不该打通（POST 应 4xx/405）。</p>
 *
 * <p>2026-10-26 新增：<b>HTTP 层的越权拒绝</b>。单元测试断言「抛 MerchantOwnershipException」，
 * 这里断言它<b>真的变成 403</b>（经 {@code GlobalExceptionHandler}）——
 * 只测到抛异常、没测到状态码，等于没验证客户端看到什么。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuditEventControllerWebTest {

    private static final Long CALLER = 700L;
    private static final String ATTR = MerchantOwnershipGuard.MERCHANT_ID_ATTR;

    @Mock
    private AuditEventRepository repository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 带上生产同款异常处理：否则 IAE 会冒泡成 500（与生产 400 不一致，
        // 首轮测试即因缺 advice 出现过假红——这正是 Web 层测试的价值）
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuditEventController(repository, new MerchantOwnershipGuard()))
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

    // ==================== 按商户 ====================

    @Test
    @DisplayName("GET /merchant/{id}：查自己 → 200 + JSON 数组")
    void byMerchant_own_returnsJson() throws Exception {
        when(repository.findByMerchantIdOrderByEventTimeDesc(CALLER))
                .thenReturn(List.of(event("REFUND_EXECUTED", CALLER)));

        mockMvc.perform(get("/api/v1/audit/events/merchant/" + CALLER).requestAttr(ATTR, CALLER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("REFUND_EXECUTED"))
                .andExpect(jsonPath("$[0].resource").value("order:ORD-1"))
                .andExpect(jsonPath("$[0].merchantId").value(700));
    }

    @Test
    @DisplayName("GET /merchant/{id}：查他人 → 403（修复前 200 且带他人数据）")
    void byMerchant_other_403() throws Exception {
        mockMvc.perform(get("/api/v1/audit/events/merchant/999").requestAttr(ATTR, CALLER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /merchant/{id}：无商户上下文 → 403")
    void byMerchant_noContext_403() throws Exception {
        mockMvc.perform(get("/api/v1/audit/events/merchant/700"))
                .andExpect(status().isForbidden());
    }

    // ==================== 按动作 ====================

    @Test
    @DisplayName("GET /action/{action} → 200（限定当前商户）")
    void byAction_scopedToCaller() throws Exception {
        when(repository.findByMerchantIdAndActionOrderByEventTimeDesc(
                CALLER, "CHANNEL_REFUND_EXECUTED"))
                .thenReturn(List.of(event("CHANNEL_REFUND_EXECUTED", CALLER)));

        mockMvc.perform(get("/api/v1/audit/events/action/CHANNEL_REFUND_EXECUTED")
                        .requestAttr(ATTR, CALLER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("CHANNEL_REFUND_EXECUTED"));
    }

    // ==================== 按时间窗 ====================

    @Test
    @DisplayName("GET /window 参数非法 → 4xx（from 不早于 to）")
    void byWindow_badRange_4xx() throws Exception {
        mockMvc.perform(get("/api/v1/audit/events/window")
                        .param("from", "2026-10-11T12:00:00Z")
                        .param("to", "2026-10-11T10:00:00Z")
                        .requestAttr(ATTR, CALLER))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("GET /window 合法 → 200（限定当前商户）")
    void byWindow_happyPath() throws Exception {
        when(repository.findByMerchantIdAndEventTimeBetweenOrderByEventTimeDesc(
                eq(CALLER), any(), any()))
                .thenReturn(List.of(event("REFUND_APPROVED", CALLER)));

        mockMvc.perform(get("/api/v1/audit/events/window")
                        .param("from", "2026-10-11T00:00:00Z")
                        .param("to", "2026-10-12T00:00:00Z")
                        .requestAttr(ATTR, CALLER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("REFUND_APPROVED"));
    }

    // ==================== 分页兜底 ====================

    @Test
    @DisplayName("GET / → 200 分页视图（限定当前商户）")
    void recent_ok() throws Exception {
        when(repository.findByMerchantId(eq(CALLER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/audit/events").requestAttr(ATTR, CALLER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.total").value(0));
    }

    // ==================== 只读契约 ====================

    @Test
    @DisplayName("只读契约：审计端点不提供任何写入口（POST → 405）")
    void writeMethods_notAllowed() throws Exception {
        mockMvc.perform(post("/api/v1/audit/events").requestAttr(ATTR, CALLER))
                .andExpect(status().isMethodNotAllowed());
    }
}
