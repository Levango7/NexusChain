package org.nexus.gateway.security.exception;

/**
 * 幂等性键异常。涵盖键格式无效、键长度不足、键重复使用等场景。
 *
 * <p>默认错误码 40005，HTTP 400。Wave 12 扩展支持自定义错误码：
 * <ul>
 *   <li>40005 — 通用幂等性键异常（默认）</li>
 *   <li>40109 — NONCE_TOO_SHORT</li>
 *   <li>40110 — IDEMPOTENCY_KEY_TOO_SHORT</li>
 *   <li>40111 — IDEMPOTENCY_KEY_INVALID_FORMAT</li>
 * </ul></p>
 *
 * <p>设计依据：Wave 12 设计文档 §4.5 错误码汇总、§8.3.1。</p>
 */
public class IdempotencyKeyException extends SecurityException {

    public IdempotencyKeyException(String message) {
        super("40005", 400, message);
    }

    public IdempotencyKeyException(String message, Throwable cause) {
        super("40005", 400, message, cause);
    }

    /**
     * 自定义错误码构造器（Wave 12 扩展）。
     *
     * @param errorCode 错误码（如 40109/40110/40111）
     * @param httpStatus HTTP 状态码
     * @param message 异常消息
     */
    public IdempotencyKeyException(String errorCode, int httpStatus, String message) {
        super(errorCode, httpStatus, message);
    }

    /**
     * 自定义错误码构造器（带 cause）。
     *
     * @param errorCode 错误码
     * @param httpStatus HTTP 状态码
     * @param message 异常消息
     * @param cause 原始异常
     */
    public IdempotencyKeyException(String errorCode, int httpStatus, String message, Throwable cause) {
        super(errorCode, httpStatus, message, cause);
    }
}