package org.nexus.signing.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.nexus.signing.approval.SigningApprovalRequest;
import org.nexus.signing.approval.SigningApprovalService;
import org.nexus.signing.audit.AuditLogService;
import org.nexus.signing.config.SecurityRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 多签审批决策的 HTTP 入口（#50，2026-10-24 新增）。
 *
 * <h2>为什么需要它</h2>
 * <p>{@link SigningApprovalService#approve} 与 {@link SigningApprovalService#reject} 在
 * 本端点之前<b>没有任何生产调用方</b>：{@code TxController} 只读请求（{@code getRequest}）、
 * 创建请求（{@code createApprovalRequest}）并标记执行，全仓 main 源码里找不到一处
 * approve/reject 调用。后果不是"少一个功能"，而是大额签名这条路整条走不通——
 * 大额请求被 {@code /api/v1/transfers/sign} 拦成 PENDING 后，
 * {@code /api/v1/transfers/sign/approved} 的前置判据是 {@code status == APPROVED}，
 * 而没有任何输入能把状态推到 APPROVED。{@code nexus.approval.approver-whitelist} 与
 * 多签 {@code requiredApprovers} 因此都是空转配置。</p>
 *
 * <h2>路径为什么选 {@code /api/v1/signing/**}</h2>
 * <p>signing-service 是 ClusterIP，且 mesh 侧 {@code deploy/istio/authorization-policies.yaml}
 * 的 {@code gateway-to-signing} 规则只放行 {@code principal = nexus-gateway-sa} 且
 * {@code paths = /api/v1/signing/*}。本端点落在该前缀内，所以网关（唯一被允许调用本服务的
 * 主体）可达，不需要改动部署资产。</p>
 *
 * <h2>审批人身份：只认 JWT subject，且请求体里根本没有这个字段</h2>
 * <p>身份取用点在服务内部：{@link SigningApprovalService} 的 {@code approve}/{@code reject}
 * <b>不接收审批人参数</b>，而是从 {@code SecurityContextHolder} 读 JWT 的 {@code sub}
 * （协调板 {@code 20261017-2015-nex-adjudication-50-approver-identity.md} 裁定第 2 条）。
 * 这样"谁批的"由调用方自填在类型层面就凑不出来；请求体里的 {@code approver}
 * 一类字段会被 Jackson 直接忽略。</p>
 *
 * <p>白名单判定与审计也都在服务内完成，端点只复述同一个判定函数的结论，
 * 因此不存在"绕过端点就能批"的第二道口子。</p>
 *
 * <p>服务间 token（gateway {@code FeignJwtRequestInterceptor} 签发的
 * SIGNER+OPERATOR+READ）同样能过 {@code @PreAuthorize}，但它的 subject 是服务身份而非
 * 运维身份，不会出现在 {@code approver-whitelist} 里 ⇒ 被
 * {@code approver_not_in_whitelist} 拒。白名单是 fail-closed 的（#129），
 * 没配就等于没人能批，所以"能过角色"不等于"能下决策"。</p>
 *
 * <h2>响应口径</h2>
 * <ul>
 *   <li>200：决策已记账，{@code data.status} 为最新状态（APPROVE 且法定数未满时仍是 PENDING）；</li>
 *   <li>400：{@code decision} 缺失或不是 APPROVE/REJECT（由
 *       {@link GlobalExceptionHandler} 把 {@code IllegalArgumentException} 映射过来）；</li>
 *   <li>403：审批人无权下决策，{@code denied_reason} 给出三种可区分原因之一
 *       （{@code approver_whitelist_not_configured} / {@code approver_identity_missing}
 *       / {@code approver_not_in_whitelist}）；</li>
 *   <li>404：requestId 不存在或已被清理；</li>
 *   <li>409：请求已不是 PENDING（已批准/已驳回/已过期），决策被忽略、状态不变。</li>
 * </ul>
 *
 * <h2>上层入口</h2>
 * <p>网关侧已提供同路径的转发端点（{@code SigningApprovalProxyController}）：
 * 本地要求 {@code ROLE_ADMIN}，并把调用者自己的 {@code Authorization} 原样透传——
 * 网关不代签身份，所以本服务看到的 {@code sub} 仍是运维本人，白名单与审计语义不变。</p>
 */
@RestController
@RequestMapping("/api/v1/signing/approvals")
public class SigningApprovalController {

    private static final Logger log = LoggerFactory.getLogger(SigningApprovalController.class);

    /** 业务成功码（与 {@code MpcColdWalletController}/{@code TxController} 既有约定一致）。 */
    private static final int BIZ_OK = 2000;
    private static final int BIZ_FORBIDDEN = 4030;
    private static final int BIZ_NOT_FOUND = 4040;
    private static final int BIZ_CONFLICT = 4090;

    private final SigningApprovalService approvalService;
    private final AuditLogService auditLogService;

    public SigningApprovalController(SigningApprovalService approvalService,
                                     AuditLogService auditLogService) {
        this.approvalService = approvalService;
        this.auditLogService = auditLogService;
    }

    /**
     * 审批决策请求体。
     *
     * <p>{@code decision} 只接受 {@code APPROVE} / {@code REJECT}（大小写不敏感）。
     * {@code comment} 进审计明细，不落审批请求本体——那需要新增列，属 #52-B 已否决的范围。</p>
     */
    public record DecisionRequest(String decision, String comment) {
    }

    /**
     * 提交一次审批决策（通过或驳回）。
     *
     * @param requestId  审批请求 ID（{@code /api/v1/transfers/sign} 大额分支返回的 approvalId）
     * @param body       决策请求体
     * @param httpRequest 用于提取来源 IP（仅可信代理才采信 X-Forwarded-For）
     * @return 决策后的请求快照，或对应语义的错误响应
     */
    @PreAuthorize("hasAnyRole('" + SecurityRoles.SIGNER + "','" + SecurityRoles.OPERATOR + "')")
    @PostMapping("/{requestId}/decision")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable String requestId,
                                                      @RequestBody(required = false) DecisionRequest body,
                                                      HttpServletRequest httpRequest) {
        boolean approve = parseDecision(body);
        String comment = body == null ? null : body.comment();
        String sourceIp = auditLogService.extractClientIp(httpRequest);

        SigningApprovalRequest before = approvalService.getRequest(requestId);
        if (before == null) {
            return body(HttpStatus.NOT_FOUND, BIZ_NOT_FOUND,
                    "Approval request not found: " + requestId, null);
        }
        if (before.getStatus() != SigningApprovalRequest.Status.PENDING) {
            return body(HttpStatus.CONFLICT, BIZ_CONFLICT,
                    "审批请求已不在待审状态，决策被忽略: " + before.getStatus(),
                    snapshot(before, approve));
        }

        SigningApprovalRequest after = approve
                ? approvalService.approve(requestId, sourceIp, comment)
                : approvalService.reject(requestId, sourceIp, comment);
        if (after == null) {
            return body(HttpStatus.NOT_FOUND, BIZ_NOT_FOUND,
                    "Approval request not found: " + requestId, null);
        }

        String denialReason = approvalService.currentApproverDenialReason();
        if (denialReason != null) {
            log.warn("审批决策被拒: requestId={}, reason={}", requestId, denialReason);
            Map<String, Object> data = snapshot(after, approve);
            data.put("denied_reason", denialReason);
            return body(HttpStatus.FORBIDDEN, BIZ_FORBIDDEN,
                    "Approver is not allowed to decide: " + denialReason, data);
        }

        return body(HttpStatus.OK, BIZ_OK, "ok", snapshot(after, approve));
    }

    /**
     * 查询审批请求状态（只读，不改变状态）。
     *
     * <p>调用方在 {@code POST /api/v1/transfers/sign} 收到
     * {@code statusCode=2001}（PENDING）后，需要凭 approvalId 判断"是否已批准"，
     * 再决定是否调用 {@code POST /api/v1/transfers/sign/approved} 执行签名。
     * 没有本端点时，调用方只能反复撞执行端点（未批准时它回 5000），无法区分
     * PENDING / REJECTED / EXPIRED，也无法安全地做轮询。</p>
     *
     * <p>服务间 token（SIGNING_SERVICE→SIGNER 归一化）与运维 token（OPERATOR）都可查询；
     * 只返回人数与时间，不回显具体审批人地址。</p>
     *
     * @param requestId 审批请求 ID
     * @return 状态快照；不存在时 404
     */
    @PreAuthorize("hasAnyRole('" + SecurityRoles.SIGNER + "','" + SecurityRoles.OPERATOR + "')")
    @GetMapping("/{requestId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable("requestId") String requestId) {
        SigningApprovalRequest request = approvalService.getRequest(requestId);
        if (request == null) {
            return body(HttpStatus.NOT_FOUND, BIZ_NOT_FOUND,
                    "Approval request not found: " + requestId, null);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", request.getRequestId());
        data.put("status", request.getStatus().name());
        data.put("expired", request.isExpired());
        data.put("amount", request.getAmount());
        data.put("currency", request.getCurrency());
        data.put("initiator", request.getInitiator());
        data.put("required_approvers", request.getRequiredApprovers());
        data.put("approvals_count", request.getApprovals().size());
        data.put("rejections_count", request.getRejections().size());
        data.put("created_at", request.getCreatedAt().toString());
        data.put("deadline", request.getDeadline().toString());
        return body(HttpStatus.OK, BIZ_OK, "ok", data);
    }

    /** 解析 {@code decision}；缺失或非法一律按 400 处理（交给 {@link GlobalExceptionHandler}）。 */
    private static boolean parseDecision(DecisionRequest body) {
        String raw = body == null ? null : body.decision();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("decision is required (APPROVE|REJECT)");
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "APPROVE" -> true;
            case "REJECT" -> false;
            default -> throw new IllegalArgumentException(
                    "decision must be APPROVE or REJECT, got: " + raw);
        };
    }

    private static Map<String, Object> snapshot(SigningApprovalRequest request, boolean approve) {
        Map<String, Object> data = new HashMap<>();
        data.put("requestId", request.getRequestId());
        data.put("status", request.getStatus().name());
        data.put("decision", approve ? "APPROVE" : "REJECT");
        data.put("approvals_count", request.getApprovals().size());
        data.put("rejections_count", request.getRejections().size());
        data.put("required_approvers", request.getRequiredApprovers());
        return data;
    }

    /** 与 {@code GlobalExceptionHandler} 同一信封：{@code {statusCode, message, data}}。 */
    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, int bizCode,
                                                            String message, Map<String, Object> data) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("statusCode", bizCode);
        payload.put("message", message);
        payload.put("data", data == null ? "" : data);
        return ResponseEntity.status(status).body(payload);
    }
}
