package org.nexus.gateway.security.encryption;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KeyManagementService 单元测试 — KEK/DEK 管理。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>generateDek 生成的 DEK 长度为 32 字节（AES-256）</li>
 *   <li>encryptDek → decryptDek 往返一致性</li>
 *   <li>isAvailable 在 KEK 未配置时返回 false</li>
 *   <li>KEK 版本不可用时抛 KeyVersionUnavailableException</li>
 *   <li>rotateKek 轮换</li>
 *   <li>decryptDekCached 缓存</li>
 *   <li>archiveKek 归档</li>
 * </ul>
 */
@DisplayName("KeyManagementService — KEK/DEK 管理")
class KeyManagementServiceTest {

    private KeyManagementService keyManagementService;

    /** 生成 Base64 编码的 32 字节 KEK，用于测试 */
    private String generateTestKekBase64() {
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        return Base64.getEncoder().encodeToString(kek);
    }

    /** 通过反射设置 kekBase64 字段并调用 init() */
    private void configureKek(String kekBase64) throws Exception {
        Field kekField = KeyManagementService.class.getDeclaredField("kekBase64");
        kekField.setAccessible(true);
        kekField.set(keyManagementService, kekBase64);

        // 调用 init() 方法（@PostConstruct）
        Field currentKekVersionField = KeyManagementService.class.getDeclaredField("currentKekVersion");
        currentKekVersionField.setAccessible(true);
        currentKekVersionField.set(keyManagementService, null);

        // 清空 kekCache
        Field kekCacheField = KeyManagementService.class.getDeclaredField("kekCache");
        kekCacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Integer, Object> kekCache =
                (java.util.Map<Integer, Object>) kekCacheField.get(keyManagementService);
        kekCache.clear();

        keyManagementService.init();
    }

    @BeforeEach
    void setUp() throws Exception {
        keyManagementService = new KeyManagementService();
    }

    @Nested
    @DisplayName("generateDek — DEK 生成")
    class GenerateDekTest {

        @Test
        @DisplayName("生成的 DEK 长度应为 32 字节（AES-256）")
        void generateDek_shouldReturn32Bytes() {
            byte[] dek = keyManagementService.generateDek();

            assertNotNull(dek);
            assertEquals(32, dek.length, "DEK 长度应为 32 字节 (AES-256)");
        }

        @Test
        @DisplayName("两次生成的 DEK 应不同（随机性）")
        void generateDek_twice_shouldReturnDifferentKeys() {
            byte[] dek1 = keyManagementService.generateDek();
            byte[] dek2 = keyManagementService.generateDek();

            assertNotEquals(Base64.getEncoder().encodeToString(dek1),
                    Base64.getEncoder().encodeToString(dek2),
                    "两次生成的 DEK 应不同");
        }
    }

    @Nested
    @DisplayName("encryptDek → decryptDek 往返一致性")
    class DekRoundTripTest {

        @Test
        @DisplayName("加密 DEK 后解密应还原原始 DEK")
        void encryptDekThenDecryptDek_shouldReturnOriginalDek() throws Exception {
            configureKek(generateTestKekBase64());

            byte[] originalDek = keyManagementService.generateDek();
            byte[] encryptedDek = keyManagementService.encryptDek(originalDek, 1);
            byte[] decryptedDek = keyManagementService.decryptDek(encryptedDek, 1);

            assertArrayEquals(originalDek, decryptedDek, "解密后的 DEK 应与原始 DEK 一致");
        }

        @Test
        @DisplayName("加密后的 DEK 长度应大于原始 DEK（含 IV + authTag）")
        void encryptDek_shouldProduceLargerOutput() throws Exception {
            configureKek(generateTestKekBase64());

            byte[] originalDek = keyManagementService.generateDek();
            byte[] encryptedDek = keyManagementService.encryptDek(originalDek, 1);

            // IV(12) + ciphertext(32) + authTag(16) = 60
            assertTrue(encryptedDek.length > originalDek.length,
                    "加密后的 DEK 应包含 IV 和 authTag，长度大于原始 DEK");
            assertEquals(60, encryptedDek.length,
                    "IV(12) + ciphertext(32) + authTag(16) = 60 字节");
        }
    }

