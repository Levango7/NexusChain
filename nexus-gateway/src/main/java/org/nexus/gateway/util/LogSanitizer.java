package org.nexus.gateway.util;

/**
 * Utility for sanitizing sensitive data in log output.
 * Prevents private keys, signatures, and secrets from appearing in logs.
 */
public final class LogSanitizer {

    private LogSanitizer() {}

    /**
     * Mask a sensitive string, showing only first 4 and last 4 characters.
     */
    public static String mask(String value) {
        if (value == null) return "null";
        if (value.length() <= 8) return "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    /**
     * Mask a private key or signature completely.
     */
    public static String maskKey(String key) {
        if (key == null) return "null";
        return "[REDACTED:" + key.length() + " chars]";
    }

    /** 单条明细的最大记录长度（防止攻击者用超大载荷灌爆日志行）。 */
    private static final int MAX_DETAIL_CHARS = 512;

    /**
     * 中和控制字符，用于把<b>可能含攻击载荷</b>的明细写进日志。
     *
     * <p>动机（B-13 攻击载荷电池实测暴露）：请求体里的 {@code \r\n}、{@code \n}
     * 会让异常明细被写进日志时<b>伪造出新的日志行/告警行</b>（log forging），
     * 污染审计与告警检索；控制字符（含 {@code \u001b} ANSI）还会破坏终端与
     * 日志采集器。本方法把它们换成可见转义，并截断超长明细。</p>
     *
     * <p>注意：本方法只治「写进日志」这一跳；响应体回显由调用方另行决定。</p>
     */
    public static String stripControlChars(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder(Math.min(value.length(), MAX_DETAIL_CHARS + 16));
        for (int i = 0; i < value.length() && sb.length() <= MAX_DETAIL_CHARS; i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\r': sb.append("\\r"); break;
                case '\n': sb.append("\\n"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        if (sb.length() > MAX_DETAIL_CHARS) {
            sb.setLength(MAX_DETAIL_CHARS);
            sb.append("…[truncated]");
        }
        return sb.toString();
    }
}