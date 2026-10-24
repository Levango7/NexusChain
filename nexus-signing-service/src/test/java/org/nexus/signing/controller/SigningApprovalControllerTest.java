package org.nexus.signing.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nexus.signing.approval.MapApprovalStore;
import org.nexus.signing.approval.SigningApprovalRequest;
import org.nexus.signing.approval.SigningApprovalService;
import org.nexus.signing.audit.AuditEvent;
import org.nexus.signing.audit.AuditLogService;
import org.nexus.signing.mpc.MpcApprovalPolicy;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SigningApprovalController} 的决策语义（#50）。
 *
 * <p>这一层要钉住的是<b>形状</b>：谁的身份算审批人、哪些输入落哪种状态码、拒绝时审计里
 * 有没有可区分的理由。角色鉴权本身（401/403）由 {@code SigningApprovalDecisionAuthIT}
 * 在真实过滤器链上验，standalone MockMvc 不加载 Spring Security，重复断言只会得到一个
 * 永远为真的空判据。</p>
 *
 * <p>服务与存储都用真的（{@link MapApprovalStore} + 真实
 * {@link SigningApprovalService}），只有审计与 MPC 策略打桩——把 {@code approve()} 打桩成
 * 常数会把"白名单判定 + 状态迁移 + 审计"这三件真正要被验的事全遮掉。</p>
 */
class SigningApprovalControllerTest {

    private static final String JWT_SUBJECT = "bob@nexus";

