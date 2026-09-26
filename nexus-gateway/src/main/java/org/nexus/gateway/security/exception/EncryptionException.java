package org.nexus.gateway.security.exception;

/**
 * 加密/解密操作异常。涵盖 AES-256-GCM 加密失败、解密认证标签验证失败等场景。
 *
 * <p>错误码 40001，HTTP 500。设计依据：Wave 12 设计文档 §8.3.1。</p>
 */
public class EncryptionException extends SecurityException {

    public EncryptionException(String message) {
        super("40001", 500, message);
    }

    public EncryptionException(String message, Throwable cause) {
        super("40001", 500, message, cause);
    }
}