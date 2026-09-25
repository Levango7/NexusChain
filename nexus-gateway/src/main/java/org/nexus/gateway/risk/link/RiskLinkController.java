package org.nexus.gateway.risk.link;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 风控联动 API 端点 — 冻结/解冻/大额交易审核。
 *
 * <p>提供以下 API：</p>
 * <ul>
 *   <li>{@code POST /api/v1/risk/link/freeze} — 手动触发冻结联动</li>
 *   <li>{@code POST /api/v1/risk/link/unfreeze} — 手动触发解冻联动</li>
 *   <li>{@code POST /api/v1/risk/link/status-change} — 手动触发状态变更联动</li>
 *   <li>{@code GET /api/v1/risk/link/interceptions} — 查询大额交易拦截记录</li>
 *   <li>{@code POST /api/v1/risk/link/interceptions/{id}/approve} — 审核通过</li>
 *   <li>{@code POST /api/v1/risk/link/interceptions/{id}/reject} — 审核驳回</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/risk/link")
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
     * 手动触发冻结联动。
     *
     * @param body        请求体（riskEventId, amount, reason）
     * @param httpRequest HTTP 请求
     * @return 联动记录
     */
    @Operation(summary = "手动触发冻结联动")
    @PostMapping("/freeze")
    public ResponseEntity<RiskAccountLinkRecord> freeze(
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);
        String riskEventId = (String) body.get("riskEventId");
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String reason = (String) body.get("reason");

        RiskAccountLinkRecord record = linkService.executeFreeze(riskEventId, merchantId, amount, reason);
        return ResponseEntity.ok(record);
    }

    /**
     * 手动触发解冻联动。
     *
     * @param body        请求体（riskEventId, amount, reason）
     * @param httpRequest HTTP 请求
     * @return 联动记录
     */
    @Operation(summary = "手动触发解冻联动")
    @PostMapping("/unfreeze")
    public ResponseEntity<RiskAccountLinkRecord> unfreeze(
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);
        String riskEventId = (String) body.get("riskEventId");
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String reason = (String) body.get("reason");

        RiskAccountLinkRecord record = linkService.executeUnfreeze(riskEventId, merchantId, amount, reason);
        return ResponseEntity.ok(record);
    }

    /**
     * 手动触发状态变更联动。
     *
     * @param body        请求体（riskEventId, linkAction, reason）
     * @param httpRequest HTTP 请求
     * @return 联动记录
     */
    @Operation(summary = "手动触发状态变更联动")
    @PostMapping("/status-change")
    public ResponseEntity<RiskAccountLinkRecord> statusChange(
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);
        String riskEventId = (String) body.get("riskEventId");
        LinkAction linkAction = LinkAction.valueOf((String) body.get("linkAction"));
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
     * @param interceptionId 拦截记录 ID
     * @param body            请求体（reviewerId, reviewComment）
     * @param httpRequest     HTTP 请求
     * @return 更新后的拦截记录
     */
    @Operation(summary = "审核通过大额交易")
    @PostMapping("/interceptions/{interceptionId}/approve")
    public ResponseEntity<LargeTransactionInterception> approveInterception(
            @PathVariable String interceptionId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        ownershipGuard.requireMerchantId(httpRequest);
        Long reviewerId = body.get("reviewerId") != null
                ? Long.valueOf(body.get("reviewerId").toString())
                : null;
        String reviewComment = (String) body.get("reviewComment");

        LargeTransactionInterception record = interceptionService.approve(interceptionId, reviewerId, reviewComment);
        return ResponseEntity.ok(record);
    }

    /**
     * 审核驳回 — 阻断大额交易。
     *
     * @param interceptionId 拦截记录 ID
     * @param body            请求体（reviewerId, reviewComment）
     * @param httpRequest     HTTP 请求
     * @return 更新后的拦截记录
     */
    @Operation(summary = "审核驳回大额交易")
    @PostMapping("/interceptions/{interceptionId}/reject")
    public ResponseEntity<LargeTransactionInterception> rejectInterception(
            @PathVariable String interceptionId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        ownershipGuard.requireMerchantId(httpRequest);
        Long reviewerId = body.get("reviewerId") != null
                ? Long.valueOf(body.get("reviewerId").toString())
                : null;
        String reviewComment = (String) body.get("reviewComment");

        LargeTransactionInterception record = interceptionService.reject(interceptionId, reviewerId, reviewComment);
        return ResponseEntity.ok(record);
    }
}