package org.nexus.gateway.logging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SensitiveDataFilter} 单元测试（任务 #28）。
 *
 * <p>测试各种敏感字段的脱敏处理，包括 JSON 格式和 key=value 格式。</p>
 */
class SensitiveDataFilterTest {

    private SensitiveDataFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SensitiveDataFilter();
        filter.start();
    }

    // === JSON 格式脱敏测试 ===

    @Test
    @DisplayName("JSON 格式 - password 字段脱敏（长值：前4+****+后4）")
    void testJsonPasswordLongValue() {
        String input = "{\"password\":\"sk_live_abcdef1234567890\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("\"password\":\"sk_l****7890\""), "应保留前4位+****+后4位");
        assertFalse(result.contains("abcdef1234567890"), "不应包含原始值");
    }

    @Test
    @DisplayName("JSON 格式 - password 字段脱敏（短值：全部****）")
    void testJsonPasswordShortValue() {
        String input = "{\"password\":\"short\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("\"password\":\"****\""), "短值应全部替换为****");
    }

    @Test
    @DisplayName("JSON 格式 - apiKey 字段脱敏")
    void testJsonApiKey() {
        String input = "{\"apiKey\":\"sk_live_abcdef1234567890\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("\"apiKey\":\"sk_l****7890\""), "apiKey 应被脱敏");
    }

    @Test
    @DisplayName("JSON 格式 - api_key 字段脱敏（下划线命名）")
    void testJsonApiUnderscoreKey() {
        String input = "{\"api_key\":\"sk_live_abcdef1234567890\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("\"api_key\":\"sk_l****7890\""), "api_key 应被脱敏");
    }

    @Test
    @DisplayName("JSON 格式 - token 字段脱敏")
    void testJsonToken() {
        String input = "{\"token\":\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.signature\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("****"), "token 应被脱敏");
        assertFalse(result.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"), "不应包含原始 token");
    }

    @Test
    @DisplayName("JSON 格式 - secret 字段脱敏")
    void testJsonSecret() {
        String input = "{\"secret\":\"my_secret_value_12345\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("****"), "secret 应被脱敏");
    }

    @Test
    @DisplayName("JSON 格式 - 多字段同时脱敏")
    void testJsonMultipleFields() {
        String input = "{\"password\":\"sk_live_abcdef1234567890\",\"apiKey\":\"pk_live_abcdef1234567890\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("\"password\":\"sk_l****7890\""), "password 应被脱敏");
        assertTrue(result.contains("\"apiKey\":\"pk_l****7890\""), "apiKey 应被脱敏");
    }

    @Test
    @DisplayName("JSON 格式 - 带空格的 key:value 格式")
    void testJsonWithSpaces() {
        String input = "{\"password\": \"sk_live_abcdef1234567890\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("\"password\":\"sk_l****7890\""), "带空格的 JSON 也应正确脱敏");
    }

    @Test
    @DisplayName("JSON 格式 - privateKey 字段脱敏")
    void testJsonPrivateKey() {
        String input = "{\"privateKey\":\"-----BEGIN PRIVATE KEY-----abcdef1234567890-----END-----\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("****"), "privateKey 应被脱敏");
    }

    @Test
    @DisplayName("JSON 格式 - hmac 字段脱敏")
    void testJsonHmac() {
        String input = "{\"hmac\":\"hmac_value_abcdef1234567890\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("****"), "hmac 应被脱敏");
    }

    @Test
    @DisplayName("JSON 格式 - signature 字段脱敏")
    void testJsonSignature() {
        String input = "{\"signature\":\"sig_value_abcdef1234567890\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("****"), "signature 应被脱敏");
    }

    @Test
    @DisplayName("JSON 格式 - mchId 字段脱敏")
    void testJsonMchId() {
        String input = "{\"mchId\":\"merchant_id_abcdef1234567890\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("****"), "mchId 应被脱敏");
    }

    @Test
    @DisplayName("JSON 格式 - appId 字段脱敏")
    void testJsonAppId() {
        String input = "{\"appId\":\"app_id_abcdef1234567890\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("****"), "appId 应被脱敏");
    }

    // === key=value 格式脱敏测试 ===

    @Test
    @DisplayName("key=value 格式 - password 字段脱敏")
    void testKvPassword() {
        String input = "password=sk_live_abcdef1234567890";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("password=sk_l****7890"), "key=value 格式也应脱敏");
    }

    @Test
    @DisplayName("key=value 格式 - apiKey 字段脱敏")
    void testKvApiKey() {
        String input = "apiKey=sk_live_abcdef1234567890";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("apiKey=sk_l****7890"), "key=value 格式也应脱敏");
    }

    @Test
    @DisplayName("key=value 格式 - 短值全部替换为****")
    void testKvShortValue() {
        String input = "token=abc";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("token=****"), "短值应全部替换为****");
    }

    @Test
    @DisplayName("key=value 格式 - 混在日志消息中")
    void testKvInLogMessage() {
        String input = "Processing payment with apiKey=sk_live_abcdef1234567890 for order 123";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("apiKey=sk_l****7890"), "日志消息中的 key=value 也应脱敏");
        assertTrue(result.contains("for order 123"), "非敏感部分应保留");
    }

    // === 边界条件测试 ===

    @Test
    @DisplayName("非敏感字段不脱敏")
    void testNonSensitiveField() {
        String input = "{\"orderId\":\"ORD123456789\",\"amount\":\"100.00\"}";
        String result = filter.maskSensitiveData(input);
        assertEquals(input, result, "非敏感字段不应被修改");
    }

    @Test
    @DisplayName("空消息不处理")
    void testEmptyMessage() {
        assertEquals("", filter.maskSensitiveData(""));
        assertNull(filter.maskSensitiveData(null));
    }

    @Test
    @DisplayName("恰好12字符的值全部替换为****")
    void testExactly12Chars() {
        String input = "{\"password\":\"123456789012\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("\"password\":\"****\""), "12字符的值应全部替换为****");
    }

    @Test
    @DisplayName("恰好13字符的值保留前4+****+后4")
    void testExactly13Chars() {
        String input = "{\"password\":\"1234567890123\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("\"password\":\"1234****0123\""), "13字符的值应保留前4+****+后4");
    }

    @Test
    @DisplayName("自定义敏感字段列表")
    void testCustomFields() {
        filter.setFields(List.of("customField"));
        filter.start();
        String input = "{\"customField\":\"sk_live_abcdef1234567890\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("****"), "自定义字段也应脱敏");
    }

    @Test
    @DisplayName("自定义脱敏替换字符")
    void testCustomMaskPattern() {
        filter.setMaskPattern("XXXX");
        filter.start();
        String input = "{\"password\":\"short\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("\"password\":\"XXXX\""), "应使用自定义替换字符");
    }

    @Test
    @DisplayName("大小写不敏感匹配")
    void testCaseInsensitive() {
        String input = "{\"Password\":\"sk_live_abcdef1234567890\"}";
        String result = filter.maskSensitiveData(input);
        assertTrue(result.contains("****"), "大小写不敏感匹配应生效");
    }
}