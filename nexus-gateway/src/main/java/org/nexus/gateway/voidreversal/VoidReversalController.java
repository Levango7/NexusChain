package org.nexus.gateway.voidreversal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 撤销与冲正 REST API Controller。
 *
 * <p>提供撤销请求和冲正请求的完整 CRUD + 审批 API：</p>
 *
 * <p><b>撤销 API：</b></p>
 * <ul>
 *   <li>POST /api/v1/void-requests — 创建撤销请求</li>
 *   <li>GET /api/v1/void-requests/{id} — 查询撤销请求状态</li>
 *   <li>POST /api/v1/void-requests/{id}/approve — 审批通过撤销</li>
 *   <li>POST /api/v1/void-requests/{id}/reject — 拒绝撤销</li>
 * </ul>
 *
 * <p><b>冲正 API：</b></p>
 * <ul>
 *   <li>POST /api/v1/reversal-requests — 创建冲正请求</li>
 *   <li>GET /api/v1/reversal-requests/{id} — 查询冲正请求状态</li>
 *   <li>POST /api/v1/reversal-requests/{id}/approve — 审批通过冲正</li>
 *   <li>POST /api/v1/reversal-requests/{id}/reject — 拒绝冲正</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class VoidReversalController {

    private static final Logger log = LoggerFactory.getLogger(VoidReversalController.class);

    private final VoidService voidService;
    private final ReversalService reversalService;

    public VoidReversalController(VoidService voidService, ReversalService reversalService) {
        this.voidService = voidService;
        this.reversalService = reversalService;
    }

    // === 撤销请求 API ===

    /**
     * 创建撤销请求 — 当日撤销。
     *
     * <p>请求体：</p>
     * <pre>{@code
     * {
     *   "orderId": Long,
     *   "reason": String (optional, max 256),
     *   "operatorId": String
     * }
     * }</pre>
     */
    @PostMapping("/void-requests")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> createVoidRequest(@RequestBody Map<String, Object> request) {
        try {
            Long orderId = Long.valueOf(request.get("orderId").toString());
            String reason = request.get("reason") != null ? request.get("reason").toString() : null;
            String operatorId = request.get("operatorId") != null ? request.get("operatorId").toString() : "UNKNOWN";

            VoidRequest voidRequest = voidService.requestVoid(orderId, reason, operatorId);

            return ResponseEntity.ok(toVoidResponse(voidRequest));

        } catch (IllegalArgumentException e) {
            log.warn("创建撤销请求失败（参数错误）: {}", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse("INVALID_REQUEST", e.getMessage()));

        } catch (IllegalStateException e) {
            log.warn("创建撤销请求失败（业务规则）: {}", e.getMessage());
            String errorCode = mapVoidErrorCode(e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse(errorCode, e.getMessage()));
        }
    }

    /**
     * 查询撤销请求状态。
     */
    @GetMapping("/void-requests/{id}")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getVoidRequest(@PathVariable Long id) {
        VoidRequest voidRequest = voidService.findById(id);
        if (voidRequest == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toVoidResponse(voidRequest));
    }

    /**
     * 审批通过撤销 — 执行撤销操作。
     */
    @PostMapping("/void-requests/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> approveVoidRequest(@PathVariable Long id) {
        try {
            VoidRequest voidRequest = voidService.approveVoid(id);
            return ResponseEntity.ok(toVoidResponse(voidRequest));

        } catch (IllegalArgumentException e) {
            log.warn("审批撤销失败（参数错误）: {}", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse("INVALID_REQUEST", e.getMessage()));

        } catch (IllegalStateException e) {
            log.warn("审批撤销失败（业务规则）: {}", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse("INVALID_STATUS", e.getMessage()));
        }
    }

    /**
     * 拒绝撤销。
     *
     * <p>请求体（可选）：</p>
     * <pre>{@code
     * {
     *   "reason": String
     * }
     * }</pre>
     */
    @PostMapping("/void-requests/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> rejectVoidRequest(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> request) {
        try {
            String reason = request != null && request.get("reason") != null
                    ? request.get("reason").toString() : null;

            VoidRequest voidRequest = voidService.rejectVoid(id, reason);
            return ResponseEntity.ok(toVoidResponse(voidRequest));

        } catch (IllegalArgumentException e) {
            log.warn("拒绝撤销失败（参数错误）: {}", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse("INVALID_REQUEST", e.getMessage()));

        } catch (IllegalStateException e) {
            log.warn("拒绝撤销失败（业务规则）: {}", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse("INVALID_STATUS", e.getMessage()));
        }
    }

    // === 冲正请求 API ===

    /**
     * 创建冲正请求 — 隔日冲正。
     *
     * <p>请求体：</p>
     * <pre>{@code
     * {
     *   "orderId": Long,
     *   "reason": String (optional, max 256),
     *   "operatorId": String
     * }
     * }</pre>
     */
    @PostMapping("/reversal-requests")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> createReversalRequest(@RequestBody Map<String, Object> request) {
        try {
            Long orderId = Long.valueOf(request.get("orderId").toString());
            String reason = request.get("reason") != null ? request.get("reason").toString() : null;
            String operatorId = request.get("operatorId") != null ? request.get("operatorId").toString() : "UNKNOWN";

            ReversalRequest reversalRequest = reversalService.requestReversal(orderId, reason, operatorId);

            return ResponseEntity.ok(toReversalResponse(reversalRequest));

        } catch (IllegalArgumentException e) {
            log.warn("创建冲正请求失败（参数错误）: {}", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse("INVALID_REQUEST", e.getMessage()));

        } catch (IllegalStateException e) {
            log.warn("创建冲正请求失败（业务规则）: {}", e.getMessage());
            String errorCode = mapReversalErrorCode(e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse(errorCode, e.getMessage()));
        }
    }

    /**
     * 查询冲正请求状态。
     */
    @GetMapping("/reversal-requests/{id}")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getReversalRequest(@PathVariable Long id) {
        ReversalRequest reversalRequest = reversalService.findById(id);
        if (reversalRequest == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toReversalResponse(reversalRequest));
    }

    /**
     * 审批通过冲正 — 执行冲正操作。
     */
    @PostMapping("/reversal-requests/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> approveReversalRequest(@PathVariable Long id) {
        try {
            ReversalRequest reversalRequest = reversalService.approveReversal(id);
            return ResponseEntity.ok(toReversalResponse(reversalRequest));

        } catch (IllegalArgumentException e) {
            log.warn("审批冲正失败（参数错误）: {}", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse("INVALID_REQUEST", e.getMessage()));

        } catch (IllegalStateException e) {
            log.warn("审批冲正失败（业务规则）: {}", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse("INVALID_STATUS", e.getMessage()));
        }
    }

    /**
     * 拒绝冲正。
     *
     * <p>请求体（可选）：</p>
     * <pre>{@code
     * {
     *   "reason": String
     * }
     * }</pre>
     */
    @PostMapping("/reversal-requests/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> rejectReversalRequest(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> request) {
        try {
            String reason = request != null && request.get("reason") != null
                    ? request.get("reason").toString() : null;

            ReversalRequest reversalRequest = reversalService.rejectReversal(id, reason);
            return ResponseEntity.ok(toReversalResponse(reversalRequest));

        } catch (IllegalArgumentException e) {
            log.warn("拒绝冲正失败（参数错误）: {}", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse("INVALID_REQUEST", e.getMessage()));

        } catch (IllegalStateException e) {
            log.warn("拒绝冲正失败（业务规则）: {}", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse("INVALID_STATUS", e.getMessage()));
        }
    }

    // === 内部方法 ===

    /**
     * 将 VoidRequest 转换为响应 Map。
     */
    private Map<String, Object> toVoidResponse(VoidRequest voidRequest) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", voidRequest.getId());
        response.put("voidNo", voidRequest.getVoidNo());
        response.put("orderId", voidRequest.getOrderId());
        response.put("merchantId", voidRequest.getMerchantId());
        response.put("amount", voidRequest.getAmount());
        response.put("status", voidRequest.getStatus().name());
        response.put("reason", voidRequest.getReason());
        response.put("operatorId", voidRequest.getOperatorId());
        response.put("createdAt", voidRequest.getCreatedAt());
        response.put("completedAt", voidRequest.getCompletedAt());
        return response;
    }

    /**
     * 将 ReversalRequest 转换为响应 Map。
     */
    private Map<String, Object> toReversalResponse(ReversalRequest reversalRequest) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", reversalRequest.getId());
        response.put("reversalNo", reversalRequest.getReversalNo());
        response.put("orderId", reversalRequest.getOrderId());
        response.put("merchantId", reversalRequest.getMerchantId());
        response.put("amount", reversalRequest.getAmount());
        response.put("status", reversalRequest.getStatus().name());
        response.put("reversalType", reversalRequest.getReversalType().name());
        response.put("reason", reversalRequest.getReason());
        response.put("operatorId", reversalRequest.getOperatorId());
        response.put("createdAt", reversalRequest.getCreatedAt());
        response.put("completedAt", reversalRequest.getCompletedAt());
        return response;
    }

    /**
     * 构造错误响应。
     */
    private Map<String, Object> errorResponse(String code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("errorCode", code);
        response.put("errorMessage", message);
        return response;
    }

    /**
     * 根据错误消息映射撤销相关的错误码。
     */
    private String mapVoidErrorCode(String message) {
        if (message.contains("超过撤销窗口")) {
            return "VOID_WINDOW_EXPIRED";
        }
        if (message.contains("已有退款")) {
            return "ALREADY_REFUNDED";
        }
        if (message.contains("已有进行中或已完成的撤销请求")) {
            return "ALREADY_VOIDED";
        }
        if (message.contains("订单状态非 PAID")) {
            return "INVALID_ORDER_STATUS";
        }
        return "VOID_REQUEST_FAILED";
    }

    /**
     * 根据错误消息映射冲正相关的错误码。
     */
    private String mapReversalErrorCode(String message) {
        if (message.contains("已有进行中或已完成的冲正请求")) {
            return "ALREADY_REVERSED";
        }
        if (message.contains("订单状态非 PAID")) {
            return "INVALID_ORDER_STATUS";
        }
        return "REVERSAL_REQUEST_FAILED";
    }
}