package org.nexus.oracle.governance.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ErrorMessageSanitizer} 单元测试（GOV-P2-01）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>文件路径脱敏（Windows / Unix 路径）</li>
 *   <li>密钥 / token 脱敏</li>
 *   <li>长度限制（500 字符截断）</li>
 *   <li>null 输入处理</li>
 *   <li>Throwable 重载</li>
 * </ul>
 */
class ErrorMessageSanitizerTest {

    // ---------- 文件路径脱敏 ----------

    @Test
    void sanitize_windowsFilePath_shouldBeRedacted() {
        String input = "Failed to read file: C:\\Users\\admin\\keys\\private.pem";
        String result = ErrorMessageSanitizer.sanitizeErrorMessage(input);
        assertTrue(result.contains("[PATH]"), "Windows path should be replaced with [PATH]: " + result);
        assertFalse(result.contains("C:\\Users"), "Original path should not appear: " + result);
    }

    @Test
    void sanitize_unixFilePath_shouldBeRedacted() {
        String input = "Config not found: /etc/nexus/config.yml";
        String result = ErrorMessageSanitizer.sanitizeErrorMessage(input);
        assertTrue(result.contains("[PATH]"), "Unix path should be replaced with [PATH]: " + result);
    }

    @Test
    void sanitize_relativeFilePath_shouldBeRedacted() {
        String input = "Cannot load: ./config/key.pem";
        String result = ErrorMessageSanitizer.sanitizeErrorMessage(input);
        assertTrue(result.contains("[PATH]"), "Relative path should be replaced with [PATH]: " + result);
    }

    // ---------- 密钥 / token 脱敏 ----------

    @Test
    void sanitize_keyAssignment_shouldBeRedacted() {
        String input = "Error: key=ABC123XYZ";
        String result = ErrorMessageSanitizer.sanitizeErrorMessage(input);
        assertTrue(result.contains("[REDACTED]"), "Key assignment should be replaced: " + result);
        assertFalse(result.contains("ABC123XYZ"), "Key value should not appear: " + result);
    }

    @Test
    void sanitize_tokenAssignment_shouldBeRedacted() {
        String input = "Auth failed: token: my-secret-token";
        String result = ErrorMessageSanitizer.sanitizeErrorMessage(input);
        assertTrue(result.contains("[REDACTED]"), "Token should be replaced: " + result);
    }

    @Test
    void sanitize_passwordAssignment_shouldBeRedacted() {
        String input = "Connection failed: password=mypassword123";
        String result = ErrorMessageSanitizer.sanitizeErrorMessage(input);
        assertTrue(result.contains("[REDACTED]"), "Password should be replaced: " + result);
    }

    @Test
    void sanitize_bearerToken_shouldBeRedacted() {
        String input = "Request failed: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc123";
        String result = ErrorMessageSanitizer.sanitizeErrorMessage(input);
        assertTrue(result.contains("[REDACTED]"), "Bearer token should be replaced: " + result);
    }

    @Test
    void sanitize_jwtToken_shouldBeRedacted() {
        String input = "Invalid JWT: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String result = ErrorMessageSanitizer.sanitizeErrorMessage(input);
        assertTrue(result.contains("[REDACTED]"), "JWT should be replaced: " + result);
    }

    @Test
    void sanitize_apiKey_shouldBeRedacted() {
        String input = "API error: api_key=sk-1234567890abcdef";
        String result = ErrorMessageSanitizer.sanitizeErrorMessage(input);
        assertTrue(result.contains("[REDACTED]"), "API key should be replaced: " + result);
    }

    // ---------- 长度限制 ----------

    @Test
    void sanitize_longMessage_shouldBeTruncated() {
        String input = "x".repeat(1000);
        String result = ErrorMessageSanitizer.sanitizeErrorMessage(input);
        assertTrue(result.length() <= ErrorMessageSanitizer.MAX_LENGTH,
                "Result should not exceed MAX_LENGTH: " + result.length());
        assertTrue(result.contains("[TRUNCATED]"), "Should contain truncation marker: " + result);
    }

    @Test
    void sanitize_exactMaxLength_shouldNotBeTruncated() {
        String input = "x".repeat(ErrorMessageSanitizer.MAX_LENGTH);
        String result = ErrorMessageSanitizer.sanitizeErrorMessage(input);
        assertEquals(ErrorMessageSanitizer.MAX_LENGTH, result.length(),
                "Exact MAX_LENGTH should not be truncated");
        assertFalse(result.contains("[TRUNCATED]"), "Should not contain truncation marker");
    }

    @Test
    void sanitize_shortMessage_shouldNotBeTruncated() {
        String input = "Short error message";
        String result = ErrorMessageSanitizer.sanitizeErrorMessage(input);
        assertEquals(input, result);
        assertFalse(result.contains("[TRUNCATED]"));
    }

    // ---------- null 处理 ----------

    @Test
    void sanitize_nullString_shouldReturnNullLiteral() {
        String result = ErrorMessageSanitizer.sanitizeErrorMessage((String) null);
        assertEquals("null", result);
    }

    @Test
    void sanitize_nullThrowable_shouldReturnNullLiteral() {
        String result = ErrorMessageSanitizer.sanitizeErrorMessage((Throwable) null);
        assertEquals("null", result);
    }

    // ---------- Throwable 重载 ----------

    @Test
    void sanitize_throwable_shouldExtractAndSanitizeMessage() {
        Throwable t = new RuntimeException("Error: key=secret123");
        String result = ErrorMessageSanitizer.sanitizeErrorMessage(t);
        assertNotNull(result);
        assertTrue(result.contains("[REDACTED]"), "Throwable message should be sanitized: " + result);
    }

    @Test
    void sanitize_throwableWithNullMessage_shouldReturnNullLiteral() {
        Throwable t = new RuntimeException();
        String result = ErrorMessageSanitizer.sanitizeErrorMessage(t);
        assertEquals("null", result);
    }

    // ---------- 组合脱敏 ----------

    @Test
    void sanitize_multipleSensitiveData_shouldRedactAll() {
        String input = "Failed: key=secret123 at C:\\config\\app.properties, token: abc456";
        String result = ErrorMessageSanitizer.sanitizeErrorMessage(input);
        assertTrue(result.contains("[REDACTED]"), "Key should be redacted: " + result);
        assertTrue(result.contains("[PATH]"), "Path should be redacted: " + result);
        assertFalse(result.contains("secret123"), "Secret should not appear: " + result);
        assertFalse(result.contains("abc456"), "Token should not appear: " + result);
    }

    @Test
    void sanitize_normalErrorMessage_shouldPassThrough() {
        String input = "Insufficient treasury balance: required=1000, available=100";
        String result = ErrorMessageSanitizer.sanitizeErrorMessage(input);
        assertEquals(input, result);
    }
}