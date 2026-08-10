package org.nexus.gateway.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link LogSanitizer} 单元测试：覆盖 mask / maskKey 的边界与正常路径。
 */
class LogSanitizerTest {

    @Test
    @DisplayName("mask: null 返回 'null'")
    void mask_null() {
        assertEquals("null", LogSanitizer.mask(null));
    }

    @Test
    @DisplayName("mask: 短串（<=8）整体遮蔽为 ****")
    void mask_short() {
        assertEquals("****", LogSanitizer.mask("abc"));
        assertEquals("****", LogSanitizer.mask("12345678"));
    }

    @Test
    @DisplayName("mask: 长串保留首尾 4 位，中间遮蔽")
    void mask_long() {
        assertEquals("abcd****wxyz", LogSanitizer.mask("abcdefghiwxyz"));
    }

    @Test
    @DisplayName("maskKey: null 返回 'null'")
    void maskKey_null() {
        assertEquals("null", LogSanitizer.maskKey(null));
    }

    @Test
    @DisplayName("maskKey: 任意非空 key 完全遮蔽并标注长度")
    void maskKey_value() {
        String key = "0123456789abcdef";
        assertEquals("[REDACTED:" + key.length() + " chars]", LogSanitizer.maskKey(key));
    }
}