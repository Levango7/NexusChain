package org.nexus.gateway.security.exception;

/**
 * 安全异常基类。所有安全相关异常继承此类，携带错误码和 HTTP 状态码，
 * 由 {@link SecurityExceptionHandler} 统一映射为 HTTP JSON 响应。
 *
 * <p>设计依据：Wave 12 设计文档 §8.3.1 — 自定义异常层次。</p>
 */
public abstract class SecurityException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;

    protected SecurityException(String errorCode, int httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    protected SecurityException(String errorCode, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}