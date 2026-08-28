package org.nexus.signing.controller;

import org.nexus.sdk.wallet.TxUtils;
import org.nexus.sdk.wallet.WalletUtils;
import org.nexus.common.tracing.BusinessSpan;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonObject;
import org.nexus.sdk.common.APIResult;
import org.nexus.signing.pool.NoncePool;
import org.nexus.signing.pool.NonceState;
import org.nexus.signing.keystore.PlatformKeystore;
import org.nexus.signing.config.SecurityRoles;
import org.nexus.signing.audit.AuditEvent;
import org.nexus.signing.audit.AuditLogService;
import org.nexus.signing.approval.SigningApprovalService;
import org.nexus.signing.approval.SigningApprovalRequest;
import org.nexus.sdk.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 签名服务交易控制器。
 *
 * <p>从 {@code org.nexus.wallet.signing.controller.TxController}（exchange-wallet）
 * 迁入 signing-service，包路径变更为 {@code org.nexus.signing.controller}。</p>
 *
 * <p>P3-T5：在签名 + 广播链路添加业务 span（signing.broadcast），
 * span 树结构见 docs/tracing-business-span.md。</p>
 *
 * <p>提供链上转账签名 + 广播 REST 端点：
 * <ul>
 *   <li>{@code POST /ClientToTransferAccount}：legacy 签名广播端点</li>
 *   <li>{@code POST /api/v1/transfers/sign}：合约化签名广播端点</li>
 *   <li>{@code GET /getNoncePool}：查询 Nonce 池</li>
 * </ul></p>
 */
@RestController
public class TxController {

    @Autowired
    NoncePool noncePool;

    @Autowired
    NodeController nodeController;

    @Autowired
    PlatformKeystore platformKeystore;

    /** Micrometer Tracer：P3-T5 业务 span 注入。可为 null（测试环境降级 no-op）。 */
    @Autowired(required = false)
    Tracer tracer;

    /**
     * P2-F1：私钥操作审计日志服务。可为 null（测试环境关闭审计）。
     */
    @Autowired(required = false)
    AuditLogService auditLogService;

    /**
     * P2-F1：多签审批服务。可为 null（测试环境关闭审批）。
     */
    @Autowired(required = false)
    SigningApprovalService signingApprovalService;

    /**
     * LEGACY transfer endpoint, kept for backward compatibility with existing
     * form-POST clients.
     *
     * <p>SECURITY (P1 fix): the {@code prikey} request parameter has been
     * REMOVED — caller-supplied plaintext private keys are never accepted.
     * Signing is performed exclusively with the server-side
     * {@link PlatformKeystore}, and {@code fromPubkey} must match the platform
     * keystore public key. Any extra {@code prikey} form field sent by legacy
     * clients is ignored, and requests whose {@code fromPubkey} does not match
     * the platform key are rejected. New clients should use
     * {@code /api/v1/transfers/sign}.</p>
     *
     * <p>SECURITY (P2-F1): 端点强制 {@code ROLE_SIGNER} 鉴权，
     * 仅允许持有合法 JWT 且 roles 含 {@code SIGNER} 的调用方
     * （即 gateway 通过 Feign 注入服务间 token）访问。
     * P1-F1 阶段使用的 {@code SIGNING_SERVICE} 角色由
     * {@link org.nexus.signing.config.JwtAuthenticationFilter#normalizeLegacyRole}
     * 归一化为 {@code SIGNER}，保持向后兼容。</p>
     */
    @PreAuthorize("hasRole('" + SecurityRoles.SIGNER + "')")
    @RequestMapping(value="/ClientToTransferAccount",method = RequestMethod.POST )
    public Object ClientToTransferAccount(@RequestParam(value = "fromPubkey", required = true) String fromPubkey,
                                          @RequestParam(value = "toPubkeyHash", required = true) String toPubkeyHash,
                                          @RequestParam(value = "amount", required = true) BigDecimal amount,
                                          HttpServletRequest request
                                          ) throws IOException {
        return signAndBroadcast(fromPubkey, toPubkeyHash, amount, request);
    }

