package org.nexus.signing.approval;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nexus.signing.audit.AuditEvent;
import org.nexus.signing.audit.AuditLogService;
import org.nexus.signing.mpc.MpcApprovalPolicy;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批人白名单的 fail-closed 语义（#50 的前置）。
 *
 * <p>旧实现在白名单未配置时返回"允许"（注释原文："未配置白名单则不限制"），于是
 * "谁能批准大额签名"这件事的<b>默认答案是谁都能</b>。大额签名的审批是资金路径上最后一道
 * 人工闸门，缺配置必须等于"没人能批"。本类写的时候 {@code approve()}/{@code reject()}
 * 还没有任何生产调用方（#45 已核实：signing 当时没有审批决策端点），所以那次改动对
 * 运行中的系统零影响；它改变的是"等端点接上时，默认值是开还是关"。端点已于 #50 落地
 * （{@code POST /api/v1/signing/approvals/{requestId}/decision}），fail-closed 因此
 * 从"将来的默认值"变成"当下的实际行为"。</p>
 *
 * <p>拒绝原因分三种可区分的字符串，是为了让审计里"没配"与"不在名单"不被混成一条：
 * 前者要运维补配置，后者要排查调用方身份。</p>
 */
class SigningApprovalWhitelistFailClosedTest {

    private MpcApprovalPolicy mpcPolicy;
    private AuditLogService auditLog;

    @BeforeEach
    void setUp() {
        mpcPolicy = mock(MpcApprovalPolicy.class);
        when(mpcPolicy.getRequiredApprovers(any(), any())).thenReturn(1);
        auditLog = mock(AuditLogService.class);
    }

    @Test
    @DisplayName("白名单未配置 → 任何人都不许批，状态保持 PENDING")
    void emptyWhitelistDeniesEveryone() {
        SigningApprovalService service = serviceWithWhitelist("");

        String rid = createPendingRequest(service);
        authenticateAs("anyone@nexus");
        SigningApprovalRequest after = service.approve(rid, "10.0.0.1", null);

        assertEquals(SigningApprovalRequest.Status.PENDING, after.getStatus(),
                "缺配置必须等于没人能批，而不是谁都能批");
        assertEquals("approver_whitelist_not_configured", capturedDenialReason());
    }

    @Test
    @DisplayName("白名单为 null（字段未被注入）→ 同样拒绝")
    void nullWhitelistDenies() {
        SigningApprovalService service = new SigningApprovalService(mpcPolicy, auditLog,
                new MapApprovalStore(new ConcurrentHashMap<>()), null);

        String rid = createPendingRequest(service);
        authenticateAs("anyone@nexus");
        SigningApprovalRequest after = service.approve(rid, "10.0.0.1", null);

        assertEquals(SigningApprovalRequest.Status.PENDING, after.getStatus());
        assertEquals("approver_whitelist_not_configured", capturedDenialReason());
    }

    @Test
    @DisplayName("名单已配但审批人不在其中 → 拒绝，原因区别于'没配'")
    void listedWhitelistDeniesUnknownApprover() {
        SigningApprovalService service = serviceWithWhitelist("alice@nexus,bob@nexus");

        String rid = createPendingRequest(service);
        authenticateAs("mallory@nexus");
        SigningApprovalRequest after = service.approve(rid, "10.0.0.2", null);

        assertEquals(SigningApprovalRequest.Status.PENDING, after.getStatus());
        assertEquals("approver_not_in_whitelist", capturedDenialReason());
    }

    @Test
    @DisplayName("名单已配但调用方身份为空 → 拒绝，第三种原因")
    void blankActorWithConfiguredWhitelistDenies() {
        SigningApprovalService service = serviceWithWhitelist("alice@nexus");

        String rid = createPendingRequest(service);
        authenticateAs("   ");
        SigningApprovalRequest after = service.approve(rid, "10.0.0.3", null);

        assertEquals(SigningApprovalRequest.Status.PENDING, after.getStatus());
        assertEquals("approver_identity_missing", capturedDenialReason());
    }

    @Test
    @DisplayName("名单已配且身份在列 → 放行（证明这不是把审批整个锁死）")
    void listedApproverIsAllowed() {
        SigningApprovalService service = serviceWithWhitelist("alice@nexus, bob@nexus");

        String rid = createPendingRequest(service);
        authenticateAs("bob@nexus");
        SigningApprovalRequest after = service.approve(rid, "10.0.0.4", null);

        assertEquals(SigningApprovalRequest.Status.APPROVED, after.getStatus(),
                "逗号分隔项带空格也必须能匹配（解析要 trim）");
    }

    // ==================== 辅助 ====================

    private SigningApprovalService serviceWithWhitelist(String whitelist) {
        SigningApprovalService service = new SigningApprovalService(mpcPolicy, auditLog,
                new MapApprovalStore(new ConcurrentHashMap<>()), null);
        ReflectionTestUtils.setField(service, "approverWhitelist", whitelist);
        return service;
    }

    private String createPendingRequest(SigningApprovalService service) {
        String rid = service.createApprovalRequest("pkA", "pkB",
                new BigDecimal("50000"), "USDT", "init", "10.0.0.0");
        assertNotNull(rid);
        return rid;
    }

    /** 从审计里取最后一条 DENIED 事件的拒绝原因——运维真正能看到的就是这个字段。 */
    private String capturedDenialReason() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLog, org.mockito.Mockito.atLeastOnce()).log(captor.capture());
        AuditEvent denied = null;
        for (AuditEvent e : captor.getAllValues()) {
            if (e.getOutcome() == AuditEvent.Outcome.DENIED) {
                denied = e;
            }
        }
        assertNotNull(denied, "拒绝必须留下 DENIED 审计，否则运维无从知道审批被闸门挡住了");
        return String.valueOf(denied.getDetails().get("denied_reason"));
    }

    private static void authenticateAs(String principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthenticationContext() {
        SecurityContextHolder.clearContext();
    }
}
