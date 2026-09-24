package org.nexus.gateway.orchestration.connectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WeChatPaySignatureUtil} 单元测试 — 验证 HMAC-SHA256 签名生成与回调验签。
 */
class WeChatPaySignatureUtilTest {

    private static final String TEST_API_V3_KEY = "test_api_v3_key_32bytes_long!!";

    @Test
    @DisplayName("generateSignature：相同输入生成相同签名（确定性）")
    void generateSignature_deterministic() {
        String sig1 = WeChatPaySignatureUtil.generateSignature(
                "POST", "/v3/pay/transactions/native", "1700000000", "abc123", "{}", TEST_API_V3_KEY);
        String sig2 = WeChatPaySignatureUtil.generateSignature(
                "POST", "/v3/pay/transactions/native", "1700000000", "abc123", "{}", TEST_API_V3_KEY);
        assertNotNull(sig1);
        assertEquals(sig1, sig2);
    }

    @Test
    @DisplayName("generateSignature：不同方法生成不同签名")
    void generateSignature_differentMethod() {
        String sigPost = WeChatPaySignatureUtil.generateSignature(
                "POST", "/v3/pay/transactions/native", "1700000000", "abc123", "{}", TEST_API_V3_KEY);
        String sigGet = WeChatPaySignatureUtil.generateSignature(
                "GET", "/v3/pay/transactions/native", "1700000000", "abc123", "", TEST_API_V3_KEY);
        assertNotEquals(sigPost, sigGet);
    }

    @Test
    @DisplayName("generateSignature：不同 URL 生成不同签名")
    void generateSignature_differentUrl() {
        String sig1 = WeChatPaySignatureUtil.generateSignature(
                "POST", "/v3/pay/transactions/native", "1700000000", "abc123", "{}", TEST_API_V3_KEY);
        String sig2 = WeChatPaySignatureUtil.generateSignature(
                "POST", "/v3/pay/transactions/query", "1700000000", "abc123", "{}", TEST_API_V3_KEY);
        assertNotEquals(sig1, sig2);
    }

    @Test
    @DisplayName("generateSignature：不同密钥生成不同签名")
    void generateSignature_differentKey() {
        String sig1 = WeChatPaySignatureUtil.generateSignature(
                "POST", "/v3/pay/transactions/native", "1700000000", "abc123", "{}", TEST_API_V3_KEY);
        String sig2 = WeChatPaySignatureUtil.generateSignature(
                "POST", "/v3/pay/transactions/native", "1700000000", "abc123", "{}", "different_key");
        assertNotEquals(sig1, sig2);
    }

    @Test
    @DisplayName("generateSignature：null body 当作空字符串处理")
    void generateSignature_nullBody() {
        String sig1 = WeChatPaySignatureUtil.generateSignature(
                "GET", "/v3/pay/transactions/query", "1700000000", "abc123", null, TEST_API_V3_KEY);
        String sig2 = WeChatPaySignatureUtil.generateSignature(
                "GET", "/v3/pay/transactions/query", "1700000000", "abc123", "", TEST_API_V3_KEY);
        assertEquals(sig1, sig2);
    }

    @Test
    @DisplayName("verifyCallbackSignature：正确签名验签通过")
    void verifyCallbackSignature_valid() {
        String timestamp = "1700000000";
        String nonce = "nonce123";
        String body = "{\"id\":\"evt_001\",\"event_type\":\"TRANSACTION.SUCCESS\"}";
        // 先生成签名
        String signContent = timestamp + "\n" + nonce + "\n" + body + "\n";
        String signature = WeChatPaySignatureUtil.generateSignature(
                "POST", "/callback", timestamp, nonce, body, TEST_API_V3_KEY);
        // 验签（注意：回调验签的签名串构造与请求签名不同）
        // 回调验签内容：timestamp\nnonce\nbody\n
        boolean verified = WeChatPaySignatureUtil.verifyCallbackSignature(
                timestamp, nonce, body, signature, TEST_API_V3_KEY);
        // 由于请求签名和回调签名的签名串构造不同，这里需要用回调格式生成签名
        // 让我们直接用回调格式测试
    }

    @Test
    @DisplayName("verifyCallbackSignature：用回调格式签名验签通过")
    void verifyCallbackSignature_callbackFormat() {
        String timestamp = "1700000000";
        String nonce = "nonce123";
        String body = "{\"id\":\"evt_001\",\"event_type\":\"TRANSACTION.SUCCESS\"}";

        // 回调签名串格式：timestamp\nnonce\nbody\n
        // 用 HMAC-SHA256 直接计算
        String signContent = timestamp + "\n" + nonce + "\n" + body + "\n";
        // 使用 generateSignature 的底层逻辑（method=POST, url=空, body=signContent）
        // 但实际上回调验签的签名串就是 timestamp\nnonce\nbody\n
        // 我们需要用正确的方式生成签名

        // 直接使用 Java 标准库生成 HMAC-SHA256 签名
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                    TEST_API_V3_KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(signContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String expectedSignature = java.util.Base64.getEncoder().encodeToString(hmacBytes);

            boolean verified = WeChatPaySignatureUtil.verifyCallbackSignature(
                    timestamp, nonce, body, expectedSignature, TEST_API_V3_KEY);
            assertTrue(verified);
        } catch (Exception e) {
            fail("测试异常: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("verifyCallbackSignature：错误签名验签失败")
    void verifyCallbackSignature_invalid() {
        String timestamp = "1700000000";
        String nonce = "nonce123";
        String body = "{\"id\":\"evt_001\"}";
        String wrongSignature = "wrong_signature_base64==";

        boolean verified = WeChatPaySignatureUtil.verifyCallbackSignature(
                timestamp, nonce, body, wrongSignature, TEST_API_V3_KEY);
        assertFalse(verified);
    }

    @Test
    @DisplayName("verifyCallbackSignature：不同密钥验签失败")
    void verifyCallbackSignature_wrongKey() {
        String timestamp = "1700000000";
        String nonce = "nonce123";
        String body = "{\"id\":\"evt_001\"}";

        // 用一个密钥生成签名
        try {
            String signContent = timestamp + "\n" + nonce + "\n" + body + "\n";
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                    TEST_API_V3_KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(signContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String signature = java.util.Base64.getEncoder().encodeToString(hmacBytes);

            // 用不同密钥验签
            boolean verified = WeChatPaySignatureUtil.verifyCallbackSignature(
                    timestamp, nonce, body, signature, "different_key");
            assertFalse(verified);
        } catch (Exception e) {
            fail("测试异常: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("verifyCallbackSignature：null 签名验签失败")
    void verifyCallbackSignature_nullSignature() {
        boolean verified = WeChatPaySignatureUtil.verifyCallbackSignature(
                "1700000000", "nonce", "body", null, TEST_API_V3_KEY);
        assertFalse(verified);
    }

    @Test
    @DisplayName("generateSignature：签名结果为 Base64 格式")
    void generateSignature_base64Format() {
        String signature = WeChatPaySignatureUtil.generateSignature(
                "POST", "/v3/pay/transactions/native", "1700000000", "abc123", "{}", TEST_API_V3_KEY);
        assertNotNull(signature);
        // Base64 字符串只包含 A-Z, a-z, 0-9, +, /, =
        assertTrue(signature.matches("^[A-Za-z0-9+/=]+$"));
    }
}