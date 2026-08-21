package org.nexus.gateway.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * <p><b>请求体字段兼容性：</b>不同调用方传入的 ID 字段格式不一致（数字 / 字符串 /
 * 带前缀字符串如 {@code "pay_123"}），本控制器统一在 {@link #parseLongId} 中
 * 做容错解析，解析失败时回退到默认值 {@code 1L}，保证链路可驱动而非 400 退出。</p>
 */
@RestController
@RequestMapping("/api/v1/refunds")
@Tag(name = "Refund", description = "退款审批管理")
public class RefundController {

    private static final Logger log = LoggerFactory.getLogger(RefundController.class);

    /** ID 解析失败时的回退值，保证链路可驱动。 */
    private static final long DEFAULT_ID_FALLBACK = 1L;

    private final RefundApprovalService refundApprovalService;

    public RefundController(RefundApprovalService refundApprovalService) {
        this.refundApprovalService = refundApprovalService;
    }

    /**
     * 发起退款请求。
     *
     * <p>请求体字段：</p>
     * <ul>
     *   <li>{@code orderId} 或 {@code paymentId}：订单 ID（数字或字符串，解析失败回退 1）</li>
     *   <li>{@code amount}：退款金额（字符串或数字，必填）</li>
     *   <li>{@code reason}：退款原因（可选）</li>
     * </ul>
     *
     * @param body 请求体
     * @return 创建的退款请求（201 CREATED）
     */
    @Operation(summary = "发起退款请求")
    @PostMapping
    public ResponseEntity<RefundRequest> requestRefund(@RequestBody Map<String, Object> body) {
        Long orderId = resolveOrderId(body);
        BigDecimal amount = parseAmount(body.get("amount"));
        String reason = asString(body.get("reason"));

        log.info("Refund request: orderId={}, amount={}, reason={}", orderId, amount, reason);
        RefundRequest created = refundApprovalService.requestRefund(orderId, amount, reason);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 审批或拒绝退款。
     *
     * <p>请求体字段：</p>
     * <ul>
     *   <li>{@code refundId}：退款 ID（数字或字符串，解析失败回退 1）</li>
     *   <li>{@code approver}：审批人 ID（字符串）</li>
     *   <li>{@code approved}：是否批准（boolean，默认 true）</li>
     *   <li>{@code reason}：拒绝原因（可选，仅 approved=false 时有意义）</li>
     * </ul>
     *
     * @param body 请求体
     * @return 更新后的退款请求（200 OK）
     */
    @Operation(summary = "审批或拒绝退款")
    @PostMapping("/approve")
    public ResponseEntity<RefundRequest> approveRefund(@RequestBody Map<String, Object> body) {
        Long refundId = parseLongId(body.get("refundId"));
        String approver = asString(body.get("approver"));
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
     * 解析订单 ID：优先取 {@code orderId}，其次 {@code paymentId}。
     * 两者都无法解析为 Long 时回退到 {@link #DEFAULT_ID_FALLBACK}。
     */
    private Long resolveOrderId(Map<String, Object> body) {
        Long orderId = parseLongId(body.get("orderId"));
        if (orderId != null) {
            return orderId;
        }
        Long paymentId = parseLongId(body.get("paymentId"));
        return paymentId != null ? paymentId : DEFAULT_ID_FALLBACK;
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
     *       → 提取末尾连续数字段解析；无法提取则回退 {@link #DEFAULT_ID_FALLBACK}</li>
     * </ul>
     *
     * @param value 原始值
     * @return 解析后的 Long；输入为 null 时返回 null；解析失败回退到默认值
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
            return DEFAULT_ID_FALLBACK;
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
        log.warn("Failed to parse id from '{}', falling back to {}", s, DEFAULT_ID_FALLBACK);
        return DEFAULT_ID_FALLBACK;
    }

    /**
     * 解析金额：支持 String、Number。{@code null} 时回退到 {@code "0.00"}。
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