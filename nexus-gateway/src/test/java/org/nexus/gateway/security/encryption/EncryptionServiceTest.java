package org.nexus.gateway.security.encryption;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EncryptionService 单元测试 — AES-256-GCM 加密/解密。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>encrypt → decrypt 往返一致性</li>
 *   <li>不同 plaintext 产生不同 ciphertext</li>
 *   <li>decrypt 错误密钥/IV 时抛异常</li>
 *   <li>null 输入处理</li>
 *   <li>Base64 编解码往返</li>
 * </ul>
 */
@DisplayName("EncryptionService — AES-256-GCM 加密/解密")
class EncryptionServiceTest {

    private EncryptionService encryptionService;
    private byte[] dek;

    @BeforeEach
    void setUp() throws Exception {
        encryptionService = new EncryptionService();
        // 生成 32 字节 AES-256 DEK
        dek = new byte[32];
        SecureRandom.getInstanceStrong().nextBytes(dek);
    }

    @Nested
    @DisplayName("encrypt → decrypt 往返一致性")
    class RoundTripTest {

        @Test
        @DisplayName("加密后解密应还原原始明文")
        void encryptThenDecrypt_shouldReturnOriginalPlaintext() {
            String plaintext = "4111111111111111";

            EncryptedField encrypted = encryptionService.encrypt(plaintext, dek);
            String decrypted = encryptionService.decrypt(
                    encrypted.getCiphertext(), encrypted.getIv(), dek);

            assertEquals(plaintext, decrypted);
        }

        @Test
        @DisplayName("中文明文加密后解密应正确还原")
        void encryptThenDecrypt_shouldHandleChineseText() {
            String plaintext = "张三的信用卡号";

            EncryptedField encrypted = encryptionService.encrypt(plaintext, dek);
            String decrypted = encryptionService.decrypt(
                    encrypted.getCiphertext(), encrypted.getIv(), dek);

            assertEquals(plaintext, decrypted);
        }

        @Test
        @DisplayName("空字符串加密后解密应还原空字符串")
        void encryptThenDecrypt_shouldHandleEmptyString() {
            String plaintext = "";

            EncryptedField encrypted = encryptionService.encrypt(plaintext, dek);
            String decrypted = encryptionService.decrypt(
                    encrypted.getCiphertext(), encrypted.getIv(), dek);

            assertEquals(plaintext, decrypted);
        }

        @Test
        @DisplayName("长文本加密后解密应正确还原")
        void encryptThenDecrypt_shouldHandleLongText() {
            String plaintext = "A".repeat(10000);

            EncryptedField encrypted = encryptionService.encrypt(plaintext, dek);
            String decrypted = encryptionService.decrypt(
                    encrypted.getCiphertext(), encrypted.getIv(), dek);

            assertEquals(plaintext, decrypted);
        }
    }

    @Nested
    @DisplayName("不同 plaintext 产生不同 ciphertext")
    class DistinctCiphertextTest {

        @Test
        @DisplayName("相同明文两次加密应产生不同密文（IV 随机）")
        void encrypt_samePlaintextTwice_shouldProduceDifferentCiphertext() {
            String plaintext = "4111111111111111";

            EncryptedField encrypted1 = encryptionService.encrypt(plaintext, dek);
            EncryptedField encrypted2 = encryptionService.encrypt(plaintext, dek);

            // IV 不同（每次随机生成）
            assertNotEquals(
                    Base64.getEncoder().encodeToString(encrypted1.getIv()),
                    Base64.getEncoder().encodeToString(encrypted2.getIv()),
                    "两次加密的 IV 应不同");
            // 密文不同
            assertNotEquals(
                    Base64.getEncoder().encodeToString(encrypted1.getCiphertext()),
                    Base64.getEncoder().encodeToString(encrypted2.getCiphertext()),
                    "两次加密的密文应不同");
            // 但都能解密回相同明文
            assertEquals(plaintext, encryptionService.decrypt(
                    encrypted1.getCiphertext(), encrypted1.getIv(), dek));
            assertEquals(plaintext, encryptionService.decrypt(
                    encrypted2.getCiphertext(), encrypted2.getIv(), dek));
        }

        @Test
        @DisplayName("不同明文应产生不同密文")
        void encrypt_differentPlaintexts_shouldProduceDifferentCiphertext() {
            EncryptedField encrypted1 = encryptionService.encrypt("plaintext-A", dek);
            EncryptedField encrypted2 = encryptionService.encrypt("plaintext-B", dek);

            assertNotEquals(
                    Base64.getEncoder().encodeToString(encrypted1.getCiphertext()),
                    Base64.getEncoder().encodeToString(encrypted2.getCiphertext()),
                    "不同明文的密文应不同");
        }
    }