    @Nested
    @DisplayName("isAvailable — KEK 可用性检查")
    class IsAvailableTest {

        @Test
        @DisplayName("KEK 未配置时 isAvailable 应返回 false")
        void isAvailable_noKekConfigured_shouldReturnFalse() throws Exception {
            configureKek("");

            assertFalse(keyManagementService.isAvailable(),
                    "KEK 未配置时 isAvailable 应返回 false");
        }

        @Test
        @DisplayName("KEK 已配置时 isAvailable 应返回 true")
        void isAvailable_kekConfigured_shouldReturnTrue() throws Exception {
            configureKek(generateTestKekBase64());

            assertTrue(keyManagementService.isAvailable(),
                    "KEK 已配置时 isAvailable 应返回 true");
        }

        @Test
        @DisplayName("KEK 未配置时 getCurrentKekVersion 应返回 null")
        void getCurrentKekVersion_noKek_shouldReturnNull() throws Exception {
            configureKek("");

            assertNull(keyManagementService.getCurrentKekVersion(),
                    "KEK 未配置时版本号应为 null");
        }

        @Test
        @DisplayName("KEK 已配置时 getCurrentKekVersion 应返回 1")
        void getCurrentKekVersion_kekConfigured_shouldReturn1() throws Exception {
            configureKek(generateTestKekBase64());

            assertEquals(1, keyManagementService.getCurrentKekVersion(),
                    "初始 KEK 版本应为 1");
        }
    }

    @Nested
    @DisplayName("KEK 版本不可用时抛异常")
    class KekVersionUnavailableTest {

        @Test
        @DisplayName("encryptDek 使用不存在的 KEK 版本应抛 KeyVersionUnavailableException")
        void encryptDek_unavailableVersion_shouldThrow() throws Exception {
            configureKek(generateTestKekBase64());

            byte[] dek = keyManagementService.generateDek();

            KeyVersionUnavailableException ex = assertThrows(
                    KeyVersionUnavailableException.class, () ->
                            keyManagementService.encryptDek(dek, 999));

            assertEquals(999, ex.getKekVersion());
        }

        @Test
        @DisplayName("decryptDek 使用不存在的 KEK 版本应抛 KeyVersionUnavailableException")
        void decryptDek_unavailableVersion_shouldThrow() throws Exception {
            configureKek(generateTestKekBase64());

            byte[] fakeEncryptedDek = new byte[60];

            KeyVersionUnavailableException ex = assertThrows(
                    KeyVersionUnavailableException.class, () ->
                            keyManagementService.decryptDek(fakeEncryptedDek, 999));

            assertEquals(999, ex.getKekVersion());
        }
    }

    @Nested
    @DisplayName("rotateKek — KEK 轮换")
    class RotateKekTest {

        @Test
        @DisplayName("轮换后版本号应递增")
        void rotateKek_shouldIncrementVersion() throws Exception {
            configureKek(generateTestKekBase64());

            assertEquals(1, keyManagementService.getCurrentKekVersion());

            int newVersion = keyManagementService.rotateKek();

            assertEquals(2, newVersion);
            assertEquals(2, keyManagementService.getCurrentKekVersion());
        }

        @Test
        @DisplayName("轮换后旧版本 KEK 仍可用（渐进式迁移）")
        void rotateKek_oldVersionStillAvailable() throws Exception {
            configureKek(generateTestKekBase64());

            // 用 v1 加密 DEK
            byte[] dek = keyManagementService.generateDek();
            byte[] encryptedDek = keyManagementService.encryptDek(dek, 1);

            // 轮换到 v2
            keyManagementService.rotateKek();

            // v1 仍可解密旧 DEK
            byte[] decryptedDek = keyManagementService.decryptDek(encryptedDek, 1);
            assertArrayEquals(dek, decryptedDek, "轮换后旧版本 KEK 应仍可解密");

            // v2 也可用于新加密
            byte[] encryptedDekV2 = keyManagementService.encryptDek(dek, 2);
            byte[] decryptedDekV2 = keyManagementService.decryptDek(encryptedDekV2, 2);
            assertArrayEquals(dek, decryptedDekV2, "新版本 KEK 应可用于加密/解密");
        }

