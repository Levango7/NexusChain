package org.nexus.gateway.risk;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 风控事件 API 端点。
 *
 * <p>提供风控事件的查询和统计摘要功能。所有端点均从 JWT 中提取商户 ID，
 * 确保商户只能查看自己的风控事件。</p>
 *
 * <ul>
 *   <li>{@code GET /api/v1/risk/events} — 获取商户的风控事件列表</li>
 *   <li>{@code GET /api/v1/risk/events?type=PAYMENT_EVALUATION} — 按类型筛选</li>
 *   <li>{@code GET /api/v1/risk/events/{orderId}} — 获取某订单的风控事件</li>
 *   <li>{@code GET /api/v1/risk/events/summary} — 获取风控事件统计摘要</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/risk/events")
@Tag(name = "Risk Events", description = "风控事件：事件查询与统计摘要")
public class RiskEventController {

    private static final Logger log = LoggerFactory.getLogger(RiskEventController.class);

    private final RiskEventService riskEventService;
    private final MerchantOwnershipGuard ownershipGuard;

    public RiskEventController(RiskEventService riskEventService,
                                MerchantOwnershipGuard ownershipGuard) {
        this.riskEventService = riskEventService;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * 获取商户的风控事件列表。
     *
     * <p>支持可选的 type 参数按事件类型筛选。</p>
     *
     * @param type        可选的事件类型筛选
     * @param httpRequest HTTP 请求
     * @return 风控事件列表
     */
    @Operation(summary = "获取商户的风控事件列表")
    @GetMapping
    public ResponseEntity<List<RiskEvent>> getEvents(
            @RequestParam(required = false) String type,
            HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);

        if (type != null && !type.isBlank()) {
            RiskEvent.EventType eventType;
            try {
                eventType = RiskEvent.EventType.valueOf(type);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid event type: " + type
                        + ", must be one of PAYMENT_EVALUATION, REFUND_EVALUATION, "
                        + "RULE_TRIGGERED, MANUAL_REVIEW, BLACKLIST_ACTION, DEVICE_FINGERPRINT");
            }
            List<RiskEvent> events = riskEventService.getEventsByType(eventType);
            // 归属过滤：只返回该商户的事件
            List<RiskEvent> filtered = events.stream()
                    .filter(e -> merchantId.equals(e.getMerchantId()))
                    .toList();
            return ResponseEntity.ok(filtered);
        }

        List<RiskEvent> events = riskEventService.getEventsByMerchant(merchantId);
        return ResponseEntity.ok(events);
    }

    /**
     * 获取某订单的风控事件。
     *
     * @param orderId     订单号
     * @param httpRequest HTTP 请求
     * @return 风控事件列表
     */
    @Operation(summary = "获取某订单的风控事件")
    @GetMapping("/{orderId}")
    public ResponseEntity<List<RiskEvent>> getEventsByOrder(@PathVariable String orderId,
                                                             HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);

        List<RiskEvent> events = riskEventService.getEventsByOrder(orderId);
        // 归属过滤：只返回该商户的事件
        List<RiskEvent> filtered = events.stream()
                .filter(e -> merchantId.equals(e.getMerchantId()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    /**
     * 获取风控事件统计摘要。
     *
     * <p>返回各风控决策的事件数量统计：</p>
     * <pre>{@code
     * {
     *   "totalEvents": 100,
     *   "approved": 80,
     *   "rejected": 15,
     *   "pendingReview": 5,
     *   "frozen": 0
     * }
     * }</pre>
     *
     * @param httpRequest HTTP 请求
     * @return 统计摘要
     */
    @Operation(summary = "获取风控事件统计摘要")
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary(HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);

        long approved = riskEventService.countEventsByMerchantAndDecision(merchantId, "APPROVED");
        long rejected = riskEventService.countEventsByMerchantAndDecision(merchantId, "REJECTED");
        long pendingReview = riskEventService.countEventsByMerchantAndDecision(merchantId, "PENDING_REVIEW");
        long frozen = riskEventService.countEventsByMerchantAndDecision(merchantId, "FROZEN");
        long totalEvents = approved + rejected + pendingReview + frozen;

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalEvents", totalEvents);
        summary.put("approved", approved);
        summary.put("rejected", rejected);
        summary.put("pendingReview", pendingReview);
        summary.put("frozen", frozen);

        log.info("Risk event summary: merchantId={}, total={}, approved={}, rejected={}, pendingReview={}, frozen={}",
                merchantId, totalEvents, approved, rejected, pendingReview, frozen);
        return ResponseEntity.ok(summary);
    }
}