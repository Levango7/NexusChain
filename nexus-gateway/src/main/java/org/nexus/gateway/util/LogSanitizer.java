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
}