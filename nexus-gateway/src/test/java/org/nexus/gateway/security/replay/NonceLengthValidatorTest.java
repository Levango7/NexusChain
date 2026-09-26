package org.nexus.gateway.security.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.security.exception.IdempotencyKeyException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NonceLengthValidator 单元测试（Wave 12 REPLAY 模块）。
 *
 * <p>覆盖：≥16 字节 nonce 通过；<16 字节 nonce 被拒绝（错误码 40109）；
 * null nonce 被拒绝。</p>
 */
class NonceLengthValidatorTest {

    private final NonceLengthValidator validator = new NonceLengthValidator();

    @Test
    @DisplayName("≥16 字节的 nonce 通过校验")
    void validate_validNonce_passes() {
        // 16 字节 ASCII 字符串
        assertDoesNotThrow(() -> validator.validate("0123456789abcdef"));
    }

    @Test
    @DisplayName("恰好 16 字节的 nonce 通过（边界值）")
    void validate_exactly16Bytes_passes() {
        assertDoesNotThrow(() -> validator.validate("aaaaaaaaaaaaaaaa"));
    }

    @Test
    @DisplayName(">16 字节的 nonce 通过")
    void validate_moreThan16Bytes_passes() {
        assertDoesNotThrow(() -> validator.validate("0123456789abcdefghij"));
    }

    @Test
    @DisplayName("<16 字节的 nonce 被拒绝（错误码 40109）")
    void validate_shortNonce_throws40109() {
        IdempotencyKeyException ex = assertThrows(IdempotencyKeyException.class,
                () -> validator.validate("short"));
        assertEquals("40109", ex.getErrorCode(), "错误码应为 40109");
        assertEquals(401, ex.getHttpStatus(), "HTTP 状态码应为 401");
        assertTrue(ex.getMessage().contains("Nonce too short"), "消息应包含 'Nonce too short'");
    }

    @Test
    @DisplayName("15 字节的 nonce 被拒绝（边界值）")
    void validate_exactly15Bytes_throws() {
        IdempotencyKeyException ex = assertThrows(IdempotencyKeyException.class,
                () -> validator.validate("aaaaaaaaaaaaaaa")); // 15 个 'a'
        assertEquals("40109", ex.getErrorCode());
    }

    @Test
    @DisplayName("空字符串 nonce 被拒绝（错误码 40109）")
    void validate_emptyNonce_throws40109() {
        IdempotencyKeyException ex = assertThrows(IdempotencyKeyException.class,
                () -> validator.validate(""));
        assertEquals("40109", ex.getErrorCode());
        assertEquals(401, ex.getHttpStatus());
    }

    @Test
    @DisplayName("null nonce 被拒绝（错误码 40109）")
    void validate_nullNonce_throws40109() {
        IdempotencyKeyException ex = assertThrows(IdempotencyKeyException.class,
                () -> validator.validate(null));
        assertEquals("40109", ex.getErrorCode());
        assertEquals(401, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("null"), "消息应提及 null");
    }

    @Test
    @DisplayName("UTF-8 多字节字符的 nonce 按字节长度校验")
    void validate_utf8MultibyteNonce_calculatedByByteLength() {
        // 8 个中文字符 = 24 UTF-8 字节 (≥16)，应通过
        assertDoesNotThrow(() -> validator.validate("一二三四五六七八"));
        // 5 个中文字符 = 15 UTF-8 字节 (<16)，应拒绝
        IdempotencyKeyException ex = assertThrows(IdempotencyKeyException.class,
                () -> validator.validate("一二三四五"));
        assertEquals("40109", ex.getErrorCode());
    }
}