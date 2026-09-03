package org.nexus.gateway.config;

import org.nexus.gateway.dto.ApiResponse;
import org.nexus.gateway.dto.ErrorCode;
import org.nexus.gateway.security.MerchantOwnershipException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(), e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException e) {
        log.warn("State conflict: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorCode.ILLEGAL_STATE_TRANSITION.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MerchantOwnershipException.class)
    public ResponseEntity<ApiResponse<Void>> handleOwnership(MerchantOwnershipException e) {
        log.warn("Ownership violation: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.RESOURCE_NOT_OWNED.getCode(),
                        ErrorCode.RESOURCE_NOT_OWNED.getMessage()));
    }

    /**
     * 方法安全层（@PreAuthorize）拒绝：403（2026-09-03 死端点修复配套）。
     *
     * <p>无此 handler 时 AccessDeniedException 会落入下方 Exception 兜底
     * 被报成 500 —— 鉴权失败被伪装成服务器内部错误，误导监控与调用方。</p>
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("Access denied by method security: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(), "Access denied"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage()));
    }
}