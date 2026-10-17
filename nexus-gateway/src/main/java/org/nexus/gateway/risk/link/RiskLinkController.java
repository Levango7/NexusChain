package org.nexus.gateway.risk.link;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 风控联动 API 端点 — 冻结/解冻/大额交易审核。
 *
 * <p>提供以下 API（写端点的角色见每条后面的标注）：</p>
 * <ul>
 *   <li>{@code POST /api/v1/risk/link/freeze} — 手动触发冻结联动 <b>[ADMIN]</b></li>
 *   <li>{@code POST /api/v1/risk/link/unfreeze} — 手动触发解冻联动 <b>[ADMIN]</b></li>
 *   <li>{@code POST /api/v1/risk/link/status-change} — 手动触发状态变更联动 <b>[ADMIN]</b></li>
 *   <li>{@code GET /api/v1/risk/link/interceptions} — 查询大额交易拦截记录（商户只看自己那份）</li>
 *   <li>{@code POST /api/v1/risk/link/interceptions/{id}/approve} — 审核通过 <b>[ADMIN]</b></li>
 *   <li>{@code POST /api/v1/risk/link/interceptions/{id}/reject} — 审核驳回 <b>[ADMIN]</b></li>
 * </ul>
 *
 * <p><b>为什么写端点必须 ADMIN-only（本次修正）：</b>类级注解是 {@code ADMIN or MERCHANT}，
 * 而这些路径又没从 {@code ApiKeyInterceptor} 排除，控制器还从调用方 API Key 反推目标商户——
 * 合起来是"只有被处置的商户自己能使这组端点，管理员一律 401"。实测（master 8965a2c8）：
 * 商户凭据 POST /unfreeze 直接返回 200 并落一条联动记录（{@code executeUnfreeze} 是真动钱，
 * FROZEN→BALANCE），而 approve/reject 与 freeze 对管理员回
 * {@code 401 {"code":40100,"message":"Missing API key"}} ⇒ 人工审核当时根本不可用。</p>
 *
 * <p>现在的形状与同仓 {@code MerchantsController} 的 admin 端点一致：管理动作从
 * {@code ApiKeyInterceptor} 排除、由 JWT + 方法级 {@code hasRole('ADMIN')} 保护，目标商户由请求体
 * 显式给出；只读的 {@code GET /interceptions} 仍留在 API Key 之后，靠
 * {@link org.nexus.gateway.security.MerchantOwnershipGuard} 保证商户只能看自己。</p>
 */
@RestController
@RequestMapping("/api/v1/risk/link")
@PreAuthorize("hasRole('ADMIN') or hasRole('MERCHANT')")
@Tag(name = "Risk Link", description = "风控联动：冻结/解冻/大额交易审核")
public class RiskLinkController {

    private static final Logger log = LoggerFactory.getLogger(RiskLinkController.class);

    private final RiskAccountLinkService linkService;
    private final LargeTransactionInterceptionService interceptionService;
    private final MerchantOwnershipGuard ownershipGuard;

    public RiskLinkController(RiskAccountLinkService linkService,
                               LargeTransactionInterceptionService interceptionService,
                               MerchantOwnershipGuard ownershipGuard) {
        this.linkService = linkService;
        this.interceptionService = interceptionService;
        this.ownershipGuard = ownershipGuard;
    }

    // ==================== 冻结/解冻联动 ====================

