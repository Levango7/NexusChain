package org.nexus.gateway.controller.v2;

import org.nexus.gateway.apiversion.V2ErrorCode;
import org.nexus.gateway.apiversion.V2ErrorResponse;
import org.nexus.gateway.security.MerchantOwnershipException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.validation.ConstraintViolationException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * v2 API 全局异常处理器（P4-T7）。
 *
 * <p>仅对 {@code org.nexus.gateway.controller.v2} 包下的控制器生效，
 * 将异常转换为 {@link V2ErrorResponse} 统一格式。</p>
 *
 * <p>v1 控制器仍由 {@link org.nexus.gateway.config.GlobalExceptionHandler} 处理，
 * 保持 v1 响应格式不变。</p>
 *
 * <p>驻留在 controller.v2 包内（而非 apiversion 包），以避免
 * apiversion → controller → apiversion 的切片循环依赖。</p>
 */
@RestControllerAdvice(basePackages = "org.nexus.gateway.controller.v2")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class V2ExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(V2ExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<V2ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> details = new HashMap<>();
        details.put("fieldErrors", e.getBindingResult().getFieldErrors().stream()
                .map(fe -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("field", fe.getField());
                    m.put("rejectedValue", fe.getRejectedValue());
                    m.put("message", fe.getDefaultMessage());
                    return m;
                })
                .collect(Collectors.toList()));
        return ResponseEntity.badRequest()
                .body(V2ErrorResponse.of(V2ErrorCode.BAD_REQUEST.getCode(),
                        "Request validation failed", details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<V2ErrorResponse> handleConstraint(ConstraintViolationException e) {
        Map<String, Object> details = new HashMap<>();
        details.put("violations", e.getConstraintViolations().stream()
                .map(v -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("path", v.getPropertyPath().toString());
                    m.put("message", v.getMessage());
                    m.put("invalidValue", v.getInvalidValue());
                    return m;
                })
                .collect(Collectors.toList()));
        return ResponseEntity.badRequest()
                .body(V2ErrorResponse.of(V2ErrorCode.BAD_REQUEST.getCode(),
                        "Constraint violation", details));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<V2ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("v2 bad request: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(V2ErrorResponse.of(V2ErrorCode.BAD_REQUEST.getCode(), e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<V2ErrorResponse> handleIllegalState(IllegalStateException e) {
        log.warn("v2 state conflict: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(V2ErrorResponse.of(V2ErrorCode.ILLEGAL_STATE_TRANSITION.getCode(),
                        e.getMessage()));
    }

    @ExceptionHandler(MerchantOwnershipException.class)
    public ResponseEntity<V2ErrorResponse> handleOwnership(MerchantOwnershipException e) {
        log.warn("v2 ownership violation: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(V2ErrorResponse.of(V2ErrorCode.FORBIDDEN.getCode(),
                        V2ErrorCode.FORBIDDEN.getDefaultMessage()));
    }

    @ExceptionHandler(PaymentV2Controller.BatchFailedException.class)
    public ResponseEntity<V2ErrorResponse> handleBatchFailed(
            PaymentV2Controller.BatchFailedException e) {
        log.warn("Batch failed at index {}: {}", e.getFailedIndex(), e.getMessage());
        Map<String, Object> details = new HashMap<>();
        details.put("failedIndex", e.getFailedIndex());
        details.put("strategy", "ALL_OR_NOTHING");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(V2ErrorResponse.of(V2ErrorCode.ILLEGAL_STATE_TRANSITION.getCode(),
                        "Batch rolled back due to item failure", details));
    }

    /**
     * 方法安全层（@PreAuthorize）拒绝：403（2026-09-03 死端点修复配套）。
     *
     * <p>无此 handler 时 AccessDeniedException 会落入 Exception 兜底被报成
     * 500，鉴权失败伪装成服务器内部错误。</p>
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<V2ErrorResponse> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException e) {
        log.warn("v2 access denied by method security: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(V2ErrorResponse.of(V2ErrorCode.FORBIDDEN.getCode(),
                        V2ErrorCode.FORBIDDEN.getDefaultMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<V2ErrorResponse> handleGeneric(Exception e) {
        log.error("v2 unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(V2ErrorResponse.of(V2ErrorCode.INTERNAL_ERROR.getCode(),
                        V2ErrorCode.INTERNAL_ERROR.getDefaultMessage()));
    }
}