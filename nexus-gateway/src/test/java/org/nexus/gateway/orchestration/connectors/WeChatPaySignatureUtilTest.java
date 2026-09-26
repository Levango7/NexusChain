package org.nexus.gateway.orchestration.connectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WeChatPaySignatureUtil} 单元测试 — 覆盖 RSA-SHA256 签名/验签、AES-256-GCM 解密、
 * 以及旧方法（HMAC-SHA256）向后兼容性。
 *
 * <p>Wave 13 Task 8：签名验证场景测试。测试密钥对在 {@link BeforeEach} 中动态生成，
 * 不依赖外部密钥文件。</p>
 */
class WeChatPaySignatureUtilTest {

    private static final String TEST_API_V3_KEY = "test_api_v3_key_32bytes_long!!";

    private String privateKeyBase64;
    private String publicKeyBase64;

    @BeforeEach
    void generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();
        privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    // ==================== RSA-SHA256 请求签名 ====================

    @Test
    @DisplayName("generateRsaSignature：使用 RSA 私钥生成签名，结果非空且为 Base64 格式")
    void generateRsaSignature_valid() {
        String signature = WeChatPaySignatureUtil.generateRsaSignature(
                "POST", "/v3/pay/transactions/native", "1700000000", "abc123", "{}", privateKeyBase64);

        assertNotNull(signature);
        assertFalse(signature.isEmpty());
        // Base64 字符串只包含 A-Z, a-z, 0-9, +, /, =
        assertTrue(signature.matches("^[A-Za-z0-9+/=]+$"));
    }

    @Test
    @DisplayName("generateRsaSignature：相同输入生成相同签名（确定性）")
    void generateRsaSignature_deterministic() {
        String sig1 = WeChatPaySignatureUtil.generateRsaSignature(
                "POST", "/v3/pay/transactions/native", "1700000000", "abc123", "{}", privateKeyBase64);
        String sig2 = WeChatPaySignatureUtil.generateRsaSignature(
                "POST", "/v3/pay/transactions/native", "1700000000", "abc123", "{}", privateKeyBase64);
        assertEquals(sig1, sig2);
    }

    @Test
    @DisplayName("generateRsaSignature：不同方法生成不同签名")
    void generateRsaSignature_differentMethod() {
        String sigPost = WeChatPaySignatureUtil.generateRsaSignature(
                "POST", "/v3/pay/transactions/native", "1700000000", "abc123", "{}", privateKeyBase64);
        String sigGet = WeChatPaySignatureUtil.generateRsaSignature(
                "GET", "/v3/pay/transactions/native", "1700000000", "abc123", "", privateKeyBase64);
        assertNotEquals(sigPost, sigGet);
    }

    @Test
    @DisplayName("generateRsaSignature：null body 当作空字符串处理")
    void generateRsaSignature_nullBody() {
        String sig1 = WeChatPaySignatureUtil.generateRsaSignature(
                "GET", "/v3/pay/transactions/query", "1700000000", "abc123", null, privateKeyBase64);
        String sig2 = WeChatPaySignatureUtil.generateRsaSignature(
                "GET", "/v3/pay/transactions/query", "1700000000", "abc123", "", privateKeyBase64);
        assertEquals(sig1, sig2);
    }

    // ==================== RSA-SHA256 回调验签（平台证书公钥） ====================

    @Test
    @DisplayName("verifyCallbackSignatureWithPlatformCert：正确签名验签通过")
    void verifyCallbackSignatureWithPlatformCert_valid() {
        String timestamp = "1700000000";
        String nonce = "nonce123";
        String body = "{\"id\":\"evt_001\",\"event_type\":\"TRANSACTION.SUCCESS\"}";

        // 回调签名串格式：timestamp\nnonce\nbody\n
        String signContent = timestamp + "\n" + nonce + "\n" + body + "\n";
        // 使用 generateRsaSignature 对回调签名串做签名（底层 rsaSign 方法）
        // 注意：generateRsaSignature 构造的签名串格式为 method\nurl\ntimestamp\nnonce\nbody\n
        // 而回调验签串格式为 timestamp\nnonce\nbody\n
        // 因此需要直接用 Java 标准库对回调格式签名串做 RSA-SHA256 签名
        String signature = rsaSignForTest(signContent, privateKeyBase64);

        boolean verified = WeChatPaySignatureUtil.verifyCallbackSignatureWithPlatformCert(
                timestamp, nonce, body, signature, publicKeyBase64);

        assertTrue(verified);
    }

