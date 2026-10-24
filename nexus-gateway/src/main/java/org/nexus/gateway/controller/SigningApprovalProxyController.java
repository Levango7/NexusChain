package org.nexus.gateway.controller;

import feign.FeignException;
import feign.Response;
import org.nexus.gateway.client.feign.SigningApprovalFeignClient;
import org.nexus.gateway.dto.ApiResponse;
import org.nexus.gateway.dto.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 签名审批决策的网关转发端点（#50 收口）。
 *
 * <h2>为什么需要它</h2>
 * <p>signing-service 的决策端点（{@code POST /api/v1/signing/approvals/{id}/decision}）
 * 在集群内是 ClusterIP，且 mesh 侧 {@code gateway-to-signing} 只放行
 * {@code nexus-gateway-sa}。运维没有直达路径；本端点补上唯一入口。</p>
 *
 * <h2>身份如何传递：透传，不代签</h2>
 * <p>下游按 JWT 的 {@code sub} 判定"谁批的"，并按
 * {@code nexus.approval.approver-whitelist} 校验。本端点把调用者的
 * {@code Authorization} <b>原样</b>转发，网关不签新身份、不改写 subject、
 * 不把 ADMIN 翻译成下游角色——白名单和审计看到的始终是运维本人。
 * 因此调用者持有的 JWT 必须同时满足两侧：网关侧 {@code ADMIN}（本类的
 * {@code @PreAuthorize}），以及下游的 {@code OPERATOR}/{@code SIGNER} 且 subject
 * 在白名单内（否则下游 403，正文带 {@code denied_reason}）。</p>
 *
 * <p>离线签发满足上述两个条件的 token 属既有运维流程（ADR-2026-09-14：
 * {@code JwtTokenProvider.generateToken}，roles claim 逗号分隔），本 PR 不新增签发路径。</p>
 *
 * <h2>响应</h2>
 * <p>下游状态码与 JSON 正文逐字透传（200/400/403/404/409 的语义由下游定义）；
 * 仅当下游不可达或响应体读取失败时，网关返回 502 + {@code 50202}。
 * requestId 不符合 {@code [A-Za-z0-9_-]{1,64}} 的请求在本层回 400，不进入转发。</p>
 *
 * <p>本路径已在 {@code WebConfig} 中从 {@code ApiKeyInterceptor} 排除：运维只有 JWT、
 * 没有商户 API Key，不排除会被拦成 401（与风控审核端点的既有教训同型）。</p>
 */
@RestController
@RequestMapping("/api/v1/signing/approvals")
public class SigningApprovalProxyController {

    private static final Logger log = LoggerFactory.getLogger(SigningApprovalProxyController.class);

    /**
     * 审批请求 ID 的形态（{@code SigningApprovalRequest.create} 用
     * {@code UUID.randomUUID().toString()}）。网关是公网入口，requestId 会被拼进
     * 下游 URL 路径，故在此拒绝路径分隔符/点段等可改变目标路径的输入。
     */
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final SigningApprovalFeignClient signingApprovalClient;

    public SigningApprovalProxyController(SigningApprovalFeignClient signingApprovalClient) {
        this.signingApprovalClient = signingApprovalClient;
    }

    /**
     * 提交一次审批决策。
     *
     * @param requestId     审批请求 ID（大额签名被拦成 PENDING 时返回的 approvalId）
     * @param authorization 调用者 JWT，原样转发给 signing-service
     * @param body          决策请求体；缺省时按空对象转发，由下游判断 decision 是否合法
     * @return 下游响应（状态码 + 正文原样），或网关侧 403/502
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{requestId}/decision")
    public ResponseEntity<Object> decide(
            @PathVariable("requestId") String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body) {

        if (authorization == null || authorization.isBlank()) {
            // 方法安全已挡住匿名调用；这里兜的是"已认证但无可转发凭证"的形态，
            // 不能让 Feign 收到 null 头。
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(ErrorCode.ACCESS_DENIED.getCode(), "Missing bearer token"));
        }

        if (!REQUEST_ID_PATTERN.matcher(requestId).matches()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("Invalid requestId: " + requestId));
        }

        String operator = currentSubject();
        try {
            Response downstream = signingApprovalClient.decide(
                    requestId, authorization, body == null ? Map.of() : body);
            log.info("Forwarded signing approval decision: requestId={}, operator={}, downstreamStatus={}",
                    requestId, operator, downstream.status());
            return ResponseEntity.status(downstream.status())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(readBody(downstream));
        } catch (FeignException | IOException e) {
            log.error("Signing approval decision call failed: requestId={}, operator={}",
                    requestId, operator, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.error(ErrorCode.SIGNING_SERVICE_UNAVAILABLE.getCode(),
                            ErrorCode.SIGNING_SERVICE_UNAVAILABLE.getMessage()));
        }
    }

    private static String readBody(Response response) throws IOException {
        if (response.body() == null) {
            return "";
        }
        try (InputStream in = response.body().asInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : String.valueOf(authentication.getPrincipal());
    }
}
