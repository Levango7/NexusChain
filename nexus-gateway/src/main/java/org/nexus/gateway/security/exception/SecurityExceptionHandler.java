package org.nexus.gateway.security.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 安全异常全局处理器。将所有 {@link SecurityException} 子类统一映射为
 * HTTP 状态码和错误码 JSON 响应，确保客户端收到结构化的错误信息。
 *
 * <p>设计依据：Wave 12 设计文档 §8.3.2 — 全局异常处理。</p>
 */
@RestControllerAdvice
public class SecurityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityExceptionHandler.class);

    @ExceptionHandler(EncryptionException.class)
    public ResponseEntity<Map<String, Object>> handleEncryption(EncryptionException e) {
        log.warn("Encryption exception: code={}, message={}", e.getErrorCode(), e.getMessage());
        return buildResponse(e);
    }

    @ExceptionHandler(KeyVersionUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleKeyVersionUnavailable(KeyVersionUnavailableException e) {
        log.warn("Key version unavailable: code={}, kekVersion={}, message={}",
                e.getErrorCode(), e.getKekVersion(), e.getMessage());
        return buildResponse(e);
    }

    @ExceptionHandler(PasswordValidationException.class)
    public ResponseEntity<Map<String, Object>> handlePasswordValidation(PasswordValidationException e) {
        log.warn("Password validation exception: code={}, message={}", e.getErrorCode(), e.getMessage());
        return buildResponse(e);
    }

    @ExceptionHandler(ThreeDsException.class)
    public ResponseEntity<Map<String, Object>> handleThreeDs(ThreeDsException e) {
        log.warn("3DS exception: code={}, message={}", e.getErrorCode(), e.getMessage());
        return buildResponse(e);
    }

    @ExceptionHandler(IdempotencyKeyException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyKey(IdempotencyKeyException e) {
        log.warn("Idempotency key exception: code={}, message={}", e.getErrorCode(), e.getMessage());
        return buildResponse(e);
    }

    /**
     * 构建统一的错误响应 JSON，包含错误码、消息和时间戳。
     */
    private ResponseEntity<Map<String, Object>> buildResponse(SecurityException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.getErrorCode());
        body.put("message", e.getMessage());
        body.put("timestamp", Instant.now().toString());
        HttpStatus status;
        try {
            status = HttpStatus.valueOf(e.getHttpStatus());
        } catch (IllegalArgumentException ex) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity.status(status).body(body);
    }
}