    /**
     * Sign + broadcast a transfer using the SERVER-SIDE platform keystore, so
     * the caller (e.g. the gateway) never transmits a private key. This is the
     * endpoint the gateway delegates to (ExchangeWalletClient.signTransfer).
     *
     * <p>SECURITY (P1 fix): the former {@code keystoreJson}/{@code password}
     * override has been REMOVED — caller-supplied keystore material is never
     * accepted over HTTP. Signing uses the platform keystore exclusively, and
     * {@code fromPubkey} must match the platform keystore public key.</p>
     *
     * <p>SECURITY (P2-F1): 端点强制 {@code ROLE_SIGNER} 鉴权。
     * gateway 通过 {@code FeignJwtRequestInterceptor} 在 Feign 调用前
     * 注入 {@code Authorization: Bearer <jwt>}，token 由共享
     * {@code JWT_SECRET} 签发，roles 含 {@code SIGNER}。
     * P1-F1 阶段使用的 {@code SIGNING_SERVICE} 角色由
     * {@link org.nexus.signing.config.JwtAuthenticationFilter#normalizeLegacyRole}
     * 归一化为 {@code SIGNER}，保持向后兼容。</p>
     */
    @PreAuthorize("hasRole('" + SecurityRoles.SIGNER + "')")
    @RequestMapping(value="/api/v1/transfers/sign", method = RequestMethod.POST )
    public Object signTransfer(@RequestParam(value = "fromPubkey", required = true) String fromPubkey,
                               @RequestParam(value = "toPubkeyHash", required = true) String toPubkeyHash,
                               @RequestParam(value = "amount", required = true) BigDecimal amount,
                               HttpServletRequest request
    ) throws IOException {
        return signAndBroadcast(fromPubkey, toPubkeyHash, amount, request);
    }

