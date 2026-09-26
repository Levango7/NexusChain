package org.nexus.gateway.security.exception;

/**
 * 3D Secure 认证异常。涵盖 ACS 不可用、认证失败、挑战超时等场景。
 *
 * <p>错误码 40004，HTTP 400。设计依据：Wave 12 设计文档 §8.2.1、§8.3.1。</p>
 */
public class ThreeDsException extends SecurityException {

    public ThreeDsException(String message) {
        super("40004", 400, message);
    }

    public ThreeDsException(String message, Throwable cause) {
        super("40004", 400, message, cause);
    }
}