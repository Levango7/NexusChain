package org.nexus.gateway.split;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 分账/分润 API 端点。
 *
 * <p>提供分账规则的创建、查询、停用，以及订单分账明细查询功能。</p>
 *
 * <ul>
 *   <li>{@code POST /api/v1/split/rules} — 创建分账规则</li>
 *   <li>{@code GET /api/v1/split/rules} — 列出商户的分账规则</li>
 *   <li>{@code DELETE /api/v1/split/rules/{ruleId}} — 停用分账规则</li>
 *   <li>{@code GET /api/v1/split/orders/{orderNo}} — 查看订单的分账明细</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/split")
@Tag(name = "Split", description = "分账/分润：规则管理与分账明细查询")
public class SplitController {

    private static final Logger log = LoggerFactory.getLogger(SplitController.class);

    private final SplitService splitService;
    private final MerchantOwnershipGuard ownershipGuard;

    public SplitController(SplitService splitService, MerchantOwnershipGuard ownershipGuard) {
        this.splitService = splitService;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * 创建分账规则。
     *
     * <p>请求体示例：</p>
     * <pre>{@code
     * {
     *   "splitType": "RATIO",
     *   "splitValue": 500,
     *   "receiverAddress": "0x...",
     *   "description": "分销商佣金"
     * }
     * }</pre>
     *
     * @param request    创建规则请求体
     * @param httpRequest HTTP 请求（用于获取 JWT 中的 merchantId）
     * @return 创建的 SplitRule
     */
    @Operation(summary = "创建分账规则")
    @PostMapping("/rules")
    public ResponseEntity<SplitRule> createSplitRule(@RequestBody CreateSplitRuleRequest request,
                                                     HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);

        SplitRule.SplitType type;
        try {
            type = SplitRule.SplitType.valueOf(request.getSplitType());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid splitType: " + request.getSplitType()
                    + ", must be RATIO or FIXED");
        }

        SplitRule rule = splitService.createSplitRule(
                merchantId,
                type,
                request.getSplitValue(),
                request.getReceiverAddress(),
                request.getDescription());

        log.info("Split rule created: id={}, merchantId={}", rule.getId(), merchantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(rule);
    }

    /**
     * 列出商户的所有活跃分账规则。
     *
     * @param httpRequest HTTP 请求
     * @return 分账规则列表
     */
    @Operation(summary = "列出商户的分账规则")
    @GetMapping("/rules")
    public ResponseEntity<List<SplitRule>> getSplitRules(HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);
        List<SplitRule> rules = splitService.getSplitRules(merchantId);
        return ResponseEntity.ok(rules);
    }

    /**
     * 停用分账规则。
     *
     * @param ruleId      规则 ID
     * @param httpRequest HTTP 请求
     * @return 204 No Content
     */
    @Operation(summary = "停用分账规则")
    @DeleteMapping("/rules/{ruleId}")
    public ResponseEntity<Void> deactivateSplitRule(@PathVariable Long ruleId,
                                                    HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);
        splitService.deactivateSplitRule(ruleId, merchantId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 查看订单的分账明细。
     *
     * @param orderNo     订单号
     * @param httpRequest HTTP 请求
     * @return 分账明细列表
     */
    @Operation(summary = "查看订单的分账明细")
    @GetMapping("/orders/{orderNo}")
    public ResponseEntity<List<SplitOrder>> getSplitOrders(@PathVariable String orderNo,
                                                           HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);
        List<SplitOrder> orders = splitService.getSplitOrders(orderNo);

        // 归属校验：确保分账明细属于该商户
        for (SplitOrder order : orders) {
            ownershipGuard.requireOwned(merchantId, order.getMerchantId(), "split order", order.getId());
        }

        return ResponseEntity.ok(orders);
    }

    // --- Request DTOs ---

    public static class CreateSplitRuleRequest {
        /** 分账类型：RATIO 或 FIXED */
        private String splitType;
        /** 分账值：RATIO 模式为基点（1-10000），FIXED 模式为金额 */
        private BigDecimal splitValue;
        /** 接收分账金额的钱包地址 */
        private String receiverAddress;
        /** 规则描述（可选） */
        private String description;

        public String getSplitType() { return splitType; }
        public void setSplitType(String splitType) { this.splitType = splitType; }

        public BigDecimal getSplitValue() { return splitValue; }
        public void setSplitValue(BigDecimal splitValue) { this.splitValue = splitValue; }

        public String getReceiverAddress() { return receiverAddress; }
        public void setReceiverAddress(String receiverAddress) { this.receiverAddress = receiverAddress; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}