    private MpcApprovalPolicy mpcPolicy;
    private AuditLogService auditLog;
    private SigningApprovalService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mpcPolicy = mock(MpcApprovalPolicy.class);
        when(mpcPolicy.getRequiredApprovers(any(), any())).thenReturn(1);
        auditLog = mock(AuditLogService.class);
        when(auditLog.extractClientIp(any())).thenReturn("203.0.113.9");
        service = serviceWithWhitelist("alice@nexus, bob@nexus");
        // setControllerAdvice 不是可有可无的装饰：decision 非法时控制器抛
        // IllegalArgumentException，靠 GlobalExceptionHandler 才映射成 400；
        // 不挂 advice 的 standalone 栈会把它当 ServletException 抛出来（实测就是这样红过一次）。
        mockMvc = MockMvcBuilders.standaloneSetup(new SigningApprovalController(service, auditLog))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(JWT_SUBJECT, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================== 放行 ====================

    @Test
    @DisplayName("名单内审批人 → 200，状态推到 APPROVED，审计记 SUCCESS + 来源 IP")
    void whitelistedApproverIsAccepted() throws Exception {
        String rid = createPendingRequest();

        mockMvc.perform(post("/api/v1/signing/approvals/{rid}/decision", rid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"comment\":\"recon ticket 42\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2000))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.decision").value("APPROVE"))
                .andExpect(jsonPath("$.data.approvals_count").value(1));

        AuditEvent decision = lastDecisionEvent();
        assertEquals(AuditEvent.Outcome.SUCCESS, decision.getOutcome());
        assertEquals(JWT_SUBJECT, decision.getActor());
        assertEquals("203.0.113.9", decision.getSourceIp());
        assertEquals("recon ticket 42", decision.getDetails().get("comment"),
                "备注要进审计——否则审计里只有「谁批的」，没有「凭什么批」");
    }

    @Test
    @DisplayName("REJECT 走同一条路 → 200，状态 REJECTED（拒绝是合法决策，不是鉴权失败）")
    void rejectDecisionIsAccepted() throws Exception {
        String rid = createPendingRequest();

        mockMvc.perform(post("/api/v1/signing/approvals/{rid}/decision", rid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.decision").value("REJECT"));

        AuditEvent decision = lastDecisionEvent();
        assertEquals("REJECT", decision.getDetails().get("decision"));
        assertFalse(decision.getDetails().containsKey("comment"),
                "没填备注时不该出现 comment 键（空串/null 都不该污染审计）");
    }

    // ==================== 身份只认 JWT ====================

    @Test
    @DisplayName("请求体里塞 approver 不生效：记账的是 JWT subject")
    void bodyCannotChooseTheApprover() throws Exception {
        String rid = createPendingRequest();

        mockMvc.perform(post("/api/v1/signing/approvals/{rid}/decision", rid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"approver\":\"mallory@nexus\"}"))
                .andExpect(status().isOk());

        SigningApprovalRequest after = service.getRequest(rid);
        assertNotNull(after);
        assertTrue(after.getApprovals().contains(JWT_SUBJECT),
                "审批人必须是已认证主体，实际=" + after.getApprovals());
        assertFalse(after.getApprovals().contains("mallory@nexus"),
                "调用方自填的审批人绝不能被记账");
    }

    // ==================== 拒绝 ====================

    @Test
    @DisplayName("白名单没配 → 403 + approver_whitelist_not_configured，状态不变")
    void missingWhitelistIsEchoedToCaller() throws Exception {
        ReflectionTestUtils.setField(service, "approverWhitelist", "");
        String rid = createPendingRequest();

        MvcResult result = mockMvc.perform(post("/api/v1/signing/approvals/{rid}/decision", rid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.denied_reason")
                        .value("approver_whitelist_not_configured"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        assertNotNull(result);

        AuditEvent decision = lastDecisionEvent();
        assertEquals(AuditEvent.Outcome.DENIED, decision.getOutcome());
        assertEquals("approver_whitelist_not_configured", decision.getDetails().get("denied_reason"),
                "调用方看到的理由必须与审计里的同一条，否则运维排障要猜");
    }

    @Test
    @DisplayName("身份不在名单 → 403 + approver_not_in_whitelist")
    void unlistedApproverIsRefused() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("mallory@nexus", null, List.of()));
        String rid = createPendingRequest();

        mockMvc.perform(post("/api/v1/signing/approvals/{rid}/decision", rid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.denied_reason").value("approver_not_in_whitelist"));
    }

    // ==================== 其它状态码 ====================

    @Test
    @DisplayName("requestId 不存在 → 404")
    void unknownRequestIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/signing/approvals/{rid}/decision", "no-such-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(4040));
    }

    @Test
    @DisplayName("请求已不在待审状态 → 409，且决策不被记账")
    void decidedRequestRejectsFurtherDecisions() throws Exception {
        String rid = createPendingRequest();
        mockMvc.perform(post("/api/v1/signing/approvals/{rid}/decision", rid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/signing/approvals/{rid}/decision", rid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.statusCode").value(4090))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        assertEquals(java.util.Set.of(JWT_SUBJECT), service.getRequest(rid).getApprovals(),
                "409 必须是「什么都没记」，而不是先记再拒");
    }

    @Test
    @DisplayName("decision 缺失或非法 → 400（不能把「没说清」当成拒绝或放行）")
    void invalidDecisionIsBadRequest() throws Exception {
        String rid = createPendingRequest();

        mockMvc.perform(post("/api/v1/signing/approvals/{rid}/decision", rid)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/signing/approvals/{rid}/decision", rid)
                        .contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/signing/approvals/{rid}/decision", rid)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());

        assertEquals(SigningApprovalRequest.Status.PENDING, service.getRequest(rid).getStatus(),
                "三次坏输入都不该动状态");
    }

    // ==================== 状态查询（执行腿的轮询入口） ====================

    @Test
    @DisplayName("GET 状态：PENDING 快照含人数与时间，且不回显审批人地址")
    void getsPendingApprovalSnapshot() throws Exception {
        String rid = createPendingRequest();

        mockMvc.perform(get("/api/v1/signing/approvals/{rid}", rid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2000))
                .andExpect(jsonPath("$.data.requestId").value(rid))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.expired").value(false))
                .andExpect(jsonPath("$.data.amount").value(50000))
                .andExpect(jsonPath("$.data.currency").value("USDT"))
                .andExpect(jsonPath("$.data.required_approvers").value(1))
                .andExpect(jsonPath("$.data.approvals_count").value(0))
                .andExpect(jsonPath("$.data.rejections_count").value(0))
                .andExpect(jsonPath("$.data.approvals").doesNotExist())
                .andExpect(jsonPath("$.data.rejections").doesNotExist());
    }

    @Test
    @DisplayName("GET 状态：决策后返回 APPROVED，调用方据此决定是否执行")
    void getsApprovedStatusAfterDecision() throws Exception {
        String rid = createPendingRequest();
        mockMvc.perform(post("/api/v1/signing/approvals/{rid}/decision", rid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/signing/approvals/{rid}", rid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvals_count").value(1));
    }

    @Test
    @DisplayName("GET 状态：未知 requestId → 404，与「已过期/已清理」同一口径")
    void getUnknownRequestIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/signing/approvals/{rid}", "no-such-request"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(4040));
    }

    // ==================== 辅助 ====================

    private SigningApprovalService serviceWithWhitelist(String whitelist) {
        SigningApprovalService created = new SigningApprovalService(mpcPolicy, auditLog,
                new MapApprovalStore(new ConcurrentHashMap<>()), null);
        ReflectionTestUtils.setField(created, "approverWhitelist", whitelist);
        return created;
    }

    private String createPendingRequest() {
        String rid = service.createApprovalRequest("pkA", "pkB",
                new BigDecimal("50000"), "USDT", "init", "10.0.0.0");
        assertNotNull(rid, "测试夹具必须真的创建出待审请求，否则后面全是空判据");
        return rid;
    }

    /** 最后一条 APPROVAL_DECISION 审计事件（APPROVAL_REQUEST 事件也走同一个 log 方法，要按类型筛）。 */
    private AuditEvent lastDecisionEvent() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLog, atLeastOnce()).log(captor.capture());
        AuditEvent last = null;
        for (AuditEvent e : captor.getAllValues()) {
            if (e.getType() == AuditEvent.Type.APPROVAL_DECISION) {
                last = e;
            }
        }
        assertNotNull(last, "决策必须留下 APPROVAL_DECISION 审计");
        return last;
    }
}