    /**
     * 手动触发冻结联动 —— <b>仅管理员</b>。
     *
     * <p>目标商户由请求体的 {@code merchantId} 显式给出：管理员没有"自己的商户"这一上下文，
     * 而旧实现把目标商户取自调用方 API Key，结果只有被处置的商户自己能使这组端点。</p>
     *
     * @param body 请求体（merchantId, riskEventId, amount, reason）
     * @return 联动记录
     */
    @Operation(summary = "手动触发冻结联动（仅管理员）")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/freeze")
    public ResponseEntity<RiskAccountLinkRecord> freeze(@RequestBody Map<String, Object> body) {
        Long merchantId = requireTargetMerchantId(body);
        if (merchantId == null) {
            return ResponseEntity.badRequest().build();
        }
        String riskEventId = (String) body.get("riskEventId");
        if (riskEventId == null || riskEventId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Object amountRaw = body.get("amount");
        if (amountRaw == null) {
            return ResponseEntity.badRequest().build();
        }
        BigDecimal amount = new BigDecimal(amountRaw.toString());
        String reason = (String) body.get("reason");

        RiskAccountLinkRecord record = linkService.executeFreeze(riskEventId, merchantId, amount, reason);
        return ResponseEntity.ok(record);
    }

    /**
     * 手动触发解冻联动 —— <b>仅管理员</b>。
     *
     * <p>{@code executeUnfreeze} 会把 FROZEN 金额放回 BALANCE（真动钱），因此绝不能由被处置的
     * 商户自己调用；旧形状下商户持自己的 API Key 就能自助撤掉处罚。</p>
     *
     * @param body 请求体（merchantId, riskEventId, amount, reason）
     * @return 联动记录
     */
    @Operation(summary = "手动触发解冻联动（仅管理员）")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/unfreeze")
    public ResponseEntity<RiskAccountLinkRecord> unfreeze(@RequestBody Map<String, Object> body) {
        Long merchantId = requireTargetMerchantId(body);
        if (merchantId == null) {
            return ResponseEntity.badRequest().build();
        }
        String riskEventId = (String) body.get("riskEventId");
        if (riskEventId == null || riskEventId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Object amountRaw = body.get("amount");
        if (amountRaw == null) {
            return ResponseEntity.badRequest().build();
        }
        BigDecimal amount = new BigDecimal(amountRaw.toString());
        String reason = (String) body.get("reason");

        RiskAccountLinkRecord record = linkService.executeUnfreeze(riskEventId, merchantId, amount, reason);
        return ResponseEntity.ok(record);
    }

    /**
     * 手动触发状态变更联动 —— <b>仅管理员</b>。
     *
     * <p>{@code STATUS_CLOSED} 是账户终态，误操作不可逆，更不能交给被处置方自己触发。</p>
     *
     * @param body 请求体（merchantId, riskEventId, linkAction, reason）
     * @return 联动记录
     */
    @Operation(summary = "手动触发状态变更联动（仅管理员）")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/status-change")
    public ResponseEntity<RiskAccountLinkRecord> statusChange(@RequestBody Map<String, Object> body) {
        Long merchantId = requireTargetMerchantId(body);
        if (merchantId == null) {
            return ResponseEntity.badRequest().build();
        }
        String riskEventId = (String) body.get("riskEventId");
        if (riskEventId == null || riskEventId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String linkActionStr = (String) body.get("linkAction");
        if (linkActionStr == null || linkActionStr.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        LinkAction linkAction;
        try {
            linkAction = LinkAction.valueOf(linkActionStr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        String reason = (String) body.get("reason");

        RiskAccountLinkRecord record = linkService.executeStatusChange(riskEventId, merchantId, linkAction, reason);
        return ResponseEntity.ok(record);
    }

    // ==================== 大额交易拦截审核 ====================

    /**
     * 查询商户的大额交易拦截记录。
     *
     * @param httpRequest HTTP 请求
     * @return 拦截记录列表
     */
    @Operation(summary = "查询大额交易拦截记录")
    @GetMapping("/interceptions")
    public ResponseEntity<List<LargeTransactionInterception>> getInterceptions(
            HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);
        List<LargeTransactionInterception> interceptions = interceptionService.getByMerchantId(merchantId);
        return ResponseEntity.ok(interceptions);
    }

    /**
     * 审核通过 — 放行大额交易。
     *
     * <p>reviewerId 从 SecurityContext 获取当前认证用户，防止伪造。</p>
     *
     * <p>不再调用 {@code ownershipGuard.requireMerchantId}：审核人是管理员，没有"自己的商户"上下文，
     * 那句检查只会把管理员挡在门外（实测 approve/reject 此前恒 401，人工审核不可用）。</p>
     *
     * @param interceptionId 拦截记录 ID
     * @param body            请求体（reviewComment）
     * @return 更新后的拦截记录
     */
    @Operation(summary = "审核通过大额交易")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/interceptions/{interceptionId}/approve")
    public ResponseEntity<LargeTransactionInterception> approveInterception(
            @PathVariable String interceptionId,
            @RequestBody Map<String, Object> body) {
        Long reviewerId = resolveReviewerId();
        if (reviewerId == null) {
            return ResponseEntity.badRequest().build();
        }
        String reviewComment = (String) body.get("reviewComment");

        LargeTransactionInterception record = interceptionService.approve(interceptionId, reviewerId, reviewComment);
        return ResponseEntity.ok(record);
    }

    /**
     * 审核驳回 — 阻断大额交易。
     *
     * <p>reviewerId 从 SecurityContext 获取当前认证用户，防止伪造。</p>
     *
     * <p>同 approve：去掉 {@code requireMerchantId}，否则管理员永远进不到这条审核路径。</p>
     *
     * @param interceptionId 拦截记录 ID
     * @param body            请求体（reviewComment）
     * @return 更新后的拦截记录
     */
    @Operation(summary = "审核驳回大额交易")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/interceptions/{interceptionId}/reject")
    public ResponseEntity<LargeTransactionInterception> rejectInterception(
            @PathVariable String interceptionId,
            @RequestBody Map<String, Object> body) {
        Long reviewerId = resolveReviewerId();
        if (reviewerId == null) {
            return ResponseEntity.badRequest().build();
        }
        String reviewComment = (String) body.get("reviewComment");

        LargeTransactionInterception record = interceptionService.reject(interceptionId, reviewerId, reviewComment);
        return ResponseEntity.ok(record);
    }

    // ==================== 内部方法 ====================

    /**
     * 管理动作的目标商户：必须由请求体 {@code merchantId} 显式给出。
     *
     * <p>旧实现用 {@link org.nexus.gateway.security.MerchantOwnershipGuard#requireMerchantId}
     * 从调用方 API Key 反推目标商户——那是防 IDOR 的正确工具，但用错了主体：它使这组
     * "风控联动"端点只对<b>被处置的商户自己</b>可用，管理员反而寸步难行。改为显式入参后，
     * 越权目标由 {@code hasRole('ADMIN')} 这一层负责拦。</p>
     *
     * <p>fail-closed：缺失、非数字、{@code <= 0} 一律返回 null，由调用方回 400，
     * 绝不回退成"调用方的商户"或任何默认值。</p>
     *
     * @param body 请求体
     * @return 目标商户 ID，非法时为 null
     */
    private static Long requireTargetMerchantId(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("merchantId");
        if (raw == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(raw.toString().trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * 从 SecurityContext 获取当前认证用户的 ID 作为 reviewerId。
     *
     * <p>优先从 JWT subject 解析为 Long。如果无法获取认证用户或解析失败，返回 null。</p>
     *
     * @return 当前认证用户 ID，或 null
     */
    private Long resolveReviewerId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("审核操作无法获取认证用户，SecurityContext 中无 Authentication");
            return null;
        }
        String name = authentication.getName();
        if (name == null || name.isBlank()) {
            log.warn("审核操作无法获取认证用户名，Authentication.name 为空");
            return null;
        }
        try {
            return Long.valueOf(name);
        } catch (NumberFormatException e) {
            log.warn("认证用户名无法解析为 Long: {}", name);
            return null;
        }
    }
}