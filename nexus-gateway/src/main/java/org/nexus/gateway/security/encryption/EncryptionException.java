package org.nexus.gateway.security.encryption;

/**
 * 加密异常 — 所有加密相关操作的统一异常类型。
 *
 * <p>包含错误码（用于 API 响应映射）和底层异常原因。
 * 来源：设计文档 §8.3.1 异常处理层次。</p>
 */
public class EncryptionException extends RuntimeException {

    private final String errorCode;

    public EncryptionException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public EncryptionException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}