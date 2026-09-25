package org.nexus.gateway.escrow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 担保交易/预授权 REST API Controller。
 *
 * <p>提供担保交易和预授权的完整 REST API：</p>
 *
 * <p>担保交易 API：</p>
 * <ul>
 *   <li>POST /api/v1/escrow — 创建担保交易</li>
 *   <li>GET /api/v1/escrow/{orderId} — 查询担保交易状态</li>
 *   <li>POST /api/v1/escrow/{escrowNo}/fund — 买家付款</li>
 *   <li>POST /api/v1/escrow/{escrowNo}/confirm — 确认收货</li>
 *   <li>POST /api/v1/escrow/{escrowNo}/refund — 退款</li>
 * </ul>
 *
 * <p>预授权 API：</p>
 * <ul>
 *   <li>POST /api/v1/pre-auth — 创建预授权</li>
 *   <li>GET /api/v1/pre-auth/{orderId} — 查询预授权状态</li>
 *   <li>POST /api/v1/pre-auth/{preauthNo}/capture — 扣款</li>
 *   <li>POST /api/v1/pre-auth/{preauthNo}/void — 撤销预授权</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class EscrowController {

    private static final Logger log = LoggerFactory.getLogger(EscrowController.class);

    private final EscrowService escrowService;
    private final PreAuthService preAuthService;

    public EscrowController(EscrowService escrowService, PreAuthService preAuthService) {
        this.escrowService = escrowService;
        this.preAuthService = preAuthService;
    }

    // === 担保交易 API ===

    /**
     * 创建担保交易。
     *
     * <p>请求体：</p>
     * <pre>
     * {
     *   "merchantId": Long,
     *   "orderId": Long,
     *   "amount": BigDecimal,
     *   "buyerAddress": String,
     *   "autoConfirmDays": Int (可选, 默认 7)
     * }
     * </pre>
     */
    @PostMapping("/escrow")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> createEscrow(@RequestBody Map<String, Object> request) {
        try {
            Long merchantId = toLong(request.get("merchantId"));
            Long orderId = toLong(request.get("orderId"));
            BigDecimal amount = toBigDecimal(request.get("amount"));
            String buyerAddress = (String) request.get("buyerAddress");
            Integer autoConfirmDays = request.get("autoConfirmDays") != null
                    ? toInt(request.get("autoConfirmDays")) : null;

            EscrowTransaction escrow = escrowService.createEscrow(merchantId, orderId, amount,
                    buyerAddress, autoConfirmDays);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("escrowNo", escrow.getEscrowNo());
            response.put("status", escrow.getStatus().name());
            response.put("amount", escrow.getAmount());
            response.put("autoConfirmDays", escrow.getAutoConfirmDays());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            return errorResponse(HttpStatus.CONFLICT, "ESCROW_ALREADY_EXISTS", e.getMessage());
        }
    }

    /**
     * 查询担保交易状态。
     */
    @GetMapping("/escrow/{orderId}")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getEscrowStatus(@PathVariable Long orderId) {
        EscrowTransaction escrow = escrowService.getEscrowStatus(orderId);
        if (escrow == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "ESCROW_NOT_FOUND",
                    "担保交易不存在: orderId=" + orderId);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("escrowNo", escrow.getEscrowNo());
        response.put("status", escrow.getStatus().name());
        response.put("amount", escrow.getAmount());
        response.put("merchantId", escrow.getMerchantId());
        response.put("buyerAddress", escrow.getBuyerAddress());
        response.put("autoConfirmDays", escrow.getAutoConfirmDays());
        response.put("fundedAt", escrow.getFundedAt());
        response.put("confirmedAt", escrow.getConfirmedAt());
        response.put("releasedAt", escrow.getReleasedAt());
        response.put("createdAt", escrow.getCreatedAt());

        return ResponseEntity.ok(response);
    }

    /**
     * 确认收货 — 资金释放给商户。
     */
    @PostMapping("/escrow/{escrowNo}/confirm")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> confirmEscrow(@PathVariable String escrowNo) {
        try {
            EscrowTransaction escrow = escrowService.confirmEscrow(escrowNo);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("escrowNo", escrow.getEscrowNo());
            response.put("status", escrow.getStatus().name());
            response.put("releasedAt", escrow.getReleasedAt());

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.NOT_FOUND, "ESCROW_NOT_FOUND", e.getMessage());
        } catch (IllegalStateException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, "ESCROW_FROZEN", e.getMessage());
        }
    }

    /**
     * 买家付款 — 资金冻结到担保账户（CREATED → FUNDED）。
     *
     * <p>请求体（可选）：</p>
     * <pre>
     * {
     *   "payerAddress": String (可选，默认使用创建担保交易时的买家地址)
     * }
     * </pre>
     */
    @PostMapping("/escrow/{escrowNo}/fund")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> fundEscrow(@PathVariable String escrowNo,
                                                           @RequestBody(required = false) Map<String, Object> request) {
        try {
            String payerAddress = request != null ? (String) request.get("payerAddress") : null;

            EscrowTransaction escrow = escrowService.fundEscrow(escrowNo, payerAddress);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("escrowNo", escrow.getEscrowNo());
            response.put("status", escrow.getStatus().name());
            response.put("amount", escrow.getAmount());
            response.put("fundedAt", escrow.getFundedAt());

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.NOT_FOUND, "ESCROW_NOT_FOUND", e.getMessage());
        } catch (IllegalStateException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, "ESCROW_INVALID_STATE", e.getMessage());
        }
    }

    /**
     * 退款 — 资金退回买家。
     */
    @PostMapping("/escrow/{escrowNo}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> refundEscrow(@PathVariable String escrowNo) {
        try {
            EscrowTransaction escrow = escrowService.refundEscrow(escrowNo);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("escrowNo", escrow.getEscrowNo());
            response.put("status", escrow.getStatus().name());

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.NOT_FOUND, "ESCROW_NOT_FOUND", e.getMessage());
        } catch (IllegalStateException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, "ESCROW_ALREADY_RELEASED", e.getMessage());
        }
    }

    // === 预授权 API ===

    /**
     * 创建预授权。
     *
     * <p>请求体：</p>
     * <pre>
     * {
     *   "merchantId": Long,
     *   "orderId": Long (可选),
     *   "freezeAmount": BigDecimal,
     *   "autoReleaseDays": Int (可选, 默认 3)
     * }
     * </pre>
     */
    @PostMapping("/pre-auth")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> createPreAuth(@RequestBody Map<String, Object> request) {
        try {
            Long merchantId = toLong(request.get("merchantId"));
            Long orderId = request.get("orderId") != null ? toLong(request.get("orderId")) : null;
            BigDecimal freezeAmount = toBigDecimal(request.get("freezeAmount"));
            Integer autoReleaseDays = request.get("autoReleaseDays") != null
                    ? toInt(request.get("autoReleaseDays")) : null;

            PreAuthTransaction preauth = preAuthService.createPreAuth(merchantId, orderId,
                    freezeAmount, autoReleaseDays);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("preauthNo", preauth.getPreauthNo());
            response.put("status", preauth.getStatus().name());
            response.put("freezeAmount", preauth.getFreezeAmount());
            response.put("authorizedAt", preauth.getAuthorizedAt());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
        } catch (IllegalStateException e) {
            return errorResponse(HttpStatus.CONFLICT, "INSUFFICIENT_BALANCE", e.getMessage());
        }
    }

    /**
     * 查询预授权状态。
     */
    @GetMapping("/pre-auth/{orderId}")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getPreAuthStatus(@PathVariable Long orderId) {
        PreAuthTransaction preauth = preAuthService.getPreAuthStatus(orderId);
        if (preauth == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "PREAUTH_NOT_FOUND",
                    "预授权不存在: orderId=" + orderId);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("preauthNo", preauth.getPreauthNo());
        response.put("status", preauth.getStatus().name());
        response.put("freezeAmount", preauth.getFreezeAmount());
        response.put("captureAmount", preauth.getCaptureAmount());
        response.put("merchantId", preauth.getMerchantId());
        response.put("autoReleaseDays", preauth.getAutoReleaseDays());
        response.put("authorizedAt", preauth.getAuthorizedAt());
        response.put("capturedAt", preauth.getCapturedAt());
        response.put("voidedAt", preauth.getVoidedAt());
        response.put("expiredAt", preauth.getExpiredAt());
        response.put("createdAt", preauth.getCreatedAt());

        return ResponseEntity.ok(response);
    }

    /**
     * 扣款 — 将冻结金额转为实际扣款（支持部分扣款）。
     *
     * <p>请求体：</p>
     * <pre>
     * {
     *   "captureAmount": BigDecimal
     * }
     * </pre>
     */
    @PostMapping("/pre-auth/{preauthNo}/capture")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> capturePreAuth(@PathVariable String preauthNo,
                                                               @RequestBody Map<String, Object> request) {
        try {
            BigDecimal captureAmount = toBigDecimal(request.get("captureAmount"));

            PreAuthTransaction preauth = preAuthService.capturePreAuth(preauthNo, captureAmount);

            BigDecimal releasedAmount = preauth.getFreezeAmount().subtract(captureAmount);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("preauthNo", preauth.getPreauthNo());
            response.put("status", preauth.getStatus().name());
            response.put("captureAmount", preauth.getCaptureAmount());
            response.put("releasedAmount", releasedAmount);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("超过冻结金额")) {
                return errorResponse(HttpStatus.BAD_REQUEST, "CAPTURE_EXCEEDS_AUTHORIZED", e.getMessage());
            }
            return errorResponse(HttpStatus.NOT_FOUND, "PREAUTH_NOT_FOUND", e.getMessage());
        } catch (IllegalStateException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, "PREAUTH_ALREADY_CAPTURED", e.getMessage());
        }
    }

    /**
     * 撤销预授权 — 释放全部冻结金额。
     */
    @PostMapping("/pre-auth/{preauthNo}/void")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> voidPreAuth(@PathVariable String preauthNo) {
        try {
            PreAuthTransaction preauth = preAuthService.voidPreAuth(preauthNo);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("preauthNo", preauth.getPreauthNo());
            response.put("status", preauth.getStatus().name());
            response.put("releasedAmount", preauth.getFreezeAmount());

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.NOT_FOUND, "PREAUTH_NOT_FOUND", e.getMessage());
        } catch (IllegalStateException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, "PREAUTH_ALREADY_CAPTURED", e.getMessage());
        }
    }

    // === 内部工具方法 ===

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String errorCode, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", errorCode);
        error.put("message", message);
        error.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.status(status).body(error);
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString());
    }

    private Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        return new BigDecimal(value.toString());
    }
}