package org.nexus.gateway.security.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SecurityExceptionHandler 单元测试（Wave 12 XSEC 模块）。
 *
 * <p>覆盖：EncryptionException → HTTP 500；PasswordValidationException → HTTP 400；
 * ThreeDsException → HTTP 400；IdempotencyKeyException → HTTP 400；
 * KeyVersionUnavailableException → HTTP 500；响应体包含 code/message/timestamp。</p>
 */
class SecurityExceptionHandlerTest {

    private final SecurityExceptionHandler handler = new SecurityExceptionHandler();

    @Test
    @DisplayName("EncryptionException 映射为 HTTP 500，错误码 40001")
    void handleEncryptionException_mapsTo500() {
        EncryptionException ex = new EncryptionException("AES-256-GCM encryption failed");

        ResponseEntity<Map<String, Object>> response = handler.handleEncryption(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(500, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("40001", response.getBody().get("code"));
        assertEquals("AES-256-GCM encryption failed", response.getBody().get("message"));
        assertNotNull(response.getBody().get("timestamp"), "timestamp 不应为 null");
    }

    @Test
    @DisplayName("PasswordValidationException 映射为 HTTP 400，错误码 40003")
    void handlePasswordValidationException_mapsTo400() {
        PasswordValidationException ex = new PasswordValidationException("Password mismatch");

        ResponseEntity<Map<String, Object>> response = handler.handlePasswordValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("40003", response.getBody().get("code"));
        assertEquals("Password mismatch", response.getBody().get("message"));
        assertNotNull(response.getBody().get("timestamp"));
    }

    @Test
    @DisplayName("ThreeDsException 映射为 HTTP 400，错误码 40004")
    void handleThreeDsException_mapsTo400() {
        ThreeDsException ex = new ThreeDsException("ACS unavailable");

        ResponseEntity<Map<String, Object>> response = handler.handleThreeDs(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("40004", response.getBody().get("code"));
        assertEquals("ACS unavailable", response.getBody().get("message"));
        assertNotNull(response.getBody().get("timestamp"));
    }

    @Test
    @DisplayName("IdempotencyKeyException 映射为 HTTP 400，错误码 40005（默认）")
    void handleIdempotencyKeyException_defaultCode_mapsTo400() {
        IdempotencyKeyException ex = new IdempotencyKeyException("Duplicate idempotency key");

        ResponseEntity<Map<String, Object>> response = handler.handleIdempotencyKey(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("40005", response.getBody().get("code"));
        assertEquals("Duplicate idempotency key", response.getBody().get("message"));
        assertNotNull(response.getBody().get("timestamp"));
    }

    @Test
    @DisplayName("IdempotencyKeyException 自定义错误码 40109 映射为 HTTP 401")
    void handleIdempotencyKeyException_customCode40109_mapsTo401() {
        IdempotencyKeyException ex = new IdempotencyKeyException("40109", 401, "Nonce too short");

        ResponseEntity<Map<String, Object>> response = handler.handleIdempotencyKey(ex);

        assertEquals(401, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("40109", response.getBody().get("code"));
        assertEquals("Nonce too short", response.getBody().get("message"));
    }

    @Test
    @DisplayName("KeyVersionUnavailableException 映射为 HTTP 503，错误码 40002")
    void handleKeyVersionUnavailableException_mapsTo503() {
        KeyVersionUnavailableException ex = new KeyVersionUnavailableException(3);

        ResponseEntity<Map<String, Object>> response = handler.handleKeyVersionUnavailable(ex);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(503, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("40002", response.getBody().get("code"));
        assertTrue(((String) response.getBody().get("message")).contains("KEK version 3"));
        assertNotNull(response.getBody().get("timestamp"));
    }

    @Test
    @DisplayName("响应体 JSON 包含 code、message、timestamp 三个字段")
    void buildResponse_containsAllFields() {
        EncryptionException ex = new EncryptionException("test error");

        ResponseEntity<Map<String, Object>> response = handler.handleEncryption(ex);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("code"), "应包含 code 字段");
        assertTrue(response.getBody().containsKey("message"), "应包含 message 字段");
        assertTrue(response.getBody().containsKey("timestamp"), "应包含 timestamp 字段");
    }

    @Test
    @DisplayName("EncryptionException 带 cause 时响应仍正确")
    void handleEncryptionException_withCause_mapsCorrectly() {
        EncryptionException ex = new EncryptionException("Decryption failed", new RuntimeException("root cause"));

        ResponseEntity<Map<String, Object>> response = handler.handleEncryption(ex);

        assertEquals(500, response.getStatusCode().value());
        assertEquals("40001", response.getBody().get("code"));
        assertEquals("Decryption failed", response.getBody().get("message"));
    }
}