    @Test
    @DisplayName("verifyCallbackSignatureWithPlatformCert：错误签名验签失败")
    void verifyCallbackSignatureWithPlatformCert_invalidSignature() {
        String timestamp = "1700000000";
        String nonce = "nonce123";
        String body = "{\"id\":\"evt_001\"}";
        String wrongSignature = "wrong_signature_base64==";

        boolean verified = WeChatPaySignatureUtil.verifyCallbackSignatureWithPlatformCert(
                timestamp, nonce, body, wrongSignature, publicKeyBase64);

        assertFalse(verified);
    }

    @Test
    @DisplayName("verifyCallbackSignatureWithPlatformCert：不同密钥对验签失败")
    void verifyCallbackSignatureWithPlatformCert_wrongKey() throws Exception {
        String timestamp = "1700000000";
        String nonce = "nonce123";
        String body = "{\"id\":\"evt_001\"}";

        // 用第一对密钥签名
        String signContent = timestamp + "\n" + nonce + "\n" + body + "\n";
        String signature = rsaSignForTest(signContent, privateKeyBase64);

        // 生成第二对密钥用于验签
        KeyPairGenerator kpg2 = KeyPairGenerator.getInstance("RSA");
        kpg2.initialize(2048);
        KeyPair keyPair2 = kpg2.generateKeyPair();
        String wrongPublicKeyBase64 = Base64.getEncoder().encodeToString(keyPair2.getPublic().getEncoded());

        boolean verified = WeChatPaySignatureUtil.verifyCallbackSignatureWithPlatformCert(
                timestamp, nonce, body, signature, wrongPublicKeyBase64);

        assertFalse(verified);
    }

    // ==================== AES-256-GCM 解密 ====================

