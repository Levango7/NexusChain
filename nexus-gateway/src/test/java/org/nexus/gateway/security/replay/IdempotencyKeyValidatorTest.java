package org.nexus.gateway.security.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.security.exception.IdempotencyKeyException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IdempotencyKeyValidator 单元测试（Wave 12 REPLAY 模块）。
 *
 * <p>覆盖：≥8 字节合法键通过；<8 字节键被拒绝（错误码 40110）；
 * 非法字符键被拒绝（错误码 40111）；null 键被拒绝。</p>
 */
class IdempotencyKeyValidatorTest {

    private final IdempotencyKeyValidator validator = new IdempotencyKeyValidator();

    @Test
    @DisplayName("≥8 字节的合法键通过校验")
    void validate_validKey_passes() {
        assertDoesNotThrow(() -> validator.validate("order-01"));
    }

    @Test
    @DisplayName("恰好 8 字节的合法键通过（边界值）")
    void validate_exactly8Bytes_passes() {
        assertDoesNotThrow(() -> validator.validate("key12345"));
    }

    @Test
    @DisplayName("包含连字符的合法键通过")
    void validate_keyWithHyphen_passes() {
        assertDoesNotThrow(() -> validator.validate("order-id-2026"));
    }

    @Test
    @DisplayName(">8 字节的合法键通过")
    void validate_moreThan8Bytes_passes() {
        assertDoesNotThrow(() -> validator.validate("idempotency-key-20260926"));
    }

    @Test
    @DisplayName("<8 字节的键被拒绝（错误码 40110）")
    void validate_shortKey_throws40110() {
        IdempotencyKeyException ex = assertThrows(IdempotencyKeyException.class,
                () -> validator.validate("short"));
        assertEquals("40110", ex.getErrorCode(), "错误码应为 40110");
        assertEquals(401, ex.getHttpStatus(), "HTTP 状态码应为 401");
        assertTrue(ex.getMessage().contains("too short"), "消息应包含 'too short'");
    }

    @Test
    @DisplayName("恰好 7 字节的键被拒绝（边界值）")
    void validate_exactly7Bytes_throws() {
        IdempotencyKeyException ex = assertThrows(IdempotencyKeyException.class,
                () -> validator.validate("key1234")); // 7 字节
        assertEquals("40110", ex.getErrorCode());
    }

    @Test
    @DisplayName("空字符串键被拒绝（错误码 40110）")
    void validate_emptyKey_throws40110() {
        IdempotencyKeyException ex = assertThrows(IdempotencyKeyException.class,
                () -> validator.validate(""));
        assertEquals("40110", ex.getErrorCode());
    }

    @Test
    @DisplayName("null 键被拒绝（错误码 40110）")
    void validate_nullKey_throws40110() {
        IdempotencyKeyException ex = assertThrows(IdempotencyKeyException.class,
                () -> validator.validate(null));
        assertEquals("40110", ex.getErrorCode());
        assertEquals(401, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("null"), "消息应提及 null");
    }

    @Test
    @DisplayName("包含空格的键被拒绝（错误码 40111 — 非法格式）")
    void validate_keyWithSpace_throws40111() {
        IdempotencyKeyException ex = assertThrows(IdempotencyKeyException.class,
                () -> validator.validate("key with space"));
        assertEquals("40111", ex.getErrorCode(), "错误码应为 40111");
        assertEquals(401, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("invalid format"), "消息应包含 'invalid format'");
    }

    @Test
    @DisplayName("包含下划线的键被拒绝（错误码 40111 — 非法格式）")
    void validate_keyWithUnderscore_throws40111() {
        IdempotencyKeyException ex = assertThrows(IdempotencyKeyException.class,
                () -> validator.validate("order_id_2026"));
        assertEquals("40111", ex.getErrorCode());
    }

    @Test
    @DisplayName("包含特殊字符的键被拒绝（错误码 40111 — 非法格式）")
    void validate_keyWithSpecialChars_throws40111() {
        IdempotencyKeyException ex = assertThrows(IdempotencyKeyException.class,
                () -> validator.validate("order@id!2026"));
        assertEquals("40111", ex.getErrorCode());
    }

    @Test
    @DisplayName("长度足够但格式非法的键优先报 40110 还是 40111 — 长度校验先于格式校验")
    void validate_shortAndInvalidFormat_throws40110First() {
        // 5 字节且含非法字符 → 长度校验先触发（40110）
        IdempotencyKeyException ex = assertThrows(IdempotencyKeyException.class,
                () -> validator.validate("a@b#c"));
        assertEquals("40110", ex.getErrorCode(), "长度不足时应先报 40110");
    }

    @Test
    @DisplayName("纯数字键通过校验")
    void validate_numericKey_passes() {
        assertDoesNotThrow(() -> validator.validate("12345678"));
    }

    @Test
    @DisplayName("纯字母键通过校验")
    void validate_alphaKey_passes() {
        assertDoesNotThrow(() -> validator.validate("abcdefgh"));
    }
}