    /**
     * P0-2：审批通过后执行签名 + 广播。
     *
     * <p>大额签名流程改为阻断式后，{@code /api/v1/transfers/sign} 对大额请求
     * 仅创建审批请求并返回 PENDING 响应。审批人通过
     * {@link SigningApprovalService#approve} 收集足够审批后，调用方凭
     * {@code approvalId} 调用本端点触发实际签名 + 广播。</p>
     *
     * <p>处理流程：
     * <ol>
     *   <li>通过 {@code approvalId} 查询审批请求</li>
     *   <li>校验审批状态为 {@code APPROVED}（未通过 / 不存在 / 已过期 / 已执行
     *       均返回错误）</li>
     *   <li>复用 {@link #signAndBroadcast} 执行签名 + 广播</li>
     *   <li>签名成功后调用 {@link SigningApprovalService#markExecuted}
     *       将审批请求标记为 {@code EXECUTED}</li>
     * </ol></p>
     *
     * <p>SECURITY：端点强制 {@code ROLE_SIGNER} 鉴权，与
     * {@code /api/v1/transfers/sign} 一致。审批决策（approve/reject）由
     * wallet-service 的 APPROVER 角色通过独立流程完成，本端点仅消费
     * 已通过的审批结果。</p>
     *
     * @param approvalId 审批请求 ID（由 {@code /api/v1/transfers/sign} 大额流程返回）
     * @param request    HTTP 请求（用于提取来源 IP 等审计信息）
     * @return 签名 + 广播结果（与 {@code /api/v1/transfers/sign} 成功时一致），
     *         或审批状态错误响应
     */
    @PreAuthorize("hasRole('" + SecurityRoles.SIGNER + "')")
    @RequestMapping(value = "/api/v1/transfers/sign/approved", method = RequestMethod.POST)
    public Object signTransferApproved(@RequestParam(value = "approvalId", required = true) String approvalId,
                                       HttpServletRequest request) throws IOException {
        // 校验审批服务可用
        if (signingApprovalService == null) {
            return fail("Approval service is not available");
        }
        // 查询审批请求
        SigningApprovalRequest approvalRequest = signingApprovalService.getRequest(approvalId);
        if (approvalRequest == null) {
            return fail("Approval request not found: " + approvalId);
        }
        // 校验审批状态为 APPROVED
        if (approvalRequest.getStatus() != SigningApprovalRequest.Status.APPROVED) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("statusCode", 5000);
            resp.put("status", approvalRequest.getStatus().name());
            resp.put("approvalId", approvalId);
            resp.put("message", "审批未通过，无法执行签名; 当前状态: "
                    + approvalRequest.getStatus().name());
            return resp;
        }
        // P1-8 修复（v2.27.0）：CAS 原子审批——在执行签名前将审批请求从 APPROVED 标记为 EXECUTING。
        // 防止两个并发调用同时通过 APPROVED 检查并重复执行签名+广播（双重放款）。
        if (!signingApprovalService.tryMarkExecuting(approvalId)) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("statusCode", 5001);
            resp.put("approvalId", approvalId);
            resp.put("message", "审批请求状态已变更（可能已被另一请求执行），请勿重复调用");
            return resp;
        }
        // 复用签名 + 广播流程（审批已通过，金额必然为大额，signAndBroadcast 内
        // 会再次触发 requiresApproval 检查并创建新的审批请求——为避免无限循环，
        // 此处直接调用 doSignAndBroadcast 绕过审批创建逻辑）。
        String actor = AuditLogService.resolveActor("anonymous");
        String sourceIp = auditLogService != null
                ? auditLogService.extractClientIp(request)
                : request.getRemoteAddr();
        Object result;
        try {
            result = doSignAndBroadcast(
                    approvalRequest.getFromPubkey(),
                    approvalRequest.getToPubkeyHash(),
                    approvalRequest.getAmount(),
                    request,
                    actor,
                    sourceIp);
        } catch (Exception e) {
            // P1-8 修复：签名执行失败时回退审批状态到 APPROVED，允许后续重试
            signingApprovalService.revertExecuting(approvalId);
            throw e;
        }
        // P1-8 修复（v2.27.0）：签名成功后将审批请求标记为 EXECUTED。
        // 标记失败不再静默吞异常——记录 ERROR 级别审计日志，便于运维排查。
        // 签名已广播不可逆，但审批状态不一致需人工介入。
        try {
            signingApprovalService.markExecuted(approvalId);
        } catch (Exception e) {
            // 标记失败不影响签名结果已返回，但以 ERROR 级别记录审计日志便于运维排查
            if (auditLogService != null) {
                auditLogService.log(AuditEvent.builder(AuditEvent.Type.APPROVAL_REQUEST,
                                AuditEvent.Outcome.FAILURE,
                                AuditLogService.resolveActor("anonymous"))
                        .target(approvalId)
                        .detail("reason", "mark_executed_failed")
                        .detail("error", e.getMessage())
                        .build());
            }
            // P1-8 修复：以 ERROR 级别记录（原实现仅 WARN 级别，易被忽略）
            throw new IllegalStateException("签名已广播但审批状态标记失败，需人工排查: "
                    + "approvalId=" + approvalId + ", error=" + e.getMessage(), e);
        }
        return result;
    }

    /**
     * Shared signing pipeline: platform-key-only. Rejects the request unless
     * the platform keystore is loaded and {@code fromPubkey} matches the
     * platform keystore public key. No caller-supplied private key material is
     * ever used.
     *
     * <p>P2-F1：集成审计日志与多签审批。
     * <ul>
     *   <li>签名成功 / 失败均记录审计日志（{@link AuditLogService}），
     *       包含 who（JWT subject）、what（txHash）、when（时间戳）、
     *       where（来源 IP）</li>
     *   <li>大额签名（金额 ≥ nexus.approval.large-amount-threshold）触发
     *       多签审批流程（{@link SigningApprovalService#createApprovalRequest}），
     *       创建审批请求并返回 PENDING 响应，<b>阻断签名执行</b>，
     *       调用方需等待审批通过后通过
     *       {@code POST /api/v1/transfers/sign/approved} 触发实际签名</li>
     * </ul></p>
     */
    private Object signAndBroadcast(String fromPubkey, String toPubkeyHash,
                                    BigDecimal amount, HttpServletRequest request) throws IOException {
        // P2-F1：提取调用方信息用于审计
        String actor = AuditLogService.resolveActor("anonymous");
        // 中9：使用实例方法 extractClientIp，仅信任可信代理的 X-Forwarded-For。
        // auditLogService 为 null（测试环境）时回退到 RemoteAddr（最安全，不信任 XFF）。
        String sourceIp = auditLogService != null
                ? auditLogService.extractClientIp(request)
                : request.getRemoteAddr();

        // P0-2：大额签名触发多签审批（阻断式：创建审批请求后返回 PENDING，不执行签名）
        if (signingApprovalService != null
                && signingApprovalService.requiresApproval(amount, "USDT")) {
            String approvalId = signingApprovalService.createApprovalRequest(
                    fromPubkey, toPubkeyHash, amount, "USDT", actor, sourceIp);
            // 阻断式审批已实现：创建审批请求后立即返回 PENDING 响应，不继续执行签名。
            // 调用方需通过 POST /api/v1/transfers/sign/approved 端点在审批通过后触发签名。
            if (approvalId != null) {
                return pendingApprovalResponse(approvalId);
            }
            // approvalId 为 null 时（理论上不应发生，因为 requiresApproval 已返回 true），
            // 保守起见继续执行签名流程，并记录告警便于排查。
            if (auditLogService != null) {
                auditLogService.log(AuditEvent.builder(AuditEvent.Type.SIGN_TRANSFER,
                                AuditEvent.Outcome.FAILURE, actor)
                        .sourceIp(sourceIp)
                        .detail("reason", "approval_request_creation_returned_null")
                        .detail("amount", amount == null ? null : amount.toPlainString())
                        .detail("currency", "USDT")
                        .build());
            }
        }

        // 无需审批或审批服务不可用：直接执行签名 + 广播
        return doSignAndBroadcast(fromPubkey, toPubkeyHash, amount, request, actor, sourceIp);
    }

    /**
     * 实际签名 + 广播执行（不含审批检查）。
     *
     * <p>P0-2：从 {@link #signAndBroadcast} 抽取，供
     * {@link #signTransferApproved} 在审批通过后直接调用，绕过审批创建逻辑
     * 避免无限循环（已通过审批的大额请求再次进入 signAndBroadcast 会创建
     * 新的审批请求）。</p>
     *
     * <p>本方法包含 platform-key-only 校验、Nonce 池管理、交易构造 + 广播、
     * 审计日志、业务 span 等完整签名逻辑，与原 signAndBroadcast 的签名部分
     * 逐字节一致。</p>
     *
     * @param fromPubkey   转出公钥
     * @param toPubkeyHash 转入公钥 hash
     * @param amount       金额
     * @param request      HTTP 请求
     * @param actor        调用方标识（JWT subject，用于审计）
     * @param sourceIp     来源 IP（用于审计）
     * @return 签名 + 广播结果
     */
    private Object doSignAndBroadcast(String fromPubkey, String toPubkeyHash,
                                      BigDecimal amount, HttpServletRequest request,
                                      String actor, String sourceIp) throws IOException {
        // P3-T5：签名 + 广播 span（signing.broadcast）
        try (BusinessSpan span = BusinessSpan.start(tracer, "signing.broadcast")
                .attr("signing.from.pubkey", fromPubkey)
                .attr("signing.to.pubkey.hash", toPubkeyHash)
                .attr("signing.amount", amount)) {
            String prikey = platformKeystore == null ? null : platformKeystore.getPrikey();
            if (prikey == null || prikey.isBlank()) {
                span.attr("signing.error", "no_signing_key").error(null);
                auditSignFailure(actor, sourceIp, "no_signing_key", amount);
                return fail("No signing key available: wallet.keystore.json is not configured");
            }
            String platformPubkey = platformKeystore.getPubkey();
            if (platformPubkey == null || platformPubkey.isBlank()
                    || !platformPubkey.equalsIgnoreCase(fromPubkey)) {
                span.attr("signing.error", "pubkey_mismatch").error(null);
                auditSignFailure(actor, sourceIp, "pubkey_mismatch", amount);
                return fail("fromPubkey does not match the platform keystore public key; "
                        + "caller-supplied private keys are no longer accepted");
            }

            long nownonce=0;
            String frompubhash=WalletUtils.pubkeyStrToPubkeyHashStr(fromPubkey);
            String address=WalletUtils.pubkeyHashToAddress(frompubhash);
            if(WalletUtils.verifyAddress(address)!=0){
                span.attr("signing.error", "address_invalid").error(null);
                auditSignFailure(actor, sourceIp, "address_invalid", amount);
                return fail("Address Error");
            }
            span.attr("signing.from.address", address);
            long maxnonce=noncePool.getMaxNonce(address);
            if(maxnonce==0){
                //rpc获取nonce
                JsonObject getnonoce=nodeController.getNonce(frompubhash);
                int Code= getnonoce != null && getnonoce.has("code") ? getnonoce.get("code").getAsInt() : 0;
                if(Code==5000){
                    span.attr("signing.error", "nonce_fetch_failed").error(null);
                    auditSignFailure(actor, sourceIp, "nonce_fetch_failed", amount);
                    return fail("Error");
                }
                long dbnonce= getnonoce != null && getnonoce.has("data") ? getnonoce.get("data").getAsLong() : 0;
                nownonce=dbnonce;
            }else{
                nownonce=maxnonce;
            }
            span.attr("signing.nonce", nownonce);
            ObjectNode data = TxUtils.ClientToTransferAccount(fromPubkey,toPubkeyHash,amount,prikey,nownonce);
            if (data == null || data.isEmpty() || !data.has("data")){
                span.attr("signing.error", "tx_build_failed").error(null);
                auditSignFailure(actor, sourceIp, "tx_build_failed", amount);
                return fail("Error");
            }else {
                // 直接返回 ObjectNode（Jackson 原生序列化），不再经 Gson 反射转 HashMap——
                // 旧实现会把 ObjectNode 序列化成 _children/_nodeFactory 内部字段，
                // 导致响应丢失 data（交易哈希）且 noncePool 记录空哈希。
                String texhash = data.get("data").asText();
                data.put("statusCode", 2000);
                nownonce++;
                NonceState nonceState=new NonceState(texhash,nownonce,new Date().getTime());
                noncePool.add(address,nonceState);
                span.attr("signing.tx.hash", texhash).success();
                // P2-F1：记录签名成功审计日志（target 为 txHash，不含私钥/签名内容）
                auditSignSuccess(actor, sourceIp, texhash, amount);
                // Spring Boot 4.0 升级兼容：TxUtils.ClientToTransferAccount 返回的是 Jackson 2
                // (com.fasterxml.jackson) 的 ObjectNode，而 Spring Boot 4.0 默认 HTTP 序列化器
                // 已切换为 Jackson 3 (tools.jackson)。Jackson 3 不识别 Jackson 2 的 ObjectNode，
                // 会按 JavaBean 反射序列化其内部属性（array/nodeType/containerNode...），
                // 导致响应体丢失业务字段（statusCode/data/message），前端 JSON 路径断言失败。
                // 转为 Map 后 Jackson 3 可按普通 Map 正常序列化，响应 JSON 结构保持不变。
                return toResponseMap(data);
            }
        }
    }

    /**
     * Spring Boot 4.0 / Jackson 3 兼容：将 Jackson 2 的 {@link ObjectNode} 转为
     * {@link Map}，供 Spring MVC 的 Jackson 3 HTTP 序列化器正确输出业务字段。
     *
     * <p>背景：{@code TxUtils.ClientToTransferAccount} 返回 Jackson 2
     * ({@code com.fasterxml.jackson.databind.node.ObjectNode}) 的 JSON 树，
     * 内含 {@code statusCode}/{@code data}/{@code message} 等业务字段。
     * Spring Boot 4.0 默认使用 Jackson 3
     * ({@code tools.jackson.databind}) 作为 HttpMessageConverter，
     * Jackson 3 不识别 Jackson 2 的 {@code ObjectNode}（非其 {@code JsonNode} 子类型），
     * 退化为 JavaBean 反射序列化，输出 {@code isArray()}/{@code getNodeType()} 等
     * 内部属性，业务字段全部丢失。</p>
     *
     * <p>实现：{@code ObjectNode.toString()} 返回 Jackson 2 规范 JSON 字符串
     * （含 statusCode/data/message），用共享 {@link JsonUtil#GSON} 解析为
     * {@code HashMap}，Jackson 3 可按普通 Map 正常序列化。与 {@link #fail}
     * 的 Gson 转 HashMap 模式一致，不引入新依赖。</p>
     *
     * @param data Jackson 2 ObjectNode（含 statusCode/data/message 业务字段）
     * @return 等价的 HashMap，供 Jackson 3 HTTP 序列化器正确输出
     */
    private Map<String, Object> toResponseMap(ObjectNode data) {
        return JsonUtil.GSON.fromJson(data.toString(), HashMap.class);
    }

    /**
     * P2-F1：记录签名成功审计日志。
     */
    private void auditSignSuccess(String actor, String sourceIp, String txHash, BigDecimal amount) {
        if (auditLogService == null) {
            return;
        }
        auditLogService.logSignTransfer(AuditEvent.Outcome.SUCCESS, actor, sourceIp,
                txHash, amount, "USDT");
    }

    /**
     * P2-F1：记录签名失败审计日志。
     */
    private void auditSignFailure(String actor, String sourceIp, String reason, BigDecimal amount) {
        if (auditLogService == null) {
            return;
        }
        auditLogService.log(AuditEvent.builder(AuditEvent.Type.SIGN_TRANSFER,
                        AuditEvent.Outcome.FAILURE, actor)
                .sourceIp(sourceIp)
                .target(null)
                .detail("reason", reason)
                .detail("amount", amount == null ? null : amount.toPlainString())
                .detail("currency", "USDT")
                .build());
    }

    /** Build a 5000-status error payload (same shape as the legacy API). */
    private Object fail(String message) {
        APIResult result = new APIResult();
        result.setStatusCode(5000);
        result.setMessage(message);
        return JsonUtil.GSON.fromJson(JsonUtil.GSON.toJson(result), HashMap.class);
    }

    /**
     * P0-2：构造大额签名「待审批」响应（PENDING 状态）。
     *
     * <p>当大额签名触发多签审批时，signAndBroadcast 创建审批请求后立即返回
     * 本响应，不执行签名。响应体包含：
     * <ul>
     *   <li>{@code statusCode}：2001（区别于 2000 成功与 5000 失败，表示需等待审批）</li>
     *   <li>{@code status}：PENDING</li>
     *   <li>{@code approvalId}：审批请求 ID，调用方凭此 ID 查询审批状态
     *       并在审批通过后调用 {@code POST /api/v1/transfers/sign/approved}</li>
     *   <li>{@code message}：「大额签名需等待审批通过」</li>
     * </ul></p>
     *
     * @param approvalId 审批请求 ID
     * @return 待审批响应体
     */
    private Object pendingApprovalResponse(String approvalId) {
        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", 2001);
        response.put("status", "PENDING");
        response.put("approvalId", approvalId);
        response.put("message", "大额签名需等待审批通过");
        return response;
    }

    /**
     * 查询指定地址的 Nonce 池快照。
     *
     * <p>SECURITY (P2-F1): 端点强制 {@code ROLE_OPERATOR} 鉴权。
     * Nonce 池查询属于运维只读操作，签名操作（{@code ROLE_SIGNER}）
     * 不应直接访问以遵循最小权限原则。Nonce 池暴露可辅助构造交易，
     * 因此仍受鉴权保护，不开放给 {@code ROLE_READ}。</p>
     */
    @PreAuthorize("hasRole('" + SecurityRoles.OPERATOR + "')")
    @RequestMapping(value="/getNoncePool",method = RequestMethod.GET )
    public Object getNoncePool(@RequestParam(value = "address", required = true) String address){
        if(WalletUtils.verifyAddress(address)!=0){
            APIResult result = new APIResult();
            result.setStatusCode(5000);
            result.setMessage("Address Error");
            return result;
        }
        TreeMap<Long, NonceState> tree=noncePool.getTreemap(address);
        return APIResult.newFailResult(2000,"SUCCESS",tree);
    }

}