    @Test
    @DisplayName("decryptResource：使用正确密钥解密，内容与原文一致")
    void decryptResource_valid() throws Exception {
        // 生成 32 字节 AES 密钥（模拟 APIv3 密钥）
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey secretKey = keyGen.generateKey();
        String apiV3Key = new String(secretKey.getEncoded(), StandardCharsets.UTF_8);

        // 注意：APIv3 密钥是 32 字节字符串，不是 AES KeyGenerator 输出
        // 使用固定 32 字节密钥更贴近真实场景
        String testKey = "0123456789abcdef0123456789abcdef"; // 32 字节

        String plaintext = "{\"out_trade_no\":\"pay_001\",\"transaction_id\":\"wx_tx_001\",\"trade_state\":\"SUCCESS\"}";
        String nonce = "abcdef0123";
        String associatedData = "transaction";

        // 使用 Java 标准库加密
        String ciphertext = aesGcmEncryptForTest(plaintext, testKey, nonce, associatedData);

        // 解密
        String decrypted = WeChatPaySignatureUtil.decryptResource(ciphertext, nonce, associatedData, testKey);

        assertEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("decryptResource：空 associatedData 时正常解密")
    void decryptResource_emptyAssociatedData() throws Exception {
        String testKey = "0123456789abcdef0123456789abcdef";
        String plaintext = "{\"out_trade_no\":\"pay_002\"}";
        String nonce = "nonce12345";

        String ciphertext = aesGcmEncryptForTest(plaintext, testKey, nonce, "");

        String decrypted = WeChatPaySignatureUtil.decryptResource(ciphertext, nonce, "", testKey);

        assertEquals(plaintext, decrypted);
    }

    @Test
    @DisplayName("decryptResource：错误密钥解密抛出 RuntimeException")
    void decryptResource_wrongKey_throwsRuntimeException() throws Exception {
        String correctKey = "0123456789abcdef0123456789abcdef";
        String wrongKey = "abcdef0123456789abcdef0123456789";
        String plaintext = "{\"out_trade_no\":\"pay_003\"}";
        String nonce = "nonce12345";

        String ciphertext = aesGcmEncryptForTest(plaintext, correctKey, nonce, "");

        // 使用错误密钥解密应抛出 RuntimeException（含 AEADBadTagException）
        assertThrows(RuntimeException.class, () ->
                WeChatPaySignatureUtil.decryptResource(ciphertext, nonce, "", wrongKey));
    }

    // ==================== 旧方法向后兼容 ====================

    @Test
    @DisplayName("generateSignature（旧方法）：HMAC-SHA256 签名仍可调用，结果为 Base64 格式")
    void generateSignature_legacy_backwardCompatible() {
        String signature = WeChatPaySignatureUtil.generateSignature(
                "POST", "/v3/pay/transactions/native", "1700000000", "abc123", "{}", TEST_API_V3_KEY);

        assertNotNull(signature);
        assertTrue(signature.matches("^[A-Za-z0-9+/=]+$"));
    }

    @Test
    @DisplayName("generateSignature（旧方法）：相同输入生成相同签名（确定性）")
    void generateSignature_legacy_deterministic() {
        String sig1 = WeChatPaySignatureUtil.generateSignature(
                "POST", "/v3/pay/transactions/native", "1700000000", "abc123", "{}", TEST_API_V3_KEY);
        String sig2 = WeChatPaySignatureUtil.generateSignature(
                "POST", "/v3/pay/transactions/native", "1700000000", "abc123", "{}", TEST_API_V3_KEY);
        assertEquals(sig1, sig2);
    }

    @Test
    @DisplayName("verifyCallbackSignature（旧方法）：HMAC-SHA256 验签仍可调用")
    void verifyCallbackSignature_legacy_backwardCompatible() {
        String timestamp = "1700000000";
        String nonce = "nonce123";
        String body = "{\"id\":\"evt_001\"}";

        // 用 HMAC-SHA256 生成回调签名
        String signContent = timestamp + "\n" + nonce + "\n" + body + "\n";
        String signature = hmacSha256ForTest(signContent, TEST_API_V3_KEY);

        boolean verified = WeChatPaySignatureUtil.verifyCallbackSignature(
                timestamp, nonce, body, signature, TEST_API_V3_KEY);

        assertTrue(verified);
    }

    @Test
    @DisplayName("verifyCallbackSignature（旧方法）：错误签名验签失败")
    void verifyCallbackSignature_legacy_invalid() {
        boolean verified = WeChatPaySignatureUtil.verifyCallbackSignature(
                "1700000000", "nonce", "body", "wrong_signature", TEST_API_V3_KEY);
        assertFalse(verified);
    }

    // ==================== 测试辅助方法 ====================

    /**
     * 使用 Java 标准库对回调签名串做 RSA-SHA256 签名（用于测试验签）。
     */
    private String rsaSignForTest(String data, String privateKeyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(privateKeyBase64);
            java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
            java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance("RSA");
            java.security.PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

            java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(data.getBytes(StandardCharsets.UTF_8));

            byte[] signBytes = signature.sign();
            return Base64.getEncoder().encodeToString(signBytes);
        } catch (Exception e) {
            throw new RuntimeException("测试签名失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 Java 标准库做 AES-256-GCM 加密（用于测试解密）。
     */
    private String aesGcmEncryptForTest(String plaintext, String key, String nonce, String associatedData)
            throws Exception {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] nonceBytes = nonce.getBytes(StandardCharsets.UTF_8);
        byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);

        javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, nonceBytes);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

        if (associatedData != null && !associatedData.isEmpty()) {
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        }

        byte[] encryptedBytes = cipher.doFinal(plaintextBytes);
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    /**
     * 使用 Java 标准库做 HMAC-SHA256 签名（用于测试旧方法验签）。
     */
    private String hmacSha256ForTest(String data, String key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmacBytes);
        } catch (Exception e) {
            throw new RuntimeException("测试 HMAC 签名失败: " + e.getMessage(), e);
        }
    }
}