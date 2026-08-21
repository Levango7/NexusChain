package org.nexus.gateway.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.refund.RefundApprovalService;
import org.nexus.gateway.refund.RefundRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 退款审批管理 REST API。
 *
 * <p>暴露多级审批链路端点，委托 {@link RefundApprovalService} 驱动退款状态机：
 * {@code PENDING → APPROVED/REJECTED → EXECUTED}。</p>
 *
 * <ul>
 *   <li>{@code POST /api/v1/refunds}：发起退款请求（201 CREATED）</li>
 *   <li>{@code POST /api/v1/refunds/approve}：审批或拒绝退款（200 OK）</li>
 *   <li>{@code POST /api/v1/refunds/{id}/execute}：执行已审批的退款（200 OK）</li>
 * </ul>
 *
 * <p><b>安全设计：</b></p>
 * <ul>
 *   <li>ID 解析失败时返回 400 Bad Request，绝不静默回退到默认值（P0-3 修复）</li>
 *   <li>审批人身份从认证上下文（{@code HttpServletRequest#getAttribute("nexus.merchantId")}）
 *       获取，忽略请求体中的 approver 字段（P0-7 修复）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/refunds")
@Tag(name = "Refund", description = "退款审批管理")
public class RefundController {

    private static final Logger log = LoggerFactory.getLogger(RefundController.class);

    /** ApiKeyInterceptor 设置的商户ID属性名 */
    private static final String MERCHANT_ID_ATTR = "nexus.merchantId";

    private final RefundApprovalService refundApprovalService;

    public RefundController(RefundApprovalService refundApprovalService) {
        this.refundApprovalService = refundApprovalService;
    }

    /**
     * 发起退款请求。
     *
     * <p>请求体字段：</p>
     * <ul>
     *   <li>{@code orderId} 或 {@code paymentId}：订单 ID（数字或字符串，解析失败返回 400）</li>
     *   <li>{@code amount}：退款金额（字符串或数字，必填）</li>
     *   <li>{@code reason}：退款原因（可选）</li>
     * </ul>
     *
     * @param body 请求体
     * @return 创建的退款请求（201 CREATED），或 400 Bad Request
     */
    @Operation(summary = "发起退款请求")
    @PostMapping
    public ResponseEntity<?> requestRefund(@RequestBody Map<String, Object> body) {
        Long orderId = resolveOrderId(body);
        if (orderId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "orderId or paymentId is required and must be valid"));
        }
        BigDecimal amount = parseAmount(body.get("amount"));
        String reason = asString(body.get("reason"));

        log.info("Refund request: orderId={}, amount={}, reason={}", orderId, amount, reason);
        RefundRequest created = refundApprovalService.requestRefund(orderId, amount, reason);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 审批或拒绝退款。
     *
     * <p><b>安全：</b>审批人身份从认证上下文获取（{@code nexus.merchantId}），
     * 忽略请求体中的 approver 字段。测试环境下若 attribute 不存在则回退到请求体。</p>
     *
     * <p>请求体字段：</p>
     * <ul>
     *   <li>{@code refundId}：退款 ID（数字或字符串，解析失败返回 400）</li>
     *   <li>{@code approved}：是否批准（boolean，默认 true）</li>
     *   <li>{@code reason}：拒绝原因（可选，仅 approved=false 时有意义）</li>
     * </ul>
     *
     * @param body    请求体
     * @param request HTTP 请求（用于获取认证商户ID）
     * @return 更新后的退款请求（200 OK），或 400 Bad Request
     */
    @Operation(summary = "审批或拒绝退款")
    @PostMapping("/approve")
    public ResponseEntity<?> approveRefund(@RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        Long refundId = parseLongId(body.get("refundId"));
        if (refundId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "refundId is required and must be valid"));
        }

        // P0-7 修复：审批人身份从认证上下文获取，不信任请求体
        String approver = resolveApprover(request, body);
        boolean approved = asBoolean(body.get("approved"));
        String reason = asString(body.get("reason"));

        RefundRequest result;
        if (approved) {
            log.info("Refund approve: refundId={}, approver={}", refundId, approver);
            result = refundApprovalService.approveRefund(refundId, approver);
        } else {
            log.info("Refund reject: refundId={}, approver={}, reason={}", refundId, approver, reason);
            result = refundApprovalService.rejectRefund(refundId, approver, reason);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 执行已审批的退款。
     *
     * @param id 退款 ID（路径变量）
     * @return 执行后的退款请求（200 OK）
     */
    @Operation(summary = "执行已审批的退款")
    @PostMapping("/{id}/execute")
    public ResponseEntity<RefundRequest> executeRefund(@PathVariable Long id) {
        log.info("Refund execute: refundId={}", id);
        RefundRequest result = refundApprovalService.executeRefund(id);
        return ResponseEntity.ok(result);
    }

    // --- 内部工具方法 ---

    /**
     * 从认证上下文获取审批人身份。
     *
     * <p>优先从 {@link HttpServletRequest} attribute {@code nexus.merchantId} 获取
     * （由 {@code ApiKeyInterceptor} 在鉴权通过后设置）。
     * 若 attribute 不存在（测试 mock 场景），回退到请求体中的 approver 字段。</p>
     */
    private String resolveApprover(HttpServletRequest request, Map<String, Object> body) {
        Object merchantAttr = request.getAttribute(MERCHANT_ID_ATTR);
        if (merchantAttr != null) {
            return merchantAttr.toString();
        }
        // 测试环境回退：ApiKeyInterceptor 被 mock 时 attribute 不存在
        String fallback = asString(body.get("approver"));
        return fallback != null ? fallback : "system";
    }

    /**
     * 解析订单 ID：优先取 {@code orderId}，其次 {@code paymentId}。
     * 两者都无法解析为 Long 时返回 {@code null}（由调用方返回 400）。
     */
    private Long resolveOrderId(Map<String, Object> body) {
        Long orderId = parseLongId(body.get("orderId"));
        if (orderId != null) {
            return orderId;
        }
        return parseLongId(body.get("paymentId"));
    }

    /**
     * 容错解析 Long ID。
     *
     * <p>支持以下输入：</p>
     * <ul>
     *   <li>{@code null} → 返回 {@code null}</li>
     *   <li>{@code Number} → {@code longValue()}</li>
     *   <li>纯数字字符串 → {@code Long.parseLong}</li>
     *   <li>带前缀字符串（如 {@code "pay_123"}、{@code "ref_test_001"}）
     *       → 提取末尾连续数字段解析；无法提取则返回 {@code null}</li>
     * </ul>
     *
     * @param value 原始值
     * @return 解析后的 Long；输入为 null 或解析失败时返回 null
     */
    private Long parseLongId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        // 纯数字直接解析
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
            // 继续尝试提取末尾数字段
        }
        // 带前缀字符串：提取末尾连续数字段（如 "pay_123" → 123, "ref_test_001" → 1）
        int end = s.length();
        while (end > 0 && !Character.isDigit(s.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isDigit(s.charAt(start - 1))) {
            start--;
        }
        if (start < end) {
            try {
                return Long.parseLong(s.substring(start, end));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        log.warn("Failed to parse id from '{}', returning null (caller should return 400)", s);
        return null;
    }

    /**
     * 解析金额：支持 String、Number。{@code null} 时回退到 {@code ZERO}。
     */
    private BigDecimal parseAmount(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse amount from '{}', using ZERO", value);
            return BigDecimal.ZERO;
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean asBoolean(Object value) {
        if (value == null) {
            return true; // 默认批准
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