        @Test
        @DisplayName("KEK 未配置时轮换应抛 KeyVersionUnavailableException")
        void rotateKek_noKek_shouldThrow() throws Exception {
            configureKek("");

            assertThrows(KeyVersionUnavailableException.class, () ->
                    keyManagementService.rotateKek());
        }

        @Test
        @DisplayName("isKekVersionAvailable 检查版本可用性")
        void isKekVersionAvailable_shouldReflectCacheState() throws Exception {
            configureKek(generateTestKekBase64());

            assertTrue(keyManagementService.isKekVersionAvailable(1));
            assertFalse(keyManagementService.isKekVersionAvailable(999));
        }
    }

    @Nested
    @DisplayName("archiveKek — KEK 归档")
    class ArchiveKekTest {

        @Test
        @DisplayName("归档后版本不再可用")
        void archiveKek_shouldRemoveFromCache() throws Exception {
            configureKek(generateTestKekBase64());

            assertTrue(keyManagementService.isKekVersionAvailable(1));

            keyManagementService.archiveKek(1);

            assertFalse(keyManagementService.isKekVersionAvailable(1),
                    "归档后 KEK 版本应不再可用");
        }

        @Test
        @DisplayName("归档后使用该版本加密应抛异常")
        void archiveKek_thenEncryptShouldThrow() throws Exception {
            configureKek(generateTestKekBase64());

            keyManagementService.archiveKek(1);

            byte[] dek = keyManagementService.generateDek();
            assertThrows(KeyVersionUnavailableException.class, () ->
                    keyManagementService.encryptDek(dek, 1));
        }
    }

    @Nested
    @DisplayName("decryptDekCached — 带缓存的 DEK 解密")
    class DecryptDekCachedTest {

        @Test
        @DisplayName("相同 metadataId 第二次解密应使用缓存（返回相同引用）")
        void decryptDekCached_shouldCacheResult() throws Exception {
            configureKek(generateTestKekBase64());

            byte[] dek = keyManagementService.generateDek();
            byte[] encryptedDek = keyManagementService.encryptDek(dek, 1);

            byte[] result1 = keyManagementService.decryptDekCached(100L, encryptedDek, 1);
            byte[] result2 = keyManagementService.decryptDekCached(100L, encryptedDek, 1);

            assertArrayEquals(dek, result1, "第一次解密应返回正确 DEK");
            assertArrayEquals(dek, result2, "第二次解密应返回相同 DEK");
            // 缓存应返回同一引用（computeIfAbsent 语义）
            assertSame(result1, result2, "缓存命中应返回同一对象引用");
        }

        @Test
        @DisplayName("不同 metadataId 应分别解密")
        void decryptDekCached_differentMetadataIds_shouldDecryptSeparately() throws Exception {
            configureKek(generateTestKekBase64());

            byte[] dek1 = keyManagementService.generateDek();
            byte[] dek2 = keyManagementService.generateDek();
            byte[] encryptedDek1 = keyManagementService.encryptDek(dek1, 1);
            byte[] encryptedDek2 = keyManagementService.encryptDek(dek2, 1);

            byte[] result1 = keyManagementService.decryptDekCached(100L, encryptedDek1, 1);
            byte[] result2 = keyManagementService.decryptDekCached(200L, encryptedDek2, 1);

            assertArrayEquals(dek1, result1);
            assertArrayEquals(dek2, result2);
            assertNotSame(result1, result2, "不同 metadataId 应返回不同对象");
        }
    }
}