package org.nexus.signing.controller;

import org.nexus.signing.business.BusinessApprovalService;
import org.nexus.signing.config.SecurityRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

/**
 * A2 业务批准凭据登记端点（ADR-036 §2.2，2026-10-28 立项实施）。
 *
 * <p>{@code POST /api/v1/signing/business-approvals}：wallet-service 在提现单
 * 业务审批（APPROVED）后、调用 {@code /api/v1/transfers/sign?businessApprovalId=...}
 * 之前登记。登记是纯 DB 写，不触发资金动作；签名腿据此跳过 signing 自身的大额审批。</p>
 *
 * <h2>路径为什么选 {@code /api/v1/signing/**}</h2>
 * <p>与 #50 决策端点同款约束：mesh 侧 {@code deploy/istio/authorization-policies.yaml}
 * 的 {@code gateway-to-signing} 规则只放行 {@code paths = /api/v1/signing/*}，
 * 本端点落在前缀内，无需改部署资产。</p>
 *
 * <h2>鉴权</h2>
 * <p>{@code ROLE_SIGNER}——与 {@code /api/v1/transfers/sign} 同级：登记的调用方
 * 就是随后发起签名的同一服务（wallet-service），其服务间 JWT 已含 SIGNER
 * （或被 {@code JwtAuthenticationFilter#normalizeLegacyRole} 归一化的等价角色）。
 * 来源可信度与签名端点同级（ADR-036 §3.1：本契约防载荷篡改，不防来源伪造）。</p>
 *
 * <h2>响应口径</h2>
 * <ul>
 *   <li>2000（HTTP 200）：登记成功 / 幂等命中，{@code data={businessApprovalId, state, idempotent}}；</li>
 *   <li>5002（HTTP 409）：载荷哈希冲突——同单号异哈希，或 body 字段重算哈希与提交值不符；</li>
 *   <li>5004（HTTP 400）：参数非法（缺字段 / 哈希格式错 / 金额非正 / expiresAt 不可解析）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/signing/business-approvals")
public class BusinessApprovalController {

    private static final Logger log = LoggerFactory.getLogger(BusinessApprovalController.class);

    private final BusinessApprovalService businessApprovalService;

    public BusinessApprovalController(BusinessApprovalService businessApprovalService) {
        this.businessApprovalService = businessApprovalService;
    }

    /**
     * 登记（幂等）一笔业务批准凭据。参数经 form 编码（与 /transfers/sign 同款）。
     */
    @PreAuthorize("hasRole('" + SecurityRoles.SIGNER + "')")
    @PostMapping(consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<Map<String, Object>> register(
            @RequestParam("businessApprovalId") String businessApprovalId,
            @RequestParam("payloadHash") String payloadHash,
            @RequestParam("amount") BigDecimal amount,
            @RequestParam(value = "currency", required = false, defaultValue = "USDT") String currency,
            @RequestParam("fromPubkey") String fromPubkey,
            @RequestParam("toPubkeyHash") String toPubkeyHash,
            @RequestParam("expiresAt") String expiresAt,
            @RequestParam(value = "approvers", required = false) String approversCsv) {

        Instant expiresInstant;
        try {
            expiresInstant = Instant.parse(expiresAt);
        } catch (DateTimeParseException | NullPointerException e) {
            return invalid("expiresAt must be an ISO-8601 instant, e.g. 2026-10-28T16:00:00Z");
        }

        BusinessApprovalService.Registration registration;
        try {
            registration = new BusinessApprovalService.Registration(
                    businessApprovalId, payloadHash, amount, currency,
                    fromPubkey, toPubkeyHash, expiresInstant, normalizeApprovers(approversCsv));
            Map<String, Object> data = businessApprovalService.register(registration);
            Map<String, Object> body = new HashMap<>();
            body.put("statusCode", 2000);
            body.put("data", data);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return invalid(e.getMessage());
        } catch (BusinessApprovalService.PayloadHashConflictException e) {
            log.warn("A2 凭据登记哈希冲突: businessApprovalId={}, error={}", businessApprovalId, e.getMessage());
            Map<String, Object> body = new HashMap<>();
            body.put("statusCode", 5002);
            body.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
    }

    private ResponseEntity<Map<String, Object>> invalid(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("statusCode", 5004);
        body.put("message", message == null ? "invalid registration" : message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 审批人快照：CSV → JSON 数组文本（仅审计展示，不参与校验）。 */
    private String normalizeApprovers(String approversCsv) {
        if (approversCsv == null || approversCsv.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        String[] parts = approversCsv.split(",");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(p.replace("\"", "'")).append('"');
        }
        return sb.append(']').toString();
    }
}