    @Nested
    @DisplayName("decrypt 错误密钥/IV 时抛异常")
    class DecryptFailureTest {

        @Test
        @DisplayName("使用错误密钥解密应抛 EncryptionException")
        void decrypt_withWrongKey_shouldThrowEncryptionException() {
            String plaintext = "sensitive-data";
            EncryptedField encrypted = encryptionService.encrypt(plaintext, dek);

            // 生成不同的 DEK
            byte[] wrongDek = new byte[32];
            new SecureRandom().nextBytes(wrongDek);

            EncryptionException ex = assertThrows(EncryptionException.class, () ->
                    encryptionService.decrypt(encrypted.getCiphertext(), encrypted.getIv(), wrongDek));

            assertEquals("DATA_INTEGRITY_VIOLATION", ex.getErrorCode());
        }

        @Test
        @DisplayName("使用错误 IV 解密应抛 EncryptionException")
        void decrypt_withWrongIv_shouldThrowEncryptionException() {
            String plaintext = "sensitive-data";
            EncryptedField encrypted = encryptionService.encrypt(plaintext, dek);

            // 生成不同的 IV
            byte[] wrongIv = new byte[12];
            new SecureRandom().nextBytes(wrongIv);

            EncryptionException ex = assertThrows(EncryptionException.class, () ->
                    encryptionService.decrypt(encrypted.getCiphertext(), wrongIv, dek));

            assertEquals("DATA_INTEGRITY_VIOLATION", ex.getErrorCode());
        }

        @Test
        @DisplayName("篡改密文后解密应抛 EncryptionException（GCM 认证标签验证失败）")
        void decrypt_withTamperedCiphertext_shouldThrowEncryptionException() {
            String plaintext = "sensitive-data";
            EncryptedField encrypted = encryptionService.encrypt(plaintext, dek);

            // 篡改密文（翻转第一个字节）
            byte[] tamperedCiphertext = encrypted.getCiphertext().clone();
            tamperedCiphertext[0] ^= 0x01;

            EncryptionException ex = assertThrows(EncryptionException.class, () ->
                    encryptionService.decrypt(tamperedCiphertext, encrypted.getIv(), dek));

            assertEquals("DATA_INTEGRITY_VIOLATION", ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("null 输入处理")
    class NullInputTest {

        @Test
        @DisplayName("encrypt(null, dek) 应返回 null")
        void encrypt_nullPlaintext_shouldReturnNull() {
            assertNull(encryptionService.encrypt(null, dek));
        }

        @Test
        @DisplayName("decrypt(null, iv, dek) 应返回 null")
        void decrypt_nullCiphertext_shouldReturnNull() {
            assertNull(encryptionService.decrypt(null, new byte[12], dek));
        }

        @Test
        @DisplayName("decrypt(ciphertext, null, dek) 应返回 null")
        void decrypt_nullIv_shouldReturnNull() {
            assertNull(encryptionService.decrypt(new byte[16], null, dek));
        }
    }

    @Nested
    @DisplayName("Base64 编解码往返")
    class Base64RoundTripTest {

        @Test
        @DisplayName("decryptFromBase64 应正确解密 Base64 编码的密文和 IV")
        void decryptFromBase64_shouldDecryptCorrectly() {
            String plaintext = "4111111111111111";
            EncryptedField encrypted = encryptionService.encrypt(plaintext, dek);

            String decrypted = encryptionService.decryptFromBase64(
                    encrypted.getCiphertextBase64(),
                    encrypted.getIvBase64(),
                    dek);

            assertEquals(plaintext, decrypted);
        }

        @Test
        @DisplayName("decryptFromBase64(null, ...) 应返回 null")
        void decryptFromBase64_nullInput_shouldReturnNull() {
            assertNull(encryptionService.decryptFromBase64(null, "iv-base64", dek));
            assertNull(encryptionService.decryptFromBase64("ct-base64", null, dek));
        }
    }

    @Nested
    @DisplayName("EncryptedField DTO")
    class EncryptedFieldTest {

        @Test
        @DisplayName("fromBase64 → getCiphertextBase64/getIvBase64 应保持一致")
        void fromBase64_thenGetBase64_shouldBeConsistent() {
            String plaintext = "test-data";
            EncryptedField encrypted = encryptionService.encrypt(plaintext, dek);

            String ctBase64 = encrypted.getCiphertextBase64();
            String ivBase64 = encrypted.getIvBase64();

            EncryptedField restored = EncryptedField.fromBase64(ctBase64, ivBase64);

            String decrypted = encryptionService.decrypt(
                    restored.getCiphertext(), restored.getIv(), dek);
            assertEquals(plaintext, decrypted);
        }
    }
}