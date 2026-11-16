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

    // ==================== stripControlChars（B-13 日志伪造防御回归） ====================

    @Test
    @DisplayName("stripControlChars: CRLF 被转成可见 \\r\\n —— 无法再伪造新日志行")
    void stripControlChars_crlfCannotForgeLogLine() {
        String attack = "ok\r\n2026-11-16 00:00:00 ERROR [nexus] forged audit line";
        String sanitized = LogSanitizer.stripControlChars(attack);
        // 关键判据：结果里不得再有真 CR / 真 LF（否则写进日志就是两行）
        org.junit.jupiter.api.Assertions.assertFalse(sanitized.contains("\r"),
                "中和后不得残留真实 CR（否则可伪造日志行）");
        org.junit.jupiter.api.Assertions.assertFalse(sanitized.contains("\n"),
                "中和后不得残留真实 LF（否则可伪造日志行）");
        org.junit.jupiter.api.Assertions.assertTrue(sanitized.contains("\\r"), "CR 应转成 \\r");
        org.junit.jupiter.api.Assertions.assertTrue(sanitized.contains("\\n"), "LF 应转成 \\n");
        // 攻击内容本身仍可读（便于排查），只是不再有换行
        org.junit.jupiter.api.Assertions.assertTrue(sanitized.contains("forged audit line"));
    }

    @Test
    @DisplayName("stripControlChars: NUL/BEL/ESC 等 C0 控制字符与 DEL 全部 \\uXXXX 化")
    void stripControlChars_controlCharsAreEscaped() {
        String attack = "a\u0000b\u0007c\u001b[31md\u007f";
        assertEquals("a\\u0000b\\u0007c\\u001b[31md\\u007f", LogSanitizer.stripControlChars(attack));
    }

    @Test
    @DisplayName("stripControlChars: 超长明细截断到上限并标注（防超大载荷灌爆日志）")
    void stripControlChars_truncatesLongInput() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) { sb.append('x'); }
        String sanitized = LogSanitizer.stripControlChars(sb.toString());
        org.junit.jupiter.api.Assertions.assertTrue(sanitized.length() < 600,
                "超长明细必须截断（实测长度 " + sanitized.length() + "）");
        org.junit.jupiter.api.Assertions.assertTrue(sanitized.endsWith("…[truncated]"),
                "截断必须有可见标记，避免被当成完整内容");
    }

    @Test
    @DisplayName("stripControlChars: 普通文本与制表符的可预期形态")
    void stripControlChars_plainTextAndTab() {
        assertEquals("hello world", LogSanitizer.stripControlChars("hello world"));
        assertEquals("a\\tb", LogSanitizer.stripControlChars("a\tb"));
        assertEquals("null", LogSanitizer.stripControlChars(null));
    }
}