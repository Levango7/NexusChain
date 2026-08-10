package org.nexus.oracle.governance.execution;

import java.util.regex.Pattern;

/**
 * 异常信息脱敏工具（GOV-P2-01）。
 *
 * <p>对异常信息进行脱敏处理，避免敏感信息（文件路径、密钥、token 等）
 * 泄露到 {@code executionResult} 等持久化字段。完整异常信息仅记录到
 * 日志（DEBUG 级别），脱敏版本用于面向用户的字段。
 *
 * <p>脱敏规则：
 * <ul>
 *   <li>文件路径（Windows / Unix 路径）替换为 {@code [PATH]}</li>
 *   <li>密钥 / token 模式替换为 {@code [REDACTED]}</li>
 *   <li>限制最大长度 500 字符，超出截断并追加 {@code ...[TRUNCATED]}</li>
 * </ul>
 *
 * @since 2.1.0
 */
public final class ErrorMessageSanitizer {

    /** 脱敏后最大长度 */
    public static final int MAX_LENGTH = 500;

    /** 截断后缀 */
    private static final String TRUNCATED_SUFFIX = "...[TRUNCATED]";

    /**
     * 文件路径正则（Windows 盘符路径或 Unix 绝对/相对路径）。
     * 匹配示例：C:\Users\foo, /etc/passwd, ./config/key.pem, F:\Nexus\keys\priv.key
     */
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
            "(?:[A-Za-z]:[\\\\/][^\\s\"'<>|]+)|(?:(?:\\./|\\.\\./|/)[^\\s\"'<>|]+\\.[A-Za-z]{1,10})");

    /**
     * 密钥 / token 模式正则。
     * 匹配示例：key=ABC123..., token: xyz, password="secret", Bearer eyJhb...
     */
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(?:(?:key|token|password|passwd|secret|credential|bearer|api[-_]?key|access[-_]?key|private[-_]?key)"
                    + "\\s*[:=]\\s*\\S+)"
                    + "|(?:Bearer\\s+\\S+)"
                    + "|(?:eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)");

    private ErrorMessageSanitizer() {
        // 工具类，不可实例化
    }

    /**
     * 对异常信息进行脱敏处理（GOV-P2-01）。
     *
     * @param message 原始异常信息（可为 {@code null}）
     * @return 脱敏后的异常信息；{@code null} 输入返回 {@code "null"}
     */
    public static String sanitizeErrorMessage(String message) {
        if (message == null) {
            return "null";
        }
        String sanitized = message;

        // 1. 替换文件路径
        sanitized = FILE_PATH_PATTERN.matcher(sanitized).replaceAll("[PATH]");

        // 2. 替换密钥 / token
        sanitized = SECRET_PATTERN.matcher(sanitized).replaceAll("[REDACTED]");

        // 3. 限制最大长度
        if (sanitized.length() > MAX_LENGTH) {
            sanitized = sanitized.substring(0, MAX_LENGTH - TRUNCATED_SUFFIX.length())
                    + TRUNCATED_SUFFIX;
        }

        return sanitized;
    }

    /**
     * 对 Throwable 的消息进行脱敏处理（GOV-P2-01）。
     *
     * @param throwable 异常对象（可为 {@code null}）
     * @return 脱敏后的异常信息；{@code null} 输入返回 {@code "null"}
     */
    public static String sanitizeErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return "null";
        }
        return sanitizeErrorMessage(throwable.getMessage());
    }
}