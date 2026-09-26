package org.nexus.gateway.security.exception;

/**
 * 支付密码验证异常。涵盖密码不匹配、密码过期、密码锁定等场景。
 *
 * <p>错误码 40003，HTTP 400。设计依据：Wave 12 设计文档 §8.3.1。</p>
 */
public class PasswordValidationException extends SecurityException {

    public PasswordValidationException(String message) {
        super("40003", 400, message);
    }

    public PasswordValidationException(String message, Throwable cause) {
        super("40003", 400, message, cause);